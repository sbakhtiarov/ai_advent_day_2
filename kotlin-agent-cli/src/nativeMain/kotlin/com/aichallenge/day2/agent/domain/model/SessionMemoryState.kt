package com.aichallenge.day2.agent.domain.model

data class SessionMemoryState(
    val messages: List<ConversationMessage>,
    val usage: MemoryUsageSnapshot? = null,
)

data class MemoryUsageSnapshot(
    val estimatedTokens: Int,
    val source: MemoryEstimateSource,
    val messageCount: Int,
)

enum class MemoryEstimateSource {
    HYBRID,
    HEURISTIC,
}
