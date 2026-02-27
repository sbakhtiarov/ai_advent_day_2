package com.aichallenge.day2.agent.data.local

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
)

fun SessionMemorySnapshotDto.toDomainModel(): SessionMemoryState = SessionMemoryState(
    messages = messages.map { it.toDomainModel() },
    compactedSummary = compactedSummary?.toDomainModel(),
    usage = memoryUsage?.toDomainModel(),
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
