package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.MessageRole
import com.aichallenge.day2.agent.domain.model.ProfilePreferenceState
import com.aichallenge.day2.agent.domain.model.PromptRequestData
import com.aichallenge.day2.agent.domain.repository.AgentRepository
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class ProfileMemoryDistillationUseCaseTest {
    @Test
    fun validJsonResponseIsAcceptedAndNormalized() = runBlocking {
        val repository = ProfileMemoryUseCaseTestAgentRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "writing_style": " concise bullets ",
                          "tooling_preferences": [" use rg ", "", "use rg", "prefer TypeScript"],
                          "workflow_defaults": ["always run tests before finalizing", "always run tests before finalizing"],
                          "stable_constraints": ["avoid destructive git commands", " "]
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val useCase = ProfileMemoryDistillationUseCase(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val result = useCase.distill(
            previousPreferenceState = ProfilePreferenceState(
                writingStyle = "formal",
                toolingPreferences = listOf("old"),
            ),
            recentMessages = listOf(
                ConversationMessage.user("Use concise answers and run tests first"),
                ConversationMessage.assistant("Will do"),
            ),
            model = "gpt-4.1-mini",
        )

        assertEquals("concise bullets", result.writingStyle)
        assertEquals(listOf("use rg", "prefer TypeScript"), result.toolingPreferences)
        assertEquals(listOf("always run tests before finalizing"), result.workflowDefaults)
        assertEquals(listOf("avoid destructive git commands"), result.stableConstraints)

        assertEquals(0.0, repository.temperatures.single())
        assertEquals("gpt-4.1-mini", repository.models.single())
        val promptMessage = repository.conversations.single()[1].content
        assertContains(promptMessage, "Previous profile preference state JSON:")
        assertContains(promptMessage, "Recent user messages:")
        assertContains(promptMessage, "USER: Use concise answers and run tests first")
    }

    @Test
    fun keyMismatchResponseThrows() = runBlocking {
        val repository = ProfileMemoryUseCaseTestAgentRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "writing_style": "concise",
                          "tooling_preferences": [],
                          "workflow_defaults": [],
                          "stable_constraints": [],
                          "extra": []
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val useCase = ProfileMemoryDistillationUseCase(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            useCase.distill(
                previousPreferenceState = null,
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
        val repository = ProfileMemoryUseCaseTestAgentRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "writing_style": "concise",
                          "tooling_preferences": [1],
                          "workflow_defaults": [],
                          "stable_constraints": []
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val useCase = ProfileMemoryDistillationUseCase(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            useCase.distill(
                previousPreferenceState = null,
                recentMessages = listOf(
                    ConversationMessage.user("Prompt"),
                    ConversationMessage.assistant("Response"),
                ),
                model = "gpt-4.1-mini",
            )
        }
        assertContains(error.message.orEmpty(), "must be a string")
    }

    @Test
    fun assistantOnlyRecentMessagesAreRejected() = runBlocking {
        val repository = ProfileMemoryUseCaseTestAgentRepository(
            responses = emptyList(),
        )
        val useCase = ProfileMemoryDistillationUseCase(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            useCase.distill(
                previousPreferenceState = null,
                recentMessages = listOf(
                    ConversationMessage(
                        role = MessageRole.ASSISTANT,
                        content = "I assume concise style.",
                    ),
                ),
                model = "gpt-4.1-mini",
            )
        }
        assertContains(error.message.orEmpty(), "at least one USER message")
    }
}

private class ProfileMemoryUseCaseTestAgentRepository(
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
