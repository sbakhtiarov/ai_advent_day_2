package com.aichallenge.day2.agent.domain.model

data class SessionMemoryState(
    val messages: List<ConversationMessage>,
    val compactedSummary: CompactedSessionSummary? = null,
    val usage: MemoryUsageSnapshot? = null,
    val activeCompactionModeId: String? = null,
    val branchingState: BranchingMemoryState? = null,
    val workflowModeEnabled: Boolean = false,
    val workflowRuntimeState: WorkflowRuntimeState? = null,
)

enum class WorkflowStep {
    USER_INPUT,
    PLANNING_APPROVAL,
    EXECUTION_APPROVAL,
}

data class WorkflowRuntimeState(
    val step: WorkflowStep = WorkflowStep.USER_INPUT,
    val originalUserPrompt: String = "",
    val planningFeedback: List<String> = emptyList(),
    val executionFeedback: List<String> = emptyList(),
    val latestPlanningOutput: String? = null,
    val approvedPlan: String? = null,
    val latestExecutionOutput: String? = null,
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
