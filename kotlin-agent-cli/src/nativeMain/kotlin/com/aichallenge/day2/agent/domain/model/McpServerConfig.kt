package com.aichallenge.day2.agent.domain.model

data class McpServerConfig(
    val name: String,
    val enabled: Boolean,
    val transport: McpTransportConfig,
)
