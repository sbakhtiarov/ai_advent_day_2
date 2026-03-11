package com.aichallenge.day2.agent.domain.model

data class LlmToolCapabilities(
    val publicMcpServers: List<PublicMcpServerCapability> = emptyList(),
    val privateTools: List<PrivateToolBinding> = emptyList(),
) {
    fun isEmpty(): Boolean = publicMcpServers.isEmpty() && privateTools.isEmpty()
}

data class PublicMcpServerCapability(
    val serverLabel: String,
    val serverUrl: String,
)
