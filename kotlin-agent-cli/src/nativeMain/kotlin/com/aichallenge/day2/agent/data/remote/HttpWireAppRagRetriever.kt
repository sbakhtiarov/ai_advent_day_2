package com.aichallenge.day2.agent.data.remote

import com.aichallenge.day2.agent.domain.model.RagRetrieveRequest
import com.aichallenge.day2.agent.domain.model.RagRetrieveResponse
import com.aichallenge.day2.agent.domain.model.RagRetrievedChunk
import com.aichallenge.day2.agent.domain.service.WireAppRagRetriever
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class HttpWireAppRagRetriever(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val json: Json,
) : WireAppRagRetriever {
    override suspend fun retrieve(question: String): List<RagRetrievedChunk> {
        require(question.isNotBlank()) {
            "question must not be blank."
        }

        val response = httpClient.post("${baseUrl.trimEnd('/')}/retrieve") {
            contentType(ContentType.Application.Json)
            setBody(
                RagRetrieveRequest(
                    question = question,
                    topK = null,
                ),
            )
        }
        val rawResponseBody = response.bodyAsText()

        if (response.status.value !in 200..299) {
            throw IllegalStateException(
                "Wire App RAG request failed with HTTP ${response.status.value}: $rawResponseBody",
            )
        }

        return json.decodeFromString<RagRetrieveResponse>(rawResponseBody).retrievedChunks
    }
}
