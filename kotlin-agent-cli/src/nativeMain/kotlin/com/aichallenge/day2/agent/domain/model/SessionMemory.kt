package com.aichallenge.day2.agent.domain.model

import com.aichallenge.day2.agent.domain.usecase.SessionPromptData

class SessionMemory {
    private val messages = mutableListOf<ConversationMessage>()
    private var compactedSummary: CompactedSessionSummary? = null

    fun recordSuccessfulTurn(prompt: String, response: String) {
        messages += ConversationMessage.user(prompt)
        messages += ConversationMessage.assistant(response)
    }

    fun restore(
        persistedMessages: List<ConversationMessage>,
        persistedCompactedSummary: CompactedSessionSummary?,
    ): Boolean {
        if (!isValidSnapshot(persistedMessages) || !isValidCompactedSummary(persistedCompactedSummary)) {
            reset()
            return false
        }

        messages.clear()
        messages += persistedMessages
        compactedSummary = persistedCompactedSummary?.copy()
        return true
    }

    fun reset() {
        messages.clear()
        compactedSummary = null
    }

    fun snapshot(): List<ConversationMessage> = messages.toList()

    fun promptDataSnapshot(): SessionPromptData {
        val summaryText = compactedSummary?.content?.trim().orEmpty()
        val summarySystemMessage = if (summaryText.isEmpty()) {
            null
        } else {
            buildCompactedSummarySystemMessage(summaryText)
        }

        return SessionPromptData(
            messages = snapshot(),
            summarySystemMessage = summarySystemMessage,
        )
    }

    fun nonSystemMessagesSnapshot(): List<ConversationMessage> = snapshot()

    fun compactedSummarySnapshot(): CompactedSessionSummary? = compactedSummary?.copy()

    fun clearCompactedSummary() {
        compactedSummary = null
    }

    fun applyCompaction(
        compactedSummary: CompactedSessionSummary?,
        compactedCount: Int,
    ) {
        require(compactedCount >= 0) {
            "compactedCount must be >= 0."
        }
        if (compactedSummary != null) {
            require(compactedSummary.strategyId.isNotBlank()) {
                "compactedSummary strategyId must not be blank."
            }
            require(compactedSummary.content.isNotBlank()) {
                "compactedSummary content must not be blank."
            }
        }

        val nonSystemMessages = nonSystemMessagesSnapshot()
        require(compactedCount <= nonSystemMessages.size) {
            "compactedCount exceeds non-system message count."
        }

        val remainingMessages = nonSystemMessages.drop(compactedCount)
        require(isValidSnapshot(remainingMessages)) {
            "Compaction produced invalid message ordering."
        }

        messages.clear()
        messages += remainingMessages
        this.compactedSummary = compactedSummary?.copy()
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
        for (index in snapshot.indices) {
            val message = snapshot[index]
            if (message.content.isBlank()) {
                return false
            }

            val expectedRole = if (index % 2 == 0) {
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
