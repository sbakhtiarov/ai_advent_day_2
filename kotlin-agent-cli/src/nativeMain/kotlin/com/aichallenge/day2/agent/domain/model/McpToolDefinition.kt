package com.aichallenge.day2.agent.domain.model

data class McpToolDefinition(
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val inputSchemaJson: String,
    val outputSchemaJson: String? = null,
)
