package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.MessageRole
import com.aichallenge.day2.agent.domain.model.PromptRequestData

data class SessionPromptData(
    val messages: List<ConversationMessage>,
    val summarySystemMessage: String? = null,
)

data class BuildPromptRequest(
    val systemPrompt: String,
    val session: SessionPromptData,
    val userPrompt: String,
)

class BuildPromptUseCase {
    fun buildContext(
        systemPrompt: String,
        session: SessionPromptData,
    ): PromptRequestData {
        require(systemPrompt.isNotBlank()) {
            "systemPrompt must not be blank."
        }
        validateSessionMessages(session.messages)

        val contextSystemMessages = session.summarySystemMessage
            ?.trim()
            ?.takeIf { summary -> summary.isNotEmpty() }
            ?.let { summary -> listOf(summary) }
            ?: emptyList()

        return PromptRequestData(
            systemPrompt = systemPrompt,
            contextSystemMessages = contextSystemMessages,
            messages = session.messages.map { message -> message.copy() },
        )
    }

    fun execute(request: BuildPromptRequest): PromptRequestData {
        require(request.userPrompt.isNotBlank()) {
            "userPrompt must not be blank."
        }

        val context = buildContext(
            systemPrompt = request.systemPrompt,
            session = request.session,
        )
        return PromptRequestData(
            systemPrompt = context.systemPrompt,
            contextSystemMessages = context.contextSystemMessages,
            messages = context.messages + ConversationMessage.user(request.userPrompt),
        )
    }

    private fun validateSessionMessages(messages: List<ConversationMessage>) {
        messages.forEachIndexed { index, message ->
            require(message.content.isNotBlank()) {
                "session.messages[$index] content must not be blank."
            }
            require(message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT) {
                "session.messages[$index] must not use SYSTEM role."
            }
        }
    }
}
