package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.PromptRequestData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BuildPromptUseCaseTest {
    private val useCase = BuildPromptUseCase()

    @Test
    fun executeBuildsConversationWithSummary() {
        val result = useCase.execute(
            request = BuildPromptRequest(
                systemPrompt = "system prompt",
                session = SessionPromptData(
                    messages = listOf(
                        ConversationMessage.user("q1"),
                        ConversationMessage.assistant("a1"),
                    ),
                    summarySystemMessage = "summary block",
                ),
                userPrompt = "next question",
            ),
        )

        assertEquals(
            PromptRequestData(
                systemPrompt = "system prompt",
                contextSystemMessages = listOf("summary block"),
                messages = listOf(
                    ConversationMessage.user("q1"),
                    ConversationMessage.assistant("a1"),
                    ConversationMessage.user("next question"),
                ),
            ),
            result,
        )
    }

    @Test
    fun executeBuildsConversationWithoutSummary() {
        val result = useCase.execute(
            request = BuildPromptRequest(
                systemPrompt = "system prompt",
                session = SessionPromptData(
                    messages = listOf(
                        ConversationMessage.user("q1"),
                        ConversationMessage.assistant("a1"),
                    ),
                    summarySystemMessage = null,
                ),
                userPrompt = "next question",
            ),
        )

        assertEquals(
            PromptRequestData(
                systemPrompt = "system prompt",
                contextSystemMessages = emptyList(),
                messages = listOf(
                    ConversationMessage.user("q1"),
                    ConversationMessage.assistant("a1"),
                    ConversationMessage.user("next question"),
                ),
            ),
            result,
        )
    }

    @Test
    fun executeRejectsInvalidSessionMessageRoles() {
        assertFailsWith<IllegalArgumentException> {
            useCase.execute(
                request = BuildPromptRequest(
                    systemPrompt = "system prompt",
                    session = SessionPromptData(
                        messages = listOf(
                            ConversationMessage.system("summary"),
                        ),
                    ),
                    userPrompt = "next question",
                ),
            )
        }
    }
}
