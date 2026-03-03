package com.aichallenge.day2.agent.data.remote

import com.aichallenge.day2.agent.core.config.AppConfig
import com.aichallenge.day2.agent.domain.model.MessageRole
import com.aichallenge.day2.agent.domain.model.PromptRequestData
import com.aichallenge.day2.agent.domain.model.TokenUsage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

class OpenAiRemoteDataSource(
    private val httpClient: HttpClient,
    private val config: AppConfig,
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

        val response = httpClient.post("${config.baseUrl}/responses") {
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(
                ResponsesApiRequest(
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
                ),
            )
        }

        if (response.status.value !in 200..299) {
            val payload = response.body<String>()
            throw IllegalStateException(
                "OpenAI request failed with HTTP ${response.status.value}: $payload",
            )
        }

        val payload = response.body<ResponsesApiEnvelope>()
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
