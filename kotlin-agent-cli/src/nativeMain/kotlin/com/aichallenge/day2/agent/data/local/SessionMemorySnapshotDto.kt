package com.aichallenge.day2.agent.data.local

import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.MessageRole
import kotlinx.serialization.Serializable

@Serializable
data class SessionMemorySnapshotDto(
    val version: Int,
    val messages: List<PersistedConversationMessageDto>,
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
