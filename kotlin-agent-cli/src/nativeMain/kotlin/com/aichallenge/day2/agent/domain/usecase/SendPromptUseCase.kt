package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.PromptRequestData
import com.aichallenge.day2.agent.domain.model.ToolCallTraceObserver
import com.aichallenge.day2.agent.domain.repository.AgentRepository

class SendPromptUseCase(
    private val agentRepository: AgentRepository,
) {
    suspend fun execute(
        prompt: PromptRequestData,
        temperature: Double? = null,
        model: String? = null,
        toolCallTraceObserver: ToolCallTraceObserver? = null,
    ): AgentResponse {
        return agentRepository.complete(
            prompt = prompt,
            temperature = temperature,
            model = model,
            toolCallTraceObserver = toolCallTraceObserver,
        )
    }
}
