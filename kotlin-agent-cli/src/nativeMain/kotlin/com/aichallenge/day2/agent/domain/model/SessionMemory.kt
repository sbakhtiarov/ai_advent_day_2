package com.aichallenge.day2.agent.domain.model

class SessionMemory(
    initialSystemPrompt: String,
) {
    private val messages = mutableListOf<ConversationMessage>()
    private var fallbackSystemPrompt = initialSystemPrompt

    init {
        reset(initialSystemPrompt)
    }

    fun conversationFor(prompt: String): List<ConversationMessage> {
        return snapshot() + ConversationMessage.user(prompt)
    }

    fun recordSuccessfulTurn(prompt: String, response: String) {
        messages += ConversationMessage.user(prompt)
        messages += ConversationMessage.assistant(response)
    }

    fun restore(persistedMessages: List<ConversationMessage>): Boolean {
        if (!isValidSnapshot(persistedMessages)) {
            reset(fallbackSystemPrompt)
            return false
        }

        messages.clear()
        messages += persistedMessages
        fallbackSystemPrompt = persistedMessages.first().content
        return true
    }

    fun reset(systemPrompt: String) {
        fallbackSystemPrompt = systemPrompt
        messages.clear()
        messages += ConversationMessage.system(systemPrompt)
    }

    fun snapshot(): List<ConversationMessage> = messages.toList()

    private fun isValidSnapshot(snapshot: List<ConversationMessage>): Boolean {
        if (snapshot.isEmpty()) return false
        val firstMessage = snapshot.first()
        if (firstMessage.role != MessageRole.SYSTEM || firstMessage.content.isBlank()) return false

        for (index in 1 until snapshot.size) {
            val message = snapshot[index]
            if (message.content.isBlank()) return false
            val expectedRole = if (index % 2 == 1) {
                MessageRole.USER
            } else {
                MessageRole.ASSISTANT
            }

            if (message.role != expectedRole) {
                return false
            }
        }

        return true
    }
}
