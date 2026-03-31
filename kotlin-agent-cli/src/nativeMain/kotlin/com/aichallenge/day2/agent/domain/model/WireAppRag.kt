package com.aichallenge.day2.agent.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RagRetrieveRequest(
    val question: String,
    @SerialName("top_k")
    val topK: Int? = null,
)

@Serializable
data class RagRetrievedChunk(
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

@Serializable
data class RagRetrieveResponse(
    @SerialName("retrieved_chunks")
    val retrievedChunks: List<RagRetrievedChunk>,
)
