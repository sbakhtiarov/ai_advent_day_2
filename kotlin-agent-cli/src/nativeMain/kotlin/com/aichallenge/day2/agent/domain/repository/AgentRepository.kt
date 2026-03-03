package com.aichallenge.day2.agent.domain.repository

import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.PromptRequestData

interface AgentRepository {
    suspend fun complete(
        prompt: PromptRequestData,
        temperature: Double? = null,
        model: String? = null,
    ): AgentResponse
}
