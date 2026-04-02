package com.aichallenge.day2.agent.server

import com.aichallenge.day2.agent.review.ReviewRagChunk
import com.aichallenge.day2.agent.review.ReviewRagRetriever
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class JvmWireAppRagRetriever(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val json: Json,
) : ReviewRagRetriever {
    override suspend fun retrieve(question: String): List<ReviewRagChunk> {
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

        return json.decodeFromString<RagRetrieveResponse>(rawResponseBody).retrievedChunks.map { chunk ->
            ReviewRagChunk(
                chunkId = chunk.chunkId,
                sectionName = chunk.sectionName,
                headingPath = chunk.headingPath,
                sourcePath = chunk.sourcePath,
                score = chunk.score,
                content = chunk.content,
            )
        }
    }
}

@Serializable
private data class RagRetrieveRequest(
    val question: String,
    @SerialName("top_k")
    val topK: Int? = null,
)

@Serializable
private data class RagRetrieveResponse(
    @SerialName("retrieved_chunks")
    val retrievedChunks: List<RagRetrievedChunk>,
)

@Serializable
private data class RagRetrievedChunk(
    @SerialName("chunk_id")
    val chunkId: String,
    @SerialName("section_name")
    val sectionName: String,
    @SerialName("heading_path")
    val headingPath: String,
    @SerialName("source_path")
    val sourcePath: String,
    val score: Double,
    val content: String,
)
