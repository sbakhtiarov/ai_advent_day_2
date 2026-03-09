package com.aichallenge.day2.agent.data.remote

import com.aichallenge.day2.agent.core.config.AppConfig
import com.aichallenge.day2.agent.core.logging.ApiTrafficFileLogger
import com.aichallenge.day2.agent.domain.model.MessageRole
import com.aichallenge.day2.agent.domain.model.PromptRequestData
import com.aichallenge.day2.agent.domain.model.TokenUsage
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

class OpenAiRemoteDataSource(
    private val httpClient: HttpClient,
    private val config: AppConfig,
    private val json: Json,
    private val apiTrafficLogger: ApiTrafficFileLogger? = null,
) {
    data class AssistantReply(
        val content: String,
        val usage: TokenUsage? = null,
    )

    suspend fun fetchAssistantReply(
        prompt: PromptRequestData,
        temperature: Double? = null,
        model: String? = null,
    ): AssistantReply {
        require(temperature == null || temperature in 0.0..2.0) {
            "Temperature must be in range 0..2."
        }

        val instructions = sequenceOf(prompt.systemPrompt.trim())
            .plus(prompt.contextSystemMessages.asSequence().map { context -> context.trim() })
            .filter { it.isNotEmpty() }
            .joinToString(separator = "\n\n")
            .ifBlank { null }
        val inputMessages = prompt.messages
        val requestPayload = ResponsesApiRequest(
            model = model ?: config.model,
            instructions = instructions,
            temperature = temperature,
            input = inputMessages.map { message ->
                RequestMessage(
                    role = message.role.toApiRole(),
                    content = listOf(
                        RequestContent(
                            type = message.role.toApiContentType(),
                            text = message.content,
                        ),
                    ),
                )
            },
        )
        val rawRequestBody = json.encodeToString(requestPayload)
        val exchangeId = apiTrafficLogger?.reserveExchangeId() ?: 0L
        val requestUrl = "${config.baseUrl}/responses"

        apiTrafficLogger?.logRequest(
            exchangeId = exchangeId,
            method = "POST",
            url = requestUrl,
            headers = listOf(
                HttpHeaders.Authorization to "Bearer ${config.apiKey}",
                HttpHeaders.ContentType to ContentType.Application.Json.toString(),
            ),
            body = rawRequestBody,
        )

        val response = try {
            httpClient.post(requestUrl) {
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
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
                "OpenAI request failed with HTTP ${response.status.value}: $rawResponseBody",
            )
        }

        val payload = json.decodeFromString<ResponsesApiEnvelope>(rawResponseBody)
        val output = extractOutput(payload)
        if (output.isBlank()) {
            throw IllegalStateException("OpenAI returned an empty response.")
        }

        return AssistantReply(
            content = output,
            usage = extractUsage(payload),
        )
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
