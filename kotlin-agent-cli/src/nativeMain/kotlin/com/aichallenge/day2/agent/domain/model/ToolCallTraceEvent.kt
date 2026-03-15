package com.aichallenge.day2.agent.domain.model

sealed interface ToolCallTraceEvent {
    data class Started(
        val toolLabel: String,
    ) : ToolCallTraceEvent
}

interface ToolCallTraceObserver {
    suspend fun onToolCallTrace(event: ToolCallTraceEvent)
}
