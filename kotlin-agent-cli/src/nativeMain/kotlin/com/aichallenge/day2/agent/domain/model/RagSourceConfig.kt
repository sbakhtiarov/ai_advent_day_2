package com.aichallenge.day2.agent.domain.model

data class RagSourceConfig(
    val name: String,
    val enabled: Boolean,
    val type: RagSourceType,
    val databaseUrl: String,
)

enum class RagSourceType {
    POSTGRES,
}
