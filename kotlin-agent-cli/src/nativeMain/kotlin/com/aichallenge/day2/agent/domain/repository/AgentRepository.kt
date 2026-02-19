package com.aichallenge.day2.agent.domain.repository

import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.ConversationMessage

interface AgentRepository {
    suspend fun complete(conversation: List<ConversationMessage>, temperature: Double? = null): AgentResponse
}
