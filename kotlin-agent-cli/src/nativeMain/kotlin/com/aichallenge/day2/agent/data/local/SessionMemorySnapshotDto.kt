package com.aichallenge.day2.agent.data.local

import com.aichallenge.day2.agent.domain.model.BranchingMemoryState
import com.aichallenge.day2.agent.domain.model.SubtopicBranchState
import com.aichallenge.day2.agent.domain.model.TopicBranchState
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.CompactedSessionSummary
import com.aichallenge.day2.agent.domain.model.MemoryEstimateSource
import com.aichallenge.day2.agent.domain.model.MemoryUsageSnapshot
import com.aichallenge.day2.agent.domain.model.MessageRole
import com.aichallenge.day2.agent.domain.model.SessionMemoryState
import kotlinx.serialization.Serializable

@Serializable
data class SessionMemorySnapshotDto(
    val version: Int,
    val messages: List<PersistedConversationMessageDto>,
    val compactedSummary: PersistedCompactedSessionSummaryDto? = null,
    val memoryUsage: PersistedMemoryUsageSnapshotDto? = null,
    val activeCompactionModeId: String? = null,
    val branchingState: PersistedBranchingMemoryStateDto? = null,
)

@Serializable
data class PersistedConversationMessageDto(
    val role: PersistedMessageRole,
    val content: String,
)

@Serializable
enum class PersistedMessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

@Serializable
data class PersistedCompactedSessionSummaryDto(
    val strategyId: String,
    val content: String,
)

@Serializable
data class PersistedMemoryUsageSnapshotDto(
    val estimatedTokens: Int,
    val source: PersistedMemoryEstimateSource,
    val messageCount: Int,
)

@Serializable
data class PersistedBranchingMemoryStateDto(
    val activeTopicKey: String,
    val activeSubtopicKey: String,
    val topics: List<PersistedTopicBranchStateDto>,
)

@Serializable
data class PersistedTopicBranchStateDto(
    val key: String,
    val displayName: String,
    val rollingSummary: String = "",
    val subtopics: List<PersistedSubtopicBranchStateDto>,
)

@Serializable
data class PersistedSubtopicBranchStateDto(
    val key: String,
    val displayName: String,
    val messages: List<PersistedConversationMessageDto>,
)

@Serializable
enum class PersistedMemoryEstimateSource {
    HYBRID,
    HEURISTIC,
}

@Serializable
data class SessionSummarySnapshotDto(
    val version: Int,
    val compactedSummary: PersistedCompactedSessionSummaryDto,
)

fun SessionMemoryState.toPersistedDto(version: Int): SessionMemorySnapshotDto = SessionMemorySnapshotDto(
    version = version,
    messages = messages.map { it.toPersistedDto() },
    compactedSummary = compactedSummary?.toPersistedDto(),
    memoryUsage = usage?.toPersistedDto(),
    activeCompactionModeId = activeCompactionModeId,
    branchingState = branchingState?.toPersistedDto(),
)

fun SessionMemorySnapshotDto.toDomainModel(): SessionMemoryState = SessionMemoryState(
    messages = messages.map { it.toDomainModel() },
    compactedSummary = compactedSummary?.toDomainModel(),
    usage = memoryUsage?.toDomainModel(),
    activeCompactionModeId = activeCompactionModeId,
    branchingState = branchingState?.toDomainModel(),
)

fun ConversationMessage.toPersistedDto(): PersistedConversationMessageDto = PersistedConversationMessageDto(
    role = role.toPersistedRole(),
    content = content,
)

fun PersistedConversationMessageDto.toDomainModel(): ConversationMessage = ConversationMessage(
    role = role.toDomainRole(),
    content = content,
)

private fun MessageRole.toPersistedRole(): PersistedMessageRole = when (this) {
    MessageRole.SYSTEM -> PersistedMessageRole.SYSTEM
    MessageRole.USER -> PersistedMessageRole.USER
    MessageRole.ASSISTANT -> PersistedMessageRole.ASSISTANT
}

private fun PersistedMessageRole.toDomainRole(): MessageRole = when (this) {
    PersistedMessageRole.SYSTEM -> MessageRole.SYSTEM
    PersistedMessageRole.USER -> MessageRole.USER
    PersistedMessageRole.ASSISTANT -> MessageRole.ASSISTANT
}

private fun MemoryUsageSnapshot.toPersistedDto(): PersistedMemoryUsageSnapshotDto = PersistedMemoryUsageSnapshotDto(
    estimatedTokens = estimatedTokens,
    source = source.toPersistedSource(),
    messageCount = messageCount,
)

private fun PersistedMemoryUsageSnapshotDto.toDomainModel(): MemoryUsageSnapshot = MemoryUsageSnapshot(
    estimatedTokens = estimatedTokens,
    source = source.toDomainSource(),
    messageCount = messageCount,
)

fun CompactedSessionSummary.toPersistedDto(): PersistedCompactedSessionSummaryDto = PersistedCompactedSessionSummaryDto(
    strategyId = strategyId,
    content = content,
)

fun PersistedCompactedSessionSummaryDto.toDomainModel(): CompactedSessionSummary = CompactedSessionSummary(
    strategyId = strategyId,
    content = content,
)

private fun MemoryEstimateSource.toPersistedSource(): PersistedMemoryEstimateSource = when (this) {
    MemoryEstimateSource.HYBRID -> PersistedMemoryEstimateSource.HYBRID
    MemoryEstimateSource.HEURISTIC -> PersistedMemoryEstimateSource.HEURISTIC
}

private fun PersistedMemoryEstimateSource.toDomainSource(): MemoryEstimateSource = when (this) {
    PersistedMemoryEstimateSource.HYBRID -> MemoryEstimateSource.HYBRID
    PersistedMemoryEstimateSource.HEURISTIC -> MemoryEstimateSource.HEURISTIC
}

private fun BranchingMemoryState.toPersistedDto(): PersistedBranchingMemoryStateDto = PersistedBranchingMemoryStateDto(
    activeTopicKey = activeTopicKey,
    activeSubtopicKey = activeSubtopicKey,
    topics = topics.map { topic -> topic.toPersistedDto() },
)

private fun TopicBranchState.toPersistedDto(): PersistedTopicBranchStateDto = PersistedTopicBranchStateDto(
    key = key,
    displayName = displayName,
    rollingSummary = rollingSummary,
    subtopics = subtopics.map { subtopic -> subtopic.toPersistedDto() },
)

private fun SubtopicBranchState.toPersistedDto(): PersistedSubtopicBranchStateDto = PersistedSubtopicBranchStateDto(
    key = key,
    displayName = displayName,
    messages = messages.map { message -> message.toPersistedDto() },
)

private fun PersistedBranchingMemoryStateDto.toDomainModel(): BranchingMemoryState = BranchingMemoryState(
    activeTopicKey = activeTopicKey,
    activeSubtopicKey = activeSubtopicKey,
    topics = topics.map { topic -> topic.toDomainModel() },
)

private fun PersistedTopicBranchStateDto.toDomainModel(): TopicBranchState = TopicBranchState(
    key = key,
    displayName = displayName,
    rollingSummary = rollingSummary,
    subtopics = subtopics.map { subtopic -> subtopic.toDomainModel() },
)

private fun PersistedSubtopicBranchStateDto.toDomainModel(): SubtopicBranchState = SubtopicBranchState(
    key = key,
    displayName = displayName,
    messages = messages.map { message -> message.toDomainModel() },
)
