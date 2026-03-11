package com.aichallenge.day2.agent.domain.model

import kotlinx.serialization.json.JsonObject

data class PrivateToolBinding(
    val modelToolName: String,
    val target: PrivateToolTarget,
    val description: String? = null,
    val parametersSchema: JsonObject,
)

sealed interface PrivateToolTarget {
    data class Mcp(
        val server: McpServerConfig,
        val sourceToolName: String,
    ) : PrivateToolTarget

    data class BuiltIn(
        val toolId: String,
    ) : PrivateToolTarget
}
