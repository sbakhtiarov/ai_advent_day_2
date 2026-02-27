package com.aichallenge.day2.agent.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionMemoryTest {
    @Test
    fun initializesWithSystemMessageOnly() {
        val memory = SessionMemory(initialSystemPrompt = "system one")

        assertEquals(
            listOf(ConversationMessage.system("system one")),
            memory.snapshot(),
        )
    }

    @Test
    fun conversationForAppendsUserPromptWithoutMutatingStoredHistory() {
        val memory = SessionMemory(initialSystemPrompt = "system one")
        memory.recordSuccessfulTurn(prompt = "hello", response = "hi")

        val conversation = memory.conversationFor("what now?")

        assertEquals(
            listOf(
                ConversationMessage.system("system one"),
                ConversationMessage.user("hello"),
                ConversationMessage.assistant("hi"),
                ConversationMessage.user("what now?"),
            ),
            conversation,
        )
        assertEquals(
            listOf(
                ConversationMessage.system("system one"),
                ConversationMessage.user("hello"),
                ConversationMessage.assistant("hi"),
            ),
            memory.snapshot(),
        )
    }

    @Test
    fun conversationForIncludesCompactedSummaryWhenPresent() {
        val memory = SessionMemory(initialSystemPrompt = "system one")
        memory.recordSuccessfulTurn(prompt = "q1", response = "a1")
        memory.recordSuccessfulTurn(prompt = "q2", response = "a2")
        memory.applyCompaction(
            compactedSummary = CompactedSessionSummary(
                strategyId = "rolling-summary-v1",
                content = "summary text",
            ),
            compactedCount = 2,
        )

        val conversation = memory.conversationFor("what now?")

        assertEquals(
            listOf(
                ConversationMessage.system("system one"),
                ConversationMessage.system(
                    """
                    Conversation summary from previous compacted turns:
                    summary text
                    """.trimIndent(),
                ),
                ConversationMessage.user("q2"),
                ConversationMessage.assistant("a2"),
                ConversationMessage.user("what now?"),
            ),
            conversation,
        )
    }

    @Test
    fun recordSuccessfulTurnStoresUserThenAssistant() {
        val memory = SessionMemory(initialSystemPrompt = "system one")

        memory.recordSuccessfulTurn(prompt = "question", response = "answer")

        assertEquals(
            listOf(
                ConversationMessage.system("system one"),
                ConversationMessage.user("question"),
                ConversationMessage.assistant("answer"),
            ),
            memory.snapshot(),
        )
    }

    @Test
    fun applyCompactionRemovesFirstMessagesAndKeepsTail() {
        val memory = SessionMemory(initialSystemPrompt = "system one")
        memory.recordSuccessfulTurn(prompt = "q1", response = "a1")
        memory.recordSuccessfulTurn(prompt = "q2", response = "a2")
        memory.recordSuccessfulTurn(prompt = "q3", response = "a3")

        memory.applyCompaction(
            compactedSummary = CompactedSessionSummary(
                strategyId = "rolling-summary-v1",
                content = "updated summary",
            ),
            compactedCount = 4,
        )

        assertEquals(
            listOf(
                ConversationMessage.system("system one"),
                ConversationMessage.user("q3"),
                ConversationMessage.assistant("a3"),
            ),
            memory.snapshot(),
        )
        assertEquals(
            CompactedSessionSummary(
                strategyId = "rolling-summary-v1",
                content = "updated summary",
            ),
            memory.compactedSummarySnapshot(),
        )
    }

    @Test
    fun resetClearsPriorTurnsAndKeepsOnlyNewSystemMessage() {
        val memory = SessionMemory(initialSystemPrompt = "system one")
        memory.recordSuccessfulTurn(prompt = "q1", response = "a1")
        memory.applyCompaction(
            compactedSummary = CompactedSessionSummary(
                strategyId = "rolling-summary-v1",
                content = "summary",
            ),
            compactedCount = 2,
        )

        memory.reset("system two")

        assertEquals(
            listOf(ConversationMessage.system("system two")),
            memory.snapshot(),
        )
        assertEquals(null, memory.compactedSummarySnapshot())
    }

    @Test
    fun restoreUsesPersistedMessagesAndSummaryWhenSnapshotIsValid() {
        val memory = SessionMemory(initialSystemPrompt = "initial system")
        val persistedMessages = listOf(
            ConversationMessage.system("persisted system"),
            ConversationMessage.user("question"),
            ConversationMessage.assistant("answer"),
        )
        val persistedSummary = CompactedSessionSummary(
            strategyId = "rolling-summary-v1",
            content = "persisted summary",
        )

        val restored = memory.restore(
            persistedMessages = persistedMessages,
            persistedCompactedSummary = persistedSummary,
        )

        assertEquals(true, restored)
        assertEquals(persistedMessages, memory.snapshot())
        assertEquals(persistedSummary, memory.compactedSummarySnapshot())
    }

    @Test
    fun restoreFallsBackToSystemOnlyWhenSummaryIsInvalid() {
        val memory = SessionMemory(initialSystemPrompt = "system one")
        val validMessages = listOf(
            ConversationMessage.system("persisted system"),
            ConversationMessage.user("question"),
            ConversationMessage.assistant("answer"),
        )
        val invalidSummary = CompactedSessionSummary(
            strategyId = "",
            content = "summary",
        )

        val restored = memory.restore(
            persistedMessages = validMessages,
            persistedCompactedSummary = invalidSummary,
        )

        assertEquals(false, restored)
        assertEquals(
            listOf(ConversationMessage.system("system one")),
            memory.snapshot(),
        )
        assertEquals(null, memory.compactedSummarySnapshot())
    }
}
