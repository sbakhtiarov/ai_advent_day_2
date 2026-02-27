package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.MessageRole
import com.aichallenge.day2.agent.domain.repository.AgentRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RollingSummaryCompactionStrategyTest {
    @Test
    fun compactPassesPreviousSummaryAndMessagesToRepository() = runBlocking {
        val repository = RecordingAgentRepository(response = AgentResponse(content = "updated rolling summary"))
        val strategy = RollingSummaryCompactionStrategy(
            sendPromptUseCase = SendPromptUseCase(repository),
        )
        val messagesToCompact = listOf(
            ConversationMessage.user("first user"),
            ConversationMessage.assistant("first answer"),
        )

        val summary = strategy.compact(
            previousSummary = "old summary",
            messagesToCompact = messagesToCompact,
            model = "gpt-4.1-mini",
        )

        assertEquals("updated rolling summary", summary)
        assertEquals(1, repository.conversations.size)
        assertEquals(listOf<Double?>(0.0), repository.temperatures)
        assertEquals(listOf<String?>("gpt-4.1-mini"), repository.models)

        val conversation = repository.conversations.single()
        assertEquals(2, conversation.size)
        assertEquals(MessageRole.SYSTEM, conversation[0].role)
        assertEquals(MessageRole.USER, conversation[1].role)

        val userPrompt = conversation[1].content
        assertContains(userPrompt, "Previous summary:")
        assertContains(userPrompt, "old summary")
        assertContains(userPrompt, "1. USER: first user")
        assertContains(userPrompt, "2. ASSISTANT: first answer")
        assertFalse(userPrompt.contains("outside message"))
    }
}

private class RecordingAgentRepository(
    private val response: AgentResponse,
) : AgentRepository {
    val conversations = mutableListOf<List<ConversationMessage>>()
    val temperatures = mutableListOf<Double?>()
    val models = mutableListOf<String?>()

    override suspend fun complete(
        conversation: List<ConversationMessage>,
        temperature: Double?,
        model: String?,
    ): AgentResponse {
        conversations += conversation
        temperatures += temperature
        models += model
        return response
    }
}
