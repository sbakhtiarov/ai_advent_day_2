package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.MessageRole
import com.aichallenge.day2.agent.domain.repository.AgentRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FactMapCompactionStrategyTest {
    @Test
    fun compactPassesPreviousSummaryAndMessagesAndReturnsNormalizedJson() = runBlocking {
        val repository = FactMapRecordingAgentRepository(
            response = AgentResponse(
                content = """
                    {
                      "goal": " Build cli memory ",
                      "constraints": ["keep 10 messages", "keep 10 messages", " "],
                      "decisions": ["use fact-map"],
                      "preferences": [],
                      "agreements": ["implement now"]
                    }
                """.trimIndent(),
            ),
        )
        val strategy = FactMapCompactionStrategy(
            sendPromptUseCase = SendPromptUseCase(repository),
        )
        val messagesToCompact = listOf(
            ConversationMessage.user("first user"),
            ConversationMessage.assistant("first answer"),
        )

        val summary = strategy.compact(
            previousSummary = """
                {
                  "goal": "old goal",
                  "constraints": [],
                  "decisions": [],
                  "preferences": [],
                  "agreements": []
                }
            """.trimIndent(),
            messagesToCompact = messagesToCompact,
            model = "gpt-4.1-mini",
        )

        assertEquals(1, repository.conversations.size)
        assertEquals(listOf<Double?>(0.0), repository.temperatures)
        assertEquals(listOf<String?>("gpt-4.1-mini"), repository.models)

        val conversation = repository.conversations.single()
        assertEquals(2, conversation.size)
        assertEquals(MessageRole.SYSTEM, conversation[0].role)
        assertEquals(MessageRole.USER, conversation[1].role)

        val userPrompt = conversation[1].content
        assertContains(userPrompt, "Previous fact map JSON:")
        assertContains(userPrompt, "\"goal\":\"old goal\"")
        assertContains(userPrompt, "1. USER: first user")
        assertContains(userPrompt, "2. ASSISTANT: first answer")

        val parsedSummary = Json.parseToJsonElement(summary) as JsonObject
        assertEquals("Build cli memory", parsedSummary["goal"]?.toString()?.trim('"'))
        assertEquals(
            JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("keep 10 messages"))),
            parsedSummary["constraints"],
        )
    }

    @Test
    fun compactRejectsInvalidJsonResponse() = runBlocking {
        val repository = FactMapRecordingAgentRepository(
            response = AgentResponse(content = "not-json"),
        )
        val strategy = FactMapCompactionStrategy(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            strategy.compact(
                previousSummary = null,
                messagesToCompact = listOf(
                    ConversationMessage.user("q1"),
                    ConversationMessage.assistant("a1"),
                ),
                model = "gpt-4.1-mini",
            )
        }
        assertContains(error.message.orEmpty(), "JSON object")
    }

    @Test
    fun compactRejectsResponseWhenRequiredKeyIsMissing() = runBlocking {
        val repository = FactMapRecordingAgentRepository(
            response = AgentResponse(
                content = """
                    {
                      "goal": "x",
                      "constraints": [],
                      "decisions": [],
                      "preferences": []
                    }
                """.trimIndent(),
            ),
        )
        val strategy = FactMapCompactionStrategy(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            strategy.compact(
                previousSummary = null,
                messagesToCompact = listOf(
                    ConversationMessage.user("q1"),
                    ConversationMessage.assistant("a1"),
                ),
                model = "gpt-4.1-mini",
            )
        }
        assertContains(error.message.orEmpty(), "Missing: agreements")
    }
}

private class FactMapRecordingAgentRepository(
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
