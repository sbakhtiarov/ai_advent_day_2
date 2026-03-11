package com.aichallenge.day2.agent.domain.model

sealed interface McpTransportConfig {
    data class Http(
        val url: String,
    ) : McpTransportConfig

    data class Stdio(
        val command: String,
        val args: List<String> = emptyList(),
    ) : McpTransportConfig
}
