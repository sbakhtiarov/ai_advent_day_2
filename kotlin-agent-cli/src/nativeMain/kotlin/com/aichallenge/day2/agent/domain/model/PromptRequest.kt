package com.aichallenge.day2.agent.domain.model

data class PromptRequestData(
    val systemPrompt: String,
    val contextSystemMessages: List<String> = emptyList(),
    val messages: List<ConversationMessage> = emptyList(),
) {
    init {
        require(systemPrompt.isNotBlank()) {
            "systemPrompt must not be blank."
        }
        contextSystemMessages.forEachIndexed { index, message ->
            require(message.isNotBlank()) {
                "contextSystemMessages[$index] must not be blank."
            }
        }
        messages.forEachIndexed { index, message ->
            require(message.content.isNotBlank()) {
                "messages[$index] content must not be blank."
            }
            require(message.role != MessageRole.SYSTEM) {
                "messages[$index] must not use SYSTEM role."
            }
        }
    }

    fun toConversation(): List<ConversationMessage> = buildList {
        add(ConversationMessage.system(systemPrompt))
        contextSystemMessages.forEach { contextMessage ->
            add(ConversationMessage.system(contextMessage))
        }
        addAll(messages.map { message -> message.copy() })
    }
}
