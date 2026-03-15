package com.aichallenge.day2.agent.domain.repository

import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.PromptRequestData
import com.aichallenge.day2.agent.domain.model.ToolCallTraceObserver

interface AgentRepository {
    suspend fun complete(
        prompt: PromptRequestData,
        temperature: Double? = null,
        model: String? = null,
    ): AgentResponse

    suspend fun complete(
        prompt: PromptRequestData,
        temperature: Double? = null,
        model: String? = null,
        toolCallTraceObserver: ToolCallTraceObserver? = null,
    ): AgentResponse = complete(
        prompt = prompt,
        temperature = temperature,
        model = model,
    )
}
