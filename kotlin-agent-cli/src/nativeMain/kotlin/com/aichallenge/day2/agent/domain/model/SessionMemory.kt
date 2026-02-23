package com.aichallenge.day2.agent.domain.model

class SessionMemory(
    initialSystemPrompt: String,
) {
    private val messages = mutableListOf<ConversationMessage>()

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

    fun reset(systemPrompt: String) {
        messages.clear()
        messages += ConversationMessage.system(systemPrompt)
    }

    fun snapshot(): List<ConversationMessage> = messages.toList()
}
