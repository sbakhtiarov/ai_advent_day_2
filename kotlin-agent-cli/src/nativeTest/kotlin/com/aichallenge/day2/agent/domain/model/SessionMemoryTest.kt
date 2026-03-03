package com.aichallenge.day2.agent.domain.model

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionMemoryTest {
    @Test
    fun initializesWithEmptySnapshot() {
        val memory = SessionMemory()

        assertEquals(emptyList(), memory.snapshot())
    }

    @Test
    fun recordSuccessfulTurnStoresUserThenAssistant() {
        val memory = SessionMemory()

        memory.recordSuccessfulTurn(prompt = "question", response = "answer")

        assertEquals(
            listOf(
                ConversationMessage.user("question"),
                ConversationMessage.assistant("answer"),
            ),
            memory.snapshot(),
        )
    }

    @Test
    fun promptDataIncludesCompactedSummaryWhenPresent() {
        val memory = SessionMemory()
        memory.recordSuccessfulTurn(prompt = "q1", response = "a1")
        memory.recordSuccessfulTurn(prompt = "q2", response = "a2")
        memory.applyCompaction(
            compactedSummary = CompactedSessionSummary(
                strategyId = "rolling-summary-v1",
                content = "summary text",
            ),
            compactedCount = 2,
        )

        val promptData = memory.promptDataSnapshot()

        assertEquals(
            listOf(
                ConversationMessage.user("q2"),
                ConversationMessage.assistant("a2"),
            ),
            promptData.messages,
        )
        assertContains(promptData.summarySystemMessage.orEmpty(), "summary text")
    }

    @Test
    fun applyCompactionRemovesFirstMessagesAndKeepsTail() {
        val memory = SessionMemory()
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
    fun applyCompactionWithNullSummaryClearsPreviousSummary() {
        val memory = SessionMemory()
        memory.recordSuccessfulTurn(prompt = "q1", response = "a1")
        memory.recordSuccessfulTurn(prompt = "q2", response = "a2")
        memory.applyCompaction(
            compactedSummary = CompactedSessionSummary(
                strategyId = "rolling-summary-v1",
                content = "summary",
            ),
            compactedCount = 2,
        )

        memory.applyCompaction(
            compactedSummary = null,
            compactedCount = 2,
        )

        assertEquals(emptyList(), memory.snapshot())
        assertNull(memory.compactedSummarySnapshot())
    }

    @Test
    fun resetClearsTurnsAndSummary() {
        val memory = SessionMemory()
        memory.recordSuccessfulTurn(prompt = "q1", response = "a1")
        memory.applyCompaction(
            compactedSummary = CompactedSessionSummary(
                strategyId = "rolling-summary-v1",
                content = "summary",
            ),
            compactedCount = 2,
        )

        memory.reset()

        assertEquals(emptyList(), memory.snapshot())
        assertNull(memory.compactedSummarySnapshot())
    }

    @Test
    fun clearCompactedSummaryRemovesSummaryWithoutChangingMessages() {
        val memory = SessionMemory()
        memory.recordSuccessfulTurn(prompt = "q1", response = "a1")
        memory.applyCompaction(
            compactedSummary = CompactedSessionSummary(
                strategyId = "rolling-summary-v1",
                content = "summary",
            ),
            compactedCount = 2,
        )
        val snapshotBeforeClear = memory.snapshot()

        memory.clearCompactedSummary()

        assertEquals(snapshotBeforeClear, memory.snapshot())
        assertNull(memory.compactedSummarySnapshot())
    }

    @Test
    fun restoreUsesPersistedMessagesAndSummaryWhenSnapshotIsValid() {
        val memory = SessionMemory()
        val persistedMessages = listOf(
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
    fun restoreFallsBackToEmptyWhenSummaryIsInvalid() {
        val memory = SessionMemory()
        memory.recordSuccessfulTurn(prompt = "q1", response = "a1")
        val validMessages = listOf(
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
        assertEquals(emptyList(), memory.snapshot())
        assertNull(memory.compactedSummarySnapshot())
    }

    @Test
    fun restoreRejectsLegacySystemFirstSnapshots() {
        val memory = SessionMemory()
        memory.recordSuccessfulTurn(prompt = "q1", response = "a1")
        val legacyMessages = listOf(
            ConversationMessage.system("persisted system"),
            ConversationMessage.user("question"),
            ConversationMessage.assistant("answer"),
        )

        val restored = memory.restore(
            persistedMessages = legacyMessages,
            persistedCompactedSummary = null,
        )

        assertEquals(false, restored)
        assertEquals(emptyList(), memory.snapshot())
    }
}
