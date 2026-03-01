package com.aichallenge.day2.agent.domain.model

data class SessionMemoryState(
    val messages: List<ConversationMessage>,
    val compactedSummary: CompactedSessionSummary? = null,
    val usage: MemoryUsageSnapshot? = null,
    val activeCompactionModeId: String? = null,
    val branchingState: BranchingMemoryState? = null,
)

data class CompactedSessionSummary(
    val strategyId: String,
    val content: String,
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

data class BranchingMemoryState(
    val activeTopicKey: String,
    val activeSubtopicKey: String,
    val topics: List<TopicBranchState>,
)

data class TopicBranchState(
    val key: String,
    val displayName: String,
    val rollingSummary: String = "",
    val subtopics: List<SubtopicBranchState>,
)

data class SubtopicBranchState(
    val key: String,
    val displayName: String,
    val messages: List<ConversationMessage>,
)
