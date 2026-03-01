package com.aichallenge.day2.agent.domain.model

data class SessionCompactionCandidate(
    val compactedCount: Int,
    val messagesToCompact: List<ConversationMessage>,
)

enum class SessionCompactionMode(
    val id: String,
    val label: String,
) {
    ROLLING_SUMMARY(
        id = "rolling-summary",
        label = "Rolling summary",
    ),
    SLIDING_WINDOW(
        id = "sliding-window",
        label = "Sliding window",
    ),
    FACT_MAP(
        id = "fact-map",
        label = "Fact map",
    ),
    ;

    companion object {
        fun fromIdOrNull(id: String?): SessionCompactionMode? {
            if (id.isNullOrBlank()) {
                return null
            }
            return entries.firstOrNull { mode -> mode.id == id }
        }
    }
}

enum class SessionCompactionSummaryMode {
    GENERATE,
    CLEAR,
}

interface SessionCompactionStartPolicy {
    fun select(messages: List<ConversationMessage>): SessionCompactionCandidate?
}

interface SessionCompactionStrategy {
    val id: String
    val summaryMode: SessionCompactionSummaryMode

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

class SlidingWindowCompactionStartPolicy(
    private val maxMessages: Int,
) : SessionCompactionStartPolicy {
    init {
        require(maxMessages > 0) {
            "maxMessages must be > 0."
        }
    }

    override fun select(messages: List<ConversationMessage>): SessionCompactionCandidate? {
        if (messages.size <= maxMessages) {
            return null
        }

        var compactedCount = messages.size - maxMessages
        if (compactedCount % 2 != 0) {
            compactedCount += 1
        }

        if (compactedCount <= 0 || compactedCount > messages.size) {
            return null
        }

        return SessionCompactionCandidate(
            compactedCount = compactedCount,
            messagesToCompact = messages.take(compactedCount),
        )
    }
}
