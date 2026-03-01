package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.SessionCompactionSummaryMode
import com.aichallenge.day2.agent.domain.model.SessionCompactionStrategy

class SlidingWindowCompactionStrategy : SessionCompactionStrategy {
    override val id: String = STRATEGY_ID
    override val summaryMode: SessionCompactionSummaryMode = SessionCompactionSummaryMode.CLEAR

    override suspend fun compact(
        previousSummary: String?,
        messagesToCompact: List<ConversationMessage>,
        model: String,
    ): String = ""

    companion object {
        private const val STRATEGY_ID = "sliding-window-v1"
    }
}
