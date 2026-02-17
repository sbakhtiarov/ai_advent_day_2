package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.repository.AgentRepository

class SendPromptUseCase(
    private val agentRepository: AgentRepository,
) {
    suspend fun execute(
        history: List<ConversationMessage>,
        prompt: String,
    ): AgentResponse {
        val conversation = history + ConversationMessage.user(prompt)
        return agentRepository.complete(conversation)
    }
}

