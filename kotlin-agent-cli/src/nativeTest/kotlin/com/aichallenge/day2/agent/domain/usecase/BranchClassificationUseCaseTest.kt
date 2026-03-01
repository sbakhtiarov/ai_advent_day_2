package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.BranchTopicCatalogEntry
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.repository.AgentRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class BranchClassificationUseCaseTest {
    @Test
    fun classifyParsesValidJsonResponse() = runBlocking {
        val repository = RecordingClassifierRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = """{"topic":"Building new application","subtopic":"Architecture"}""",
                    ),
                ),
            ),
        )
        val useCase = BranchClassificationUseCase(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val result = useCase.classify(
            existingTopics = listOf(
                BranchTopicCatalogEntry(
                    topic = "Building new application",
                    subtopics = listOf("API"),
                ),
            ),
            userPrompt = "Let's design architecture",
            assistantResponse = "We can start with clean architecture.",
            fallbackTopic = "General",
            fallbackSubtopic = "General",
            model = "gpt-4.1-mini",
        )

        assertEquals("Building new application", result.topic)
        assertEquals("Architecture", result.subtopic)
        assertEquals(false, result.usedFallback)
        assertEquals(listOf<Double?>(0.0), repository.temperatures)
        assertEquals(listOf<String?>("gpt-4.1-mini"), repository.models)
    }

    @Test
    fun classifyDefaultsBlankSubtopicToGeneral() = runBlocking {
        val repository = RecordingClassifierRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = """{"topic":"Building new application","subtopic":"   "}""",
                    ),
                ),
            ),
        )
        val useCase = BranchClassificationUseCase(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val result = useCase.classify(
            existingTopics = emptyList(),
            userPrompt = "Let's talk about features",
            assistantResponse = "Sure, what features first?",
            fallbackTopic = "General",
            fallbackSubtopic = "General",
            model = "gpt-4.1-mini",
        )

        assertEquals("Building new application", result.topic)
        assertEquals("General", result.subtopic)
        assertEquals(false, result.usedFallback)
    }

    @Test
    fun classifyRetriesOnceAndUsesSecondSuccessfulResponse() = runBlocking {
        val repository = RecordingClassifierRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "not json")),
                Result.success(
                    AgentResponse(
                        content = """{"topic":"Building new application","subtopic":"Network API"}""",
                    ),
                ),
            ),
        )
        val useCase = BranchClassificationUseCase(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val result = useCase.classify(
            existingTopics = emptyList(),
            userPrompt = "Need API contract",
            assistantResponse = "Let's define endpoints.",
            fallbackTopic = "General",
            fallbackSubtopic = "General",
            model = "gpt-4.1-mini",
        )

        assertEquals("Building new application", result.topic)
        assertEquals("Network API", result.subtopic)
        assertEquals(false, result.usedFallback)
        assertEquals(2, repository.conversations.size)
    }

    @Test
    fun classifyFallsBackAfterTwoFailedAttempts() = runBlocking {
        val repository = RecordingClassifierRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "no json")),
                Result.failure(IllegalStateException("temporary failure")),
            ),
        )
        val useCase = BranchClassificationUseCase(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val result = useCase.classify(
            existingTopics = emptyList(),
            userPrompt = "Need architecture advice",
            assistantResponse = "Let's break it down by modules.",
            fallbackTopic = " Existing Topic ",
            fallbackSubtopic = " Existing Subtopic ",
            model = "gpt-4.1-mini",
        )

        assertEquals("Existing Topic", result.topic)
        assertEquals("Existing Subtopic", result.subtopic)
        assertEquals(true, result.usedFallback)
        assertEquals(2, repository.conversations.size)
    }
}

private class RecordingClassifierRepository(
    responses: List<Result<AgentResponse>>,
) : AgentRepository {
    private val queuedResponses = ArrayDeque(responses)
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

        val response = queuedResponses.removeFirstOrNull()
            ?: error("No prepared classifier response for call #${conversations.size}")
        return response.getOrThrow()
    }
}
