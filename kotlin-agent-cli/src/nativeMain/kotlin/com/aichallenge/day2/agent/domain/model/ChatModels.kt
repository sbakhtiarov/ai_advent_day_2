package com.aichallenge.day2.agent.domain.model

enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

data class ConversationMessage(
    val role: MessageRole,
    val content: String,
) {
    companion object {
        fun system(content: String): ConversationMessage = ConversationMessage(
            role = MessageRole.SYSTEM,
            content = content,
        )

        fun user(content: String): ConversationMessage = ConversationMessage(
            role = MessageRole.USER,
            content = content,
        )

        fun assistant(content: String): ConversationMessage = ConversationMessage(
            role = MessageRole.ASSISTANT,
            content = content,
        )
    }
}

data class TokenUsage(
    val totalTokens: Int,
    val inputTokens: Int,
    val outputTokens: Int,
)

data class AgentResponse(
    val content: String,
    val usage: TokenUsage? = null,
)
