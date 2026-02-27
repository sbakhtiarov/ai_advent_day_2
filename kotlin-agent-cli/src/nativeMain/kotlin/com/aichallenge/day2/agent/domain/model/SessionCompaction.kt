package com.aichallenge.day2.agent.domain.model

data class SessionCompactionCandidate(
    val compactedCount: Int,
    val messagesToCompact: List<ConversationMessage>,
)

interface SessionCompactionStartPolicy {
    fun select(messages: List<ConversationMessage>): SessionCompactionCandidate?
}

interface SessionCompactionStrategy {
    val id: String

    suspend fun compact(
        previousSummary: String?,
        messagesToCompact: List<ConversationMessage>,
        model: String,
    ): String
}

class RollingWindowCompactionStartPolicy(
    private val threshold: Int,
    private val compactCount: Int,
    private val keepCount: Int,
) : SessionCompactionStartPolicy {
    init {
        require(threshold > 0) {
            "threshold must be > 0."
        }
        require(compactCount > 0) {
            "compactCount must be > 0."
        }
        require(keepCount >= 0) {
            "keepCount must be >= 0."
        }
        require(threshold == compactCount + keepCount) {
            "threshold must equal compactCount + keepCount."
        }
    }

    override fun select(messages: List<ConversationMessage>): SessionCompactionCandidate? {
        if (messages.size < threshold) {
            return null
        }

        if (compactCount > messages.size) {
            return null
        }

        return SessionCompactionCandidate(
            compactedCount = compactCount,
            messagesToCompact = messages.take(compactCount),
        )
    }
}
