package com.aichallenge.day2.agent.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ResponsesApiRequest(
    val model: String,
    val input: List<RequestMessage>,
)

@Serializable
data class RequestMessage(
    val role: String,
    val content: List<RequestContent>,
)

@Serializable
data class RequestContent(
    val type: String,
    val text: String,
)

@Serializable
data class ResponsesApiEnvelope(
    @SerialName("output_text")
    val outputText: String? = null,
    val output: List<OutputItem> = emptyList(),
)

@Serializable
data class OutputItem(
    val content: List<OutputContent> = emptyList(),
)

@Serializable
data class OutputContent(
    val type: String? = null,
    val text: String? = null,
)
