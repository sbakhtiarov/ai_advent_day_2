package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.PromptRequestData
import com.aichallenge.day2.agent.domain.model.WorkingTaskState
import com.aichallenge.day2.agent.domain.repository.AgentRepository
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class WorkingMemoryDistillationUseCaseTest {
    @Test
    fun validJsonResponseIsAcceptedAndNormalized() = runBlocking {
        val repository = WorkingMemoryUseCaseTestAgentRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "goal": " Ship feature ",
                          "constraints": [" keep prompts short ", "", "keep prompts short", "no prompt injection"],
                          "decisions": ["use working memory", "use working memory"],
                          "assumptions": ["interactive only", " "],
                          "open_questions": ["How to consume it?"],
                          "next_steps": ["Define usage", "Define usage"],
                          "artifacts": ["README", ""]
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val useCase = WorkingMemoryDistillationUseCase(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val result = useCase.distill(
            previousTaskState = WorkingTaskState(
                goal = "Old goal",
                constraints = listOf("old constraint"),
            ),
            recentMessages = listOf(
                ConversationMessage.user("User asks to add a memory layer"),
                ConversationMessage.assistant("Assistant confirms implementation path"),
            ),
            model = "gpt-4.1-mini",
        )

        assertEquals("Ship feature", result.goal)
        assertEquals(listOf("keep prompts short", "no prompt injection"), result.constraints)
        assertEquals(listOf("use working memory"), result.decisions)
        assertEquals(listOf("interactive only"), result.assumptions)
        assertEquals(listOf("How to consume it?"), result.openQuestions)
        assertEquals(listOf("Define usage"), result.nextSteps)
        assertEquals(listOf("README"), result.artifacts)

        assertEquals(0.0, repository.temperatures.single())
        assertEquals("gpt-4.1-mini", repository.models.single())
        val promptMessage = repository.conversations.single()[1].content
        assertContains(promptMessage, "Previous task state JSON:")
        assertContains(promptMessage, "Recent messages:")
        assertContains(promptMessage, "USER: User asks to add a memory layer")
        assertContains(promptMessage, "ASSISTANT: Assistant confirms implementation path")
        val systemPrompt = repository.conversations.single()[0].content
        assertContains(systemPrompt, "Do not store volatile readouts that become stale quickly")
        assertContains(systemPrompt, "do not retain the exact returned clock reading")
    }

    @Test
    fun keyMismatchResponseThrows() = runBlocking {
        val repository = WorkingMemoryUseCaseTestAgentRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "goal": "Ship feature",
                          "constraints": [],
                          "decisions": [],
                          "assumptions": [],
                          "open_questions": [],
                          "next_steps": [],
                          "artifacts": [],
                          "extra": []
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val useCase = WorkingMemoryDistillationUseCase(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            useCase.distill(
                previousTaskState = null,
                recentMessages = listOf(
                    ConversationMessage.user("Prompt"),
                    ConversationMessage.assistant("Response"),
                ),
                model = "gpt-4.1-mini",
            )
        }
        assertContains(error.message.orEmpty(), "keys mismatch")
    }

    @Test
    fun nonStringArrayItemsThrowValidationError() = runBlocking {
        val repository = WorkingMemoryUseCaseTestAgentRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "goal": "Ship feature",
                          "constraints": [1],
                          "decisions": [],
                          "assumptions": [],
                          "open_questions": [],
                          "next_steps": [],
                          "artifacts": []
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val useCase = WorkingMemoryDistillationUseCase(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            useCase.distill(
                previousTaskState = null,
                recentMessages = listOf(
                    ConversationMessage.user("Prompt"),
                    ConversationMessage.assistant("Response"),
                ),
                model = "gpt-4.1-mini",
            )
        }
        assertContains(error.message.orEmpty(), "must be a string")
    }
}

private class WorkingMemoryUseCaseTestAgentRepository(
    responses: List<Result<AgentResponse>>,
) : AgentRepository {
    private val queuedResponses = ArrayDeque(responses)
    val conversations = mutableListOf<List<ConversationMessage>>()
    val temperatures = mutableListOf<Double?>()
    val models = mutableListOf<String?>()

    override suspend fun complete(
        prompt: PromptRequestData,
        temperature: Double?,
        model: String?,
    ): AgentResponse {
        conversations += prompt.toConversation()
        temperatures += temperature
        models += model
        val response = queuedResponses.removeFirstOrNull()
            ?: error("No prepared response for conversation #${conversations.size}")
        return response.getOrThrow()
    }
}
