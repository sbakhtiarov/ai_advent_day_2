package com.aichallenge.day2.agent.domain.model

class SessionMemory(
    initialSystemPrompt: String,
) {
    private val messages = mutableListOf<ConversationMessage>()
    private var fallbackSystemPrompt = initialSystemPrompt
    private var compactedSummary: CompactedSessionSummary? = null

    init {
        reset(initialSystemPrompt)
    }

    fun conversationFor(prompt: String): List<ConversationMessage> {
        return contextSnapshot() + ConversationMessage.user(prompt)
    }

    fun recordSuccessfulTurn(prompt: String, response: String) {
        messages += ConversationMessage.user(prompt)
        messages += ConversationMessage.assistant(response)
    }

    fun restore(
        persistedMessages: List<ConversationMessage>,
        persistedCompactedSummary: CompactedSessionSummary?,
    ): Boolean {
        if (!isValidSnapshot(persistedMessages) || !isValidCompactedSummary(persistedCompactedSummary)) {
            reset(fallbackSystemPrompt)
            return false
        }

        messages.clear()
        messages += persistedMessages
        fallbackSystemPrompt = persistedMessages.first().content
        compactedSummary = persistedCompactedSummary?.copy()
        return true
    }

    fun reset(systemPrompt: String) {
        fallbackSystemPrompt = systemPrompt
        messages.clear()
        messages += ConversationMessage.system(systemPrompt)
        compactedSummary = null
    }

    fun snapshot(): List<ConversationMessage> = messages.toList()

    fun contextSnapshot(): List<ConversationMessage> {
        val summaryText = compactedSummary?.content?.trim().orEmpty()
        if (summaryText.isEmpty()) {
            return snapshot()
        }

        return buildList {
            add(messages.first())
            add(
                ConversationMessage.system(
                    buildCompactedSummarySystemMessage(summaryText),
                ),
            )
            addAll(messages.drop(1))
        }
    }

    fun nonSystemMessagesSnapshot(): List<ConversationMessage> = messages.drop(1)

    fun compactedSummarySnapshot(): CompactedSessionSummary? = compactedSummary?.copy()

    fun applyCompaction(
        compactedSummary: CompactedSessionSummary,
        compactedCount: Int,
    ) {
        require(compactedCount >= 0) {
            "compactedCount must be >= 0."
        }
        require(compactedSummary.strategyId.isNotBlank()) {
            "compactedSummary strategyId must not be blank."
        }
        require(compactedSummary.content.isNotBlank()) {
            "compactedSummary content must not be blank."
        }

        val nonSystemMessages = nonSystemMessagesSnapshot()
        require(compactedCount <= nonSystemMessages.size) {
            "compactedCount exceeds non-system message count."
        }

        val remainingMessages = nonSystemMessages.drop(compactedCount)
        val nextMessages = buildList {
            add(messages.first())
            addAll(remainingMessages)
        }
        require(isValidSnapshot(nextMessages)) {
            "Compaction produced invalid message ordering."
        }

        messages.clear()
        messages += nextMessages
        this.compactedSummary = compactedSummary.copy()
    }

    private fun isValidCompactedSummary(summary: CompactedSessionSummary?): Boolean {
        if (summary == null) {
            return true
        }
        return summary.strategyId.isNotBlank() && summary.content.isNotBlank()
    }

    private fun buildCompactedSummarySystemMessage(summary: String): String = """
        Conversation summary from previous compacted turns:
        $summary
    """.trimIndent()

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
