package com.aichallenge.day2.agent.domain.model

data class McpToolCatalogState(
    val server: McpServerConfig,
    val status: McpToolCatalogStatus,
    val tools: List<McpToolDefinition> = emptyList(),
    val failureMessage: String? = null,
)
