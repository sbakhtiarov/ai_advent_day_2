package com.aichallenge.day2.agent.domain.model

data class UserWorkflowOption(
    val fileName: String,
    val displayName: String,
)

data class UserWorkflowDefinition(
    val fileName: String,
    val name: String,
    val basePrompt: String? = null,
    val planning: String,
    val execution: String,
    val validation: String,
)
