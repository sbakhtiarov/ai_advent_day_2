package com.aichallenge.day2.agent.server

import com.aichallenge.day2.agent.review.PullRequestReviewModelClient
import com.aichallenge.day2.agent.review.PullRequestReviewModelResponse
import com.aichallenge.day2.agent.review.ReviewTokenUsage
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OpenAiPullRequestReviewModelClient(
    private val httpClient: HttpClient,
    private val json: Json,
    private val config: HttpApiServerConfig,
    private val apiTrafficLogger: JvmApiTrafficFileLogger? = null,
) : PullRequestReviewModelClient {
    override val modelId: String = config.apiModel
    override val contextWindowTokens: Int? = JvmModelCatalog.contextWindowTokens(config.apiModel)

    override suspend fun review(
        systemPrompt: String,
        contextSystemMessage: String,
        userPrompt: String,
    ): PullRequestReviewModelResponse {
        val requestUrl = "${config.apiBaseUrl}/responses"
        val requestPayload = ResponsesApiRequest(
            model = modelId,
            instructions = sequenceOf(systemPrompt.trim(), contextSystemMessage.trim())
                .filter { it.isNotEmpty() }
                .joinToString(separator = "\n\n")
                .ifBlank { null },
            temperature = config.apiTemperature,
            input = listOf(
                ResponseInputItem(
                    role = "user",
                    content = listOf(
                        RequestContent(
                            type = "input_text",
                            text = userPrompt,
                        ),
                    ),
                ),
            ),
        )
        val requestHeaders = listOf(
            HttpHeaders.ContentType to ContentType.Application.Json.toString(),
            HttpHeaders.Authorization to "Bearer ${config.apiKey}",
        )
        val exchangeId = apiTrafficLogger?.reserveExchangeId() ?: 0L
        val rawRequestBody = json.encodeToString(requestPayload)
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
        } catch (error: Throwable) {
            apiTrafficLogger?.logFailure(exchangeId, error)
            throw error
        }

        val rawResponse = try {
            response.bodyAsText()
        } catch (error: Throwable) {
            apiTrafficLogger?.logFailure(exchangeId, error)
            throw error
        }

        apiTrafficLogger?.logResponse(
            exchangeId = exchangeId,
            statusCode = response.status.value,
            statusDescription = response.status.description,
            headers = response.headers.entries().map { (name, values) ->
                name to values.joinToString(separator = ", ")
            },
            body = rawResponse,
        )

        if (response.status.value !in 200..299) {
            throw IllegalStateException(
                "API request failed with HTTP ${response.status.value}: $rawResponse",
            )
        }

        val payload = runCatching {
            json.decodeFromString<ResponsesApiEnvelope>(rawResponse)
        }.getOrElse { error ->
            throw IllegalStateException("API returned malformed JSON.", error)
        }
        val output = payload.outputText?.trim()?.takeIf { it.isNotEmpty() }
            ?: payload.output.asSequence()
                .flatMap { item -> item.content.asSequence() }
                .mapNotNull { item -> item.text?.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(separator = "\n")
                .trim()

        if (output.isBlank()) {
            throw IllegalStateException("API returned an empty response.")
        }

        return PullRequestReviewModelResponse(
            reviewMarkdown = output,
            usage = payload.usage?.toReviewTokenUsage(),
        )
    }
}

private fun UsageDetails.toReviewTokenUsage(): ReviewTokenUsage? {
    val total = totalTokens ?: return null
    val input = inputTokens ?: return null
    val output = outputTokens ?: return null
    return ReviewTokenUsage(
        totalTokens = total,
        inputTokens = input,
        outputTokens = output,
    )
}

@Serializable
private data class ResponsesApiRequest(
    val model: String,
    val instructions: String? = null,
    val temperature: Double? = null,
    val input: List<ResponseInputItem>,
)

@Serializable
private data class ResponseInputItem(
    val role: String,
    val content: List<RequestContent>,
)

@Serializable
private data class RequestContent(
    val type: String,
    val text: String,
)

@Serializable
private data class ResponsesApiEnvelope(
    @SerialName("output_text")
    val outputText: String? = null,
    val output: List<OutputItem> = emptyList(),
    val usage: UsageDetails? = null,
)

@Serializable
private data class OutputItem(
    val content: List<OutputContent> = emptyList(),
)

@Serializable
private data class OutputContent(
    val text: String? = null,
)

@Serializable
private data class UsageDetails(
    @SerialName("total_tokens")
    val totalTokens: Int? = null,
    @SerialName("input_tokens")
    val inputTokens: Int? = null,
    @SerialName("output_tokens")
    val outputTokens: Int? = null,
)
