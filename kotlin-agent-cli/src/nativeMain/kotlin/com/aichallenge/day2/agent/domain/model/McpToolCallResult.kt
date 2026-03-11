package com.aichallenge.day2.agent.domain.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

data class McpToolCallResult(
    val isError: Boolean,
    val content: JsonArray,
    val structuredContent: JsonObject? = null,
    val meta: JsonObject? = null,
)
