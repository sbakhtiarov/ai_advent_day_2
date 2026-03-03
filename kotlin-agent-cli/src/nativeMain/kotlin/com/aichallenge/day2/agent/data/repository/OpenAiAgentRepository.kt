package com.aichallenge.day2.agent.data.repository

import com.aichallenge.day2.agent.data.remote.OpenAiRemoteDataSource
import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.PromptRequestData
import com.aichallenge.day2.agent.domain.repository.AgentRepository

class OpenAiAgentRepository(
    private val remoteDataSource: OpenAiRemoteDataSource,
) : AgentRepository {
    override suspend fun complete(
        prompt: PromptRequestData,
        temperature: Double?,
        model: String?,
    ): AgentResponse {
        val assistantReply = remoteDataSource.fetchAssistantReply(prompt, temperature, model)
        return AgentResponse(
            content = assistantReply.content,
            usage = assistantReply.usage,
        )
    }
}
