package com.aichallenge.day2.agent.data.repository

import com.aichallenge.day2.agent.data.remote.OpenAiRemoteDataSource
import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.repository.AgentRepository

class OpenAiAgentRepository(
    private val remoteDataSource: OpenAiRemoteDataSource,
) : AgentRepository {
    override suspend fun complete(conversation: List<ConversationMessage>): AgentResponse {
        val content = remoteDataSource.fetchAssistantReply(conversation)
        return AgentResponse(content = content)
    }
}

