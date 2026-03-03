package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.PromptRequestData
import com.aichallenge.day2.agent.domain.repository.AgentRepository

class SendPromptUseCase(
    private val agentRepository: AgentRepository,
) {
    suspend fun execute(
        prompt: PromptRequestData,
        temperature: Double? = null,
        model: String? = null,
    ): AgentResponse {
        return agentRepository.complete(prompt, temperature, model)
    }
}
