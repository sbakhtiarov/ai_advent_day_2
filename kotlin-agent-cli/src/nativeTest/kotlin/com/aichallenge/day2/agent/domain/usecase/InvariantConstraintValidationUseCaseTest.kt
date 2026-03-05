package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.PromptRequestData
import com.aichallenge.day2.agent.domain.repository.AgentRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InvariantConstraintValidationUseCaseTest {
    @Test
    fun validateBuildsStrictInvariantRequest() = runBlocking {
        val repository = RecordingInvariantValidationRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = """{"status":"PASS","failed_constraints":[]}""",
                    ),
                ),
            ),
        )
        val useCase = InvariantConstraintValidationUseCase(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val result = useCase.validate(
            invariants = listOf(" Keep PR scope small ", "[Strict] Always run tests", "[strict] keep pr scope small"),
            userPrompt = "Review this PR.",
            assistantResponse = "Looks good.",
            model = "gpt-4.1-mini",
        )

        assertEquals(InvariantValidationStatus.PASS, result.status)
        assertTrue(result.failedConstraints.isEmpty())
        assertEquals(1, repository.conversations.size)
        val request = repository.conversations.single()
        assertEquals(listOf("system", "user"), request.map { message -> message.role.name.lowercase() })
        assertContains(
            request[0].content,
            "You validate assistant responses against strict invariant constraints.",
        )
        assertContains(request[1].content, "1. [Strict] Keep PR scope small")
        assertContains(request[1].content, "2. [Strict] Always run tests")
        assertContains(request[1].content, "Original user prompt:")
        assertContains(request[1].content, "Candidate LLM response:")
        assertContains(request[1].content, "\"source\":\"user|llm\"")
    }

    @Test
    fun validateParsesFailResponseWithMultipleViolations() = runBlocking {
        val repository = RecordingInvariantValidationRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "status": "FAIL",
                          "failed_constraints": [
                            {
                              "constraint": "[Strict] Mention test evidence",
                              "source": "llm",
                              "user_message": "Add concrete test evidence."
                            },
                            {
                              "constraint": "[Strict] Avoid unsupported claims",
                              "source": "user",
                              "user_message": "Remove claims that are not verified."
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val useCase = InvariantConstraintValidationUseCase(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val result = useCase.validate(
            invariants = listOf("Mention test evidence", "Avoid unsupported claims"),
            userPrompt = "Check docs.",
            assistantResponse = "All done.",
            model = "gpt-4.1-mini",
        )

        assertEquals(InvariantValidationStatus.FAIL, result.status)
        assertEquals(2, result.failedConstraints.size)
        assertEquals("[Strict] Mention test evidence", result.failedConstraints[0].constraint)
        assertEquals("Add concrete test evidence.", result.failedConstraints[0].userMessage)
        assertEquals(InvariantViolationSource.LLM, result.failedConstraints[0].source)
        assertEquals("[Strict] Avoid unsupported claims", result.failedConstraints[1].constraint)
        assertEquals("Remove claims that are not verified.", result.failedConstraints[1].userMessage)
        assertEquals(InvariantViolationSource.USER, result.failedConstraints[1].source)
    }

    @Test
    fun validateTreatsInvalidJsonAsFailWithSyntheticViolation() = runBlocking {
        val repository = RecordingInvariantValidationRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = "not a json response",
                    ),
                ),
            ),
        )
        val useCase = InvariantConstraintValidationUseCase(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val result = useCase.validate(
            invariants = listOf("Always include a migration note"),
            userPrompt = "Prepare release notes",
            assistantResponse = "Ready.",
            model = "gpt-4.1-mini",
        )

        assertEquals(InvariantValidationStatus.FAIL, result.status)
        assertEquals(1, result.failedConstraints.size)
        assertEquals("[Strict] Always include a migration note", result.failedConstraints.single().constraint)
        assertContains(result.failedConstraints.single().userMessage, "Validation output is not valid JSON.")
        assertEquals(InvariantViolationSource.LLM, result.failedConstraints.single().source)
    }
}

private class RecordingInvariantValidationRepository(
    responses: List<Result<AgentResponse>>,
) : AgentRepository {
    private val queuedResponses = ArrayDeque(responses)
    val conversations = mutableListOf<List<ConversationMessage>>()

    override suspend fun complete(
        prompt: PromptRequestData,
        temperature: Double?,
        model: String?,
    ): AgentResponse {
        conversations += prompt.toConversation()
        val response = queuedResponses.removeFirstOrNull()
            ?: error("No prepared response for request #${conversations.size}")
        return response.getOrThrow()
    }
}
