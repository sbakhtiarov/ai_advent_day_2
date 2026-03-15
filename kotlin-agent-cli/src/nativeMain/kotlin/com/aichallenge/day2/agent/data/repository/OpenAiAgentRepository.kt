package com.aichallenge.day2.agent.data.repository

import com.aichallenge.day2.agent.data.remote.OpenAiRemoteDataSource
import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.PromptRequestData
import com.aichallenge.day2.agent.domain.model.ToolCallTraceObserver
import com.aichallenge.day2.agent.domain.repository.AgentRepository

class OpenAiAgentRepository(
    private val remoteDataSource: OpenAiRemoteDataSource,
) : AgentRepository {
    override suspend fun complete(
        prompt: PromptRequestData,
        temperature: Double?,
        model: String?,
    ): AgentResponse {
        return complete(
            prompt = prompt,
            temperature = temperature,
            model = model,
            toolCallTraceObserver = null,
        )
    }

    override suspend fun complete(
        prompt: PromptRequestData,
        temperature: Double?,
        model: String?,
        toolCallTraceObserver: ToolCallTraceObserver?,
    ): AgentResponse {
        val assistantReply = remoteDataSource.fetchAssistantReply(
            prompt = prompt,
            temperature = temperature,
            model = model,
            toolCallTraceObserver = toolCallTraceObserver,
        )
        return AgentResponse(
            content = assistantReply.content,
            usage = assistantReply.usage,
        )
    }
}
