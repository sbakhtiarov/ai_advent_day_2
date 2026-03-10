package com.aichallenge.day2.agent.domain.model

data class McpServerRuntimeState(
    val server: McpServerConfig,
    val status: McpRuntimeStatus,
    val failureMessage: String? = null,
    val toolCatalogStatus: McpToolCatalogStatus = McpToolCatalogStatus.NOT_REQUESTED,
    val tools: List<McpToolDefinition> = emptyList(),
    val toolCatalogFailureMessage: String? = null,
)
