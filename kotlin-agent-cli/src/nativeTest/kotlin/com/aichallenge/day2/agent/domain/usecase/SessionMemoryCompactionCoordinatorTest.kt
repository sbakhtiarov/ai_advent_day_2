package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.CompactedSessionSummary
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.RollingWindowCompactionStartPolicy
import com.aichallenge.day2.agent.domain.model.SessionCompactionSummaryMode
import com.aichallenge.day2.agent.domain.model.SessionCompactionStrategy
import com.aichallenge.day2.agent.domain.model.SessionMemory
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionMemoryCompactionCoordinatorTest {
    @Test
    fun generateSummaryModeCompactsAndStoresReturnedSummary() = runBlocking {
        val memory = SessionMemory(initialSystemPrompt = "system")
        memory.recordSuccessfulTurn("q1", "a1")
        memory.recordSuccessfulTurn("q2", "a2")
        memory.recordSuccessfulTurn("q3", "a3")
        val strategy = RecordingGenerateStrategy(summaryToReturn = "updated summary")
        val coordinator = SessionMemoryCompactionCoordinator(
            startPolicy = RollingWindowCompactionStartPolicy(
                threshold = 4,
                compactCount = 2,
                keepCount = 2,
            ),
            strategy = strategy,
        )

        val compacted = coordinator.compactIfNeeded(
            sessionMemory = memory,
            model = "gpt-4.1-mini",
        )

        assertTrue(compacted)
        assertEquals(1, strategy.calls)
        assertEquals(
            listOf(
                ConversationMessage.system("system"),
                ConversationMessage.user("q2"),
                ConversationMessage.assistant("a2"),
                ConversationMessage.user("q3"),
                ConversationMessage.assistant("a3"),
            ),
            memory.snapshot(),
        )
        assertEquals("rolling-summary-v1", memory.compactedSummarySnapshot()?.strategyId)
        assertEquals("updated summary", memory.compactedSummarySnapshot()?.content)
    }

    @Test
    fun clearSummaryModeCompactsAndRemovesExistingSummary() = runBlocking {
        val memory = SessionMemory(initialSystemPrompt = "system")
        memory.recordSuccessfulTurn("q1", "a1")
        memory.recordSuccessfulTurn("q2", "a2")
        memory.recordSuccessfulTurn("q3", "a3")
        memory.applyCompaction(
            compactedSummary = CompactedSessionSummary(
                strategyId = "rolling-summary-v1",
                content = "existing summary",
            ),
            compactedCount = 2,
        )
        memory.recordSuccessfulTurn("q4", "a4")
        val strategy = RecordingClearStrategy()
        val coordinator = SessionMemoryCompactionCoordinator(
            startPolicy = RollingWindowCompactionStartPolicy(
                threshold = 4,
                compactCount = 2,
                keepCount = 2,
            ),
            strategy = strategy,
        )

        val compacted = coordinator.compactIfNeeded(
            sessionMemory = memory,
            model = "gpt-4.1-mini",
        )

        assertTrue(compacted)
        assertEquals(0, strategy.calls)
        assertEquals(
            listOf(
                ConversationMessage.system("system"),
                ConversationMessage.user("q3"),
                ConversationMessage.assistant("a3"),
                ConversationMessage.user("q4"),
                ConversationMessage.assistant("a4"),
            ),
            memory.snapshot(),
        )
        assertEquals(null, memory.compactedSummarySnapshot())
    }
}

private class RecordingGenerateStrategy(
    private val summaryToReturn: String,
) : SessionCompactionStrategy {
    var calls: Int = 0
        private set

    override val id: String = "rolling-summary-v1"
    override val summaryMode: SessionCompactionSummaryMode = SessionCompactionSummaryMode.GENERATE

    override suspend fun compact(
        previousSummary: String?,
        messagesToCompact: List<ConversationMessage>,
        model: String,
    ): String {
        calls += 1
        return summaryToReturn
    }
}

private class RecordingClearStrategy : SessionCompactionStrategy {
    var calls: Int = 0
        private set

    override val id: String = "sliding-window-v1"
    override val summaryMode: SessionCompactionSummaryMode = SessionCompactionSummaryMode.CLEAR

    override suspend fun compact(
        previousSummary: String?,
        messagesToCompact: List<ConversationMessage>,
        model: String,
    ): String {
        calls += 1
        return ""
    }
}
