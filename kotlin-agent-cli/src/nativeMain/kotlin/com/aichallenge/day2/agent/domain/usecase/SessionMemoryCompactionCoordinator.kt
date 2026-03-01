package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.CompactedSessionSummary
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.SessionCompactionStartPolicy
import com.aichallenge.day2.agent.domain.model.SessionCompactionSummaryMode
import com.aichallenge.day2.agent.domain.model.SessionCompactionStrategy
import com.aichallenge.day2.agent.domain.model.SessionMemory

class SessionMemoryCompactionCoordinator(
    private val startPolicy: SessionCompactionStartPolicy,
    private val strategy: SessionCompactionStrategy,
) {
    suspend fun compactIfNeeded(
        sessionMemory: SessionMemory,
        model: String,
    ): Boolean {
        val nonSystemMessages = sessionMemory.nonSystemMessagesSnapshot()
        val candidate = startPolicy.select(nonSystemMessages) ?: return false

        val compactedSummary = when (strategy.summaryMode) {
            SessionCompactionSummaryMode.GENERATE -> {
                val previousSummary = sessionMemory.compactedSummarySnapshot()?.content
                val nextSummary = runCatching {
                    strategy.compact(
                        previousSummary = previousSummary,
                        messagesToCompact = candidate.messagesToCompact,
                        model = model,
                    ).trim()
                }.getOrNull()

                if (nextSummary.isNullOrBlank()) {
                    return false
                }

                CompactedSessionSummary(
                    strategyId = strategy.id,
                    content = nextSummary,
                )
            }

            SessionCompactionSummaryMode.CLEAR -> null
        }

        sessionMemory.applyCompaction(
            compactedSummary = compactedSummary,
            compactedCount = candidate.compactedCount,
        )
        return true
    }

    companion object {
        fun disabled(): SessionMemoryCompactionCoordinator = SessionMemoryCompactionCoordinator(
            startPolicy = NeverCompactionStartPolicy,
            strategy = DisabledCompactionStrategy,
        )
    }
}

private object NeverCompactionStartPolicy : SessionCompactionStartPolicy {
    override fun select(messages: List<ConversationMessage>) = null
}

private object DisabledCompactionStrategy : SessionCompactionStrategy {
    override val id: String = "disabled"
    override val summaryMode: SessionCompactionSummaryMode = SessionCompactionSummaryMode.CLEAR

    override suspend fun compact(
        previousSummary: String?,
        messagesToCompact: List<ConversationMessage>,
        model: String,
    ): String = ""
}
