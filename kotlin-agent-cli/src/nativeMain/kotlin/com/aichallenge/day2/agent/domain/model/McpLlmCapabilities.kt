package com.aichallenge.day2.agent.domain.model

import kotlinx.serialization.json.JsonObject

data class McpLlmCapabilities(
    val publicServers: List<McpPublicServerCapability> = emptyList(),
    val privateTools: List<McpPrivateToolBinding> = emptyList(),
) {
    fun isEmpty(): Boolean = publicServers.isEmpty() && privateTools.isEmpty()
}

data class McpPublicServerCapability(
    val serverLabel: String,
    val serverUrl: String,
)

data class McpPrivateToolBinding(
    val modelToolName: String,
    val server: McpServerConfig,
    val sourceToolName: String,
    val description: String? = null,
    val parametersSchema: JsonObject,
)
