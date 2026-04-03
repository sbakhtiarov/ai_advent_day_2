package com.aichallenge.day2.agent.data.remote

import com.aichallenge.day2.agent.core.config.ApiSettingsService
import com.aichallenge.day2.agent.core.config.ConfiguredApi
import com.aichallenge.day2.agent.core.logging.ApiTrafficFileLogger
import com.aichallenge.day2.agent.domain.model.MessageRole
import com.aichallenge.day2.agent.domain.model.LlmToolCapabilities
import com.aichallenge.day2.agent.domain.model.PromptRequestData
import com.aichallenge.day2.agent.domain.model.PrivateToolBinding
import com.aichallenge.day2.agent.domain.model.PrivateToolResult
import com.aichallenge.day2.agent.domain.model.PrivateToolTarget
import com.aichallenge.day2.agent.domain.model.TokenUsage
import com.aichallenge.day2.agent.domain.model.ToolCallTraceEvent
import com.aichallenge.day2.agent.domain.model.ToolCallTraceObserver
import com.aichallenge.day2.agent.domain.service.PrivateToolExecutionService
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class OpenAiRemoteDataSource(
    private val httpClient: HttpClient,
    private val apiSettingsService: ApiSettingsService,
    private val json: Json,
    private val apiTrafficLogger: ApiTrafficFileLogger? = null,
    private val privateToolExecutionService: PrivateToolExecutionService,
) {
    data class AssistantReply(
        val content: String,
        val usage: TokenUsage? = null,
    )

    suspend fun fetchAssistantReply(
        prompt: PromptRequestData,
        temperature: Double? = null,
        model: String? = null,
        toolCallTraceObserver: ToolCallTraceObserver? = null,
    ): AssistantReply {
        require(temperature == null || temperature in 0.0..2.0) {
            "Temperature must be in range 0..2."
        }

        val instructions = sequenceOf(prompt.systemPrompt.trim())
            .plus(prompt.contextSystemMessages.asSequence().map { context -> context.trim() })
            .filter { it.isNotEmpty() }
            .joinToString(separator = "\n\n")
            .ifBlank { null }
        val activeApi = apiSettingsService.currentApi()
            ?: throw IllegalStateException("No API is configured. Define APIs in ~/.kotlin-agent-cli/api-settings.json and use /api to select one.")
        val requestUrl = "${activeApi.baseUrl}/responses"
        val responseTools = buildResponseTools(prompt.toolCapabilities)
        val privateToolBindings = prompt.toolCapabilities.privateTools.associateBy(PrivateToolBinding::modelToolName)
        var totalUsage: TokenUsage? = null
        var executedPrivateToolCalls = 0
        var retriedWithoutPublicMcpTools = false
        var requestPayload = ResponsesApiRequest(
            model = model ?: activeApi.selectedModel,
            instructions = instructions,
            temperature = temperature,
            input = prompt.messages.map { message ->
                ResponseInputItem(
                    role = message.role.toApiRole(),
                    content = listOf(
                        RequestContent(
                            type = message.role.toApiContentType(),
                            text = message.content,
                        ),
                    ),
                )
            },
            tools = responseTools.takeUnless(List<ResponseTool>::isEmpty),
            parallelToolCalls = responseTools.takeUnless(List<ResponseTool>::isEmpty)?.let { false },
        )

        while (true) {
            val payload = executeRequest(
                activeApi = activeApi,
                requestUrl = requestUrl,
                requestPayload = requestPayload,
            )
            totalUsage = mergeUsage(totalUsage, extractUsage(payload))

            val pendingFunctionCalls = try {
                extractPendingFunctionCalls(payload)
            } catch (missingName: MissingFunctionNameException) {
                if (!retriedWithoutPublicMcpTools && requestPayload.hasPublicMcpTools()) {
                    retriedWithoutPublicMcpTools = true
                    requestPayload = requestPayload.withoutPublicMcpTools()
                    continue
                }
                throw missingName
            }
            if (pendingFunctionCalls.isEmpty()) {
                val output = extractOutput(payload)
                if (output.isBlank()) {
                    throw IllegalStateException("API '${activeApi.name}' returned an empty response.")
                }
                return AssistantReply(
                    content = output,
                    usage = totalUsage,
                )
            }

            val responseId = payload.id?.trim().takeUnless { it.isNullOrEmpty() }
                ?: throw IllegalStateException("API '${activeApi.name}' returned function calls without a response id.")
            val functionOutputs = pendingFunctionCalls.map { functionCall ->
                executedPrivateToolCalls += 1
                if (executedPrivateToolCalls > MAX_PRIVATE_TOOL_CALLS_PER_TURN) {
                    throw IllegalStateException(
                        "API '${activeApi.name}' requested more than $MAX_PRIVATE_TOOL_CALLS_PER_TURN private MCP tool calls in one turn.",
                    )
                }
                val binding = privateToolBindings[functionCall.name]
                    ?: throw IllegalStateException("API '${activeApi.name}' requested unknown private MCP tool '${functionCall.name}'.")
                val parsedArguments = runCatching { parseFunctionCallArguments(functionCall.arguments) }
                parsedArguments.fold(
                    onSuccess = { arguments ->
                        toolCallTraceObserver?.onToolCallTrace(
                            ToolCallTraceEvent.Started(
                                toolLabel = buildToolTraceLabel(binding),
                                statusMessage = ToolCallStatusMessageFormatter.format(binding, arguments),
                            ),
                        )
                        buildFunctionCallOutput(
                            binding = binding,
                            functionCall = functionCall,
                            arguments = arguments,
                        )
                    },
                    onFailure = { throwable ->
                        buildFailedFunctionCallOutput(
                            binding = binding,
                            functionCall = functionCall,
                            throwable = throwable,
                        )
                    },
                )
            }

            requestPayload = ResponsesApiRequest(
                model = model ?: activeApi.selectedModel,
                instructions = instructions,
                temperature = temperature,
                input = functionOutputs.map { output ->
                    ResponseInputItem(
                        type = output.type,
                        callId = output.callId,
                        output = output.output,
                    )
                },
                tools = responseTools.takeUnless(List<ResponseTool>::isEmpty),
                parallelToolCalls = responseTools.takeUnless(List<ResponseTool>::isEmpty)?.let { false },
                previousResponseId = responseId,
            )
        }
    }

    private fun extractOutput(payload: ResponsesApiEnvelope): String {
        payload.outputText?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        return payload.output
            .asSequence()
            .flatMap { it.content.asSequence() }
            .mapNotNull { it.text?.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(separator = "\n")
    }

    private fun buildResponseTools(capabilities: LlmToolCapabilities): List<ResponseTool> {
        val publicTools = capabilities.publicMcpServers.map { server ->
            ResponseTool(
                type = "mcp",
                serverLabel = server.serverLabel,
                serverUrl = server.serverUrl,
                requireApproval = "never",
            )
        }
        val privateTools = capabilities.privateTools.map { tool ->
            ResponseTool(
                type = "function",
                name = tool.modelToolName,
                description = tool.description,
                parameters = tool.parametersSchema,
            )
        }
        return publicTools + privateTools
    }

    private suspend fun executeRequest(
        activeApi: ConfiguredApi,
        requestUrl: String,
        requestPayload: ResponsesApiRequest,
    ): ResponsesApiEnvelope {
        val rawRequestBody = json.encodeToString(requestPayload)
        val exchangeId = apiTrafficLogger?.reserveExchangeId() ?: 0L
        val requestHeaders = buildRequestHeaders(activeApi)

        apiTrafficLogger?.logRequest(
            exchangeId = exchangeId,
            method = "POST",
            url = requestUrl,
            headers = requestHeaders,
            body = rawRequestBody,
        )

        val response = try {
            httpClient.post(requestUrl) {
                requestHeaders.forEach { (name, value) ->
                    header(name, value)
                }
                contentType(ContentType.Application.Json)
                setBody(requestPayload)
            }
        } catch (throwable: Throwable) {
            apiTrafficLogger?.logFailure(exchangeId, throwable)
            throw throwable
        }
        val rawResponseBody = try {
            response.bodyAsText()
        } catch (throwable: Throwable) {
            apiTrafficLogger?.logFailure(exchangeId, throwable)
            throw throwable
        }

        apiTrafficLogger?.logResponse(
            exchangeId = exchangeId,
            statusCode = response.status.value,
            statusDescription = response.status.description,
            headers = response.headers.entries().map { (name, values) ->
                name to values.joinToString(separator = ", ")
            },
            body = rawResponseBody,
        )

        if (response.status.value !in 200..299) {
            throw IllegalStateException(
                "API '${activeApi.name}' request failed with HTTP ${response.status.value}: $rawResponseBody",
            )
        }

        return json.decodeFromString(rawResponseBody)
    }

    private fun buildRequestHeaders(api: ConfiguredApi): List<Pair<String, String>> {
        val headers = mutableListOf(
            HttpHeaders.ContentType to ContentType.Application.Json.toString(),
        )
        api.apiKey.takeIf { it.isNotBlank() }?.let { apiKey ->
            headers += HttpHeaders.Authorization to "Bearer $apiKey"
        }
        return headers
    }

    private fun extractPendingFunctionCalls(payload: ResponsesApiEnvelope): List<PendingFunctionCall> {
        return payload.output.mapNotNull { item ->
            if (item.type != "function_call") {
                return@mapNotNull null
            }

            val callId = item.callId?.trim().takeUnless { it.isNullOrEmpty() }
                ?: throw IllegalStateException("LLM provider returned a function call without call_id.")
            val name = item.name?.trim().takeUnless { it.isNullOrEmpty() }
                ?: throw MissingFunctionNameException()

            PendingFunctionCall(
                callId = callId,
                name = name,
                arguments = item.arguments.orEmpty(),
            )
        }
    }

    private suspend fun buildFunctionCallOutput(
        binding: PrivateToolBinding,
        functionCall: PendingFunctionCall,
        arguments: JsonObject,
    ): ResponsesApiFunctionCallOutput {
        val output = runCatching {
            val result = privateToolExecutionService.execute(binding, arguments)
            serializeSuccessfulFunctionOutput(
                binding = binding,
                result = result,
            )
        }.getOrElse { throwable ->
            serializeFailedFunctionOutput(
                binding = binding,
                throwable = throwable,
            )
        }

        return ResponsesApiFunctionCallOutput(
            callId = functionCall.callId,
            output = output,
        )
    }

    private fun buildFailedFunctionCallOutput(
        binding: PrivateToolBinding,
        functionCall: PendingFunctionCall,
        throwable: Throwable,
    ): ResponsesApiFunctionCallOutput {
        return ResponsesApiFunctionCallOutput(
            callId = functionCall.callId,
            output = serializeFailedFunctionOutput(
                binding = binding,
                throwable = throwable,
            ),
        )
    }

    private fun parseFunctionCallArguments(rawArguments: String): JsonObject {
        if (rawArguments.isBlank()) {
            return buildJsonObject {}
        }
        val parsedElement = runCatching {
            json.parseToJsonElement(rawArguments)
        }.getOrElse {
            throw IllegalArgumentException("Private MCP tool arguments must be valid JSON.")
        }
        return parsedElement as? JsonObject
            ?: throw IllegalArgumentException("Private MCP tool arguments must be a JSON object.")
    }

    private fun serializeSuccessfulFunctionOutput(
        binding: PrivateToolBinding,
        result: PrivateToolResult,
    ): String {
        val payload = buildJsonObject {
            put("ok", true)
            put("tool", binding.modelToolName)
            put("is_error", result.isError)
            if (result.structuredContent != null) {
                put("structured_content", result.structuredContent)
            }
            if (result.content.isNotEmpty()) {
                put("content", result.content)
            }
            if (result.meta != null) {
                put("_meta", result.meta)
            }
        }
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    private fun serializeFailedFunctionOutput(
        binding: PrivateToolBinding,
        throwable: Throwable,
    ): String {
        val payload = buildJsonObject {
            put("ok", false)
            put("tool", binding.modelToolName)
            put("error", throwable.message?.trim().takeUnless { it.isNullOrEmpty() } ?: "Unexpected error")
        }
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    private fun buildToolTraceLabel(binding: PrivateToolBinding): String {
        return when (val target = binding.target) {
            is PrivateToolTarget.BuiltIn -> "built-in '${target.toolId}'"
            is PrivateToolTarget.Mcp -> "MCP '${target.server.name}/${target.sourceToolName}'"
        }
    }

    private fun extractUsage(payload: ResponsesApiEnvelope): TokenUsage? {
        val usage = payload.usage ?: return null
        val inputTokens = usage.inputTokens ?: return null
        val outputTokens = usage.outputTokens ?: return null
        val totalTokens = usage.totalTokens ?: (inputTokens + outputTokens)
        if (totalTokens < 0 || inputTokens < 0 || outputTokens < 0) {
            return null
        }
        return TokenUsage(
            totalTokens = totalTokens,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
        )
    }

    private fun mergeUsage(current: TokenUsage?, next: TokenUsage?): TokenUsage? {
        if (current == null) {
            return next
        }
        if (next == null) {
            return current
        }
        return TokenUsage(
            totalTokens = current.totalTokens + next.totalTokens,
            inputTokens = current.inputTokens + next.inputTokens,
            outputTokens = current.outputTokens + next.outputTokens,
        )
    }

    companion object {
        private const val MAX_PRIVATE_TOOL_CALLS_PER_TURN = 16
    }
}

private class MissingFunctionNameException : IllegalStateException("LLM provider returned a function call without name.")

private fun ResponsesApiRequest.hasPublicMcpTools(): Boolean {
    return tools?.any { tool -> tool.type == "mcp" } == true
}

private fun ResponsesApiRequest.withoutPublicMcpTools(): ResponsesApiRequest {
    val filteredTools = tools
        ?.filterNot { tool -> tool.type == "mcp" }
        ?.takeUnless(List<ResponseTool>::isEmpty)
    return copy(
        tools = filteredTools,
        parallelToolCalls = filteredTools?.let { false },
    )
}

private fun MessageRole.toApiRole(): String = when (this) {
    MessageRole.USER -> "user"
    MessageRole.ASSISTANT -> "assistant"
    MessageRole.SYSTEM -> "system"
}

private fun MessageRole.toApiContentType(): String = when (this) {
    MessageRole.USER -> "input_text"
    MessageRole.ASSISTANT -> "output_text"
    MessageRole.SYSTEM -> "input_text"
}
