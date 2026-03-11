package com.aichallenge.day2.agent.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Serializable
data class ResponsesApiRequest(
    val model: String,
    val instructions: String? = null,
    val temperature: Double? = null,
    val input: List<ResponseInputItem>,
    val tools: List<ResponseTool>? = null,
    @SerialName("parallel_tool_calls")
    val parallelToolCalls: Boolean? = null,
    @SerialName("previous_response_id")
    val previousResponseId: String? = null,
)

@Serializable
data class ResponseInputItem(
    val role: String? = null,
    val content: List<RequestContent>? = null,
    val type: String? = null,
    @SerialName("call_id")
    val callId: String? = null,
    val output: String? = null,
)

@Serializable
data class RequestContent(
    val type: String,
    val text: String,
)

@Serializable
data class ResponsesApiEnvelope(
    val id: String? = null,
    @SerialName("output_text")
    val outputText: String? = null,
    val output: List<OutputItem> = emptyList(),
    val usage: UsageDetails? = null,
)

@Serializable
data class UsageDetails(
    @SerialName("total_tokens")
    val totalTokens: Int? = null,
    @SerialName("input_tokens")
    val inputTokens: Int? = null,
    @SerialName("output_tokens")
    val outputTokens: Int? = null,
)

@Serializable
data class OutputItem(
    val id: String? = null,
    val type: String? = null,
    val role: String? = null,
    val content: List<OutputContent> = emptyList(),
    @SerialName("call_id")
    val callId: String? = null,
    val name: String? = null,
    val arguments: String? = null,
    val status: String? = null,
)

@Serializable
data class OutputContent(
    val type: String? = null,
    val text: String? = null,
)

@Serializable
data class ResponseTool(
    val type: String,
    val name: String? = null,
    val description: String? = null,
    val parameters: JsonObject? = null,
    val strict: Boolean? = null,
    @SerialName("server_label")
    val serverLabel: String? = null,
    @SerialName("server_url")
    val serverUrl: String? = null,
    @SerialName("require_approval")
    val requireApproval: String? = null,
    @SerialName("allowed_tools")
    val allowedTools: List<String>? = null,
)

@Serializable
data class ResponsesApiFunctionCallOutput(
    val type: String = "function_call_output",
    @SerialName("call_id")
    val callId: String,
    val output: String,
)

data class PendingFunctionCall(
    val callId: String,
    val name: String,
    val arguments: String,
)
