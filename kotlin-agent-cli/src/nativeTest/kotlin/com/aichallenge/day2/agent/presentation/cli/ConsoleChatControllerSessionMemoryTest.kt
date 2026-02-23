package com.aichallenge.day2.agent.presentation.cli

import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.MessageRole
import com.aichallenge.day2.agent.domain.repository.AgentRepository
import com.aichallenge.day2.agent.domain.usecase.SendPromptUseCase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ConsoleChatControllerSessionMemoryTest {
    @Test
    fun secondPromptRequestIncludesFirstSuccessfulTurn() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "answer one")),
                Result.success(AgentResponse(content = "answer two")),
            ),
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = listOf("prompt one", "prompt two", "/exit")),
        )

        controller.runInteractive()

        assertEquals(2, repository.conversations.size)

        val firstRequest = repository.conversations[0]
        val secondRequest = repository.conversations[1]
        val systemMessage = firstRequest.first()

        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER),
            firstRequest.map { it.role },
        )
        assertEquals("prompt one", firstRequest[1].content)

        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER),
            secondRequest.map { it.role },
        )
        assertEquals(systemMessage.content, secondRequest[0].content)
        assertEquals("prompt one", secondRequest[1].content)
        assertEquals("answer one", secondRequest[2].content)
        assertEquals("prompt two", secondRequest[3].content)
    }

    @Test
    fun failedRequestDoesNotAddTurnToSessionMemory() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.failure(IllegalStateException("boom")),
                Result.success(AgentResponse(content = "answer after failure")),
            ),
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = listOf("prompt one", "prompt two", "/exit")),
        )

        controller.runInteractive()

        assertEquals(2, repository.conversations.size)

        val firstRequest = repository.conversations[0]
        val secondRequest = repository.conversations[1]

        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER),
            firstRequest.map { it.role },
        )
        assertEquals("prompt one", firstRequest[1].content)

        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER),
            secondRequest.map { it.role },
        )
        assertEquals(firstRequest[0].content, secondRequest[0].content)
        assertEquals("prompt two", secondRequest[1].content)
    }

    @Test
    fun resetCommandClearsSessionMemoryBeforeNextPrompt() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "answer one")),
                Result.success(AgentResponse(content = "answer two")),
            ),
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = listOf("prompt one", "/reset", "prompt two", "/exit")),
        )

        controller.runInteractive()

        assertEquals(2, repository.conversations.size)

        val firstRequest = repository.conversations[0]
        val secondRequest = repository.conversations[1]

        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER),
            firstRequest.map { it.role },
        )
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER),
            secondRequest.map { it.role },
        )
        assertEquals(firstRequest[0].content, secondRequest[0].content)
        assertEquals("prompt two", secondRequest[1].content)
    }

    @Test
    fun configCommandResetsMemoryAndUsesUpdatedSystemPrompt() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "answer one")),
                Result.success(AgentResponse(content = "answer two")),
            ),
        )
        val updatedConfig = ConfigMenuSelection(
            format = OutputFormatOption.MARKDOWN,
            maxOutputTokens = 256,
            stopSequence = "DONE",
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(
                inputs = listOf("prompt one", "/config", "prompt two", "/exit"),
                configSelections = listOf(updatedConfig),
            ),
        )

        controller.runInteractive()

        assertEquals(2, repository.conversations.size)

        val firstRequest = repository.conversations[0]
        val secondRequest = repository.conversations[1]

        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER),
            firstRequest.map { it.role },
        )
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER),
            secondRequest.map { it.role },
        )
        assertNotEquals(firstRequest[0].content, secondRequest[0].content)
        assertContains(secondRequest[0].content, "Format: Markdown")
        assertContains(secondRequest[0].content, "Max output tokens: 256")
        assertContains(secondRequest[0].content, "Stop sequence: DONE")
        assertEquals("prompt two", secondRequest[1].content)
    }

    private fun createController(
        repository: RecordingAgentRepository,
        io: CliIO,
    ): ConsoleChatController {
        return ConsoleChatController(
            sendPromptUseCase = SendPromptUseCase(repository),
            initialSystemPrompt = "Base system prompt",
            initialModel = "gpt-4.1-mini",
            availableModels = listOf("gpt-4.1-mini"),
            modelPricing = emptyMap(),
            io = io,
        )
    }
}

private class RecordingAgentRepository(
    responses: List<Result<AgentResponse>>,
) : AgentRepository {
    private val queuedResponses = ArrayDeque(responses)
    val conversations = mutableListOf<List<ConversationMessage>>()

    override suspend fun complete(
        conversation: List<ConversationMessage>,
        temperature: Double?,
        model: String?,
    ): AgentResponse {
        conversations += conversation
        val response = queuedResponses.removeFirstOrNull()
            ?: error("No prepared response for conversation #${conversations.size}")
        return response.getOrThrow()
    }
}

private class FakeCliIO(
    inputs: List<String>,
    configSelections: List<ConfigMenuSelection> = emptyList(),
) : CliIO {
    private val queuedInputs = ArrayDeque<String?>(inputs)
    private val queuedConfigSelections = ArrayDeque(configSelections)

    override fun clearScreen() = Unit

    override fun hideCursor() = Unit

    override fun showCursor() = Unit

    override fun writeLine(text: String) = Unit

    override fun readLine(prompt: String): String? = nextInput()

    override fun readLineInFooter(prompt: String, divider: String, systemPromptText: String): String? = nextInput()

    override fun openConfigMenu(
        tabs: List<String>,
        descriptions: List<String>,
        currentSelection: ConfigMenuSelection,
    ): ConfigMenuSelection {
        return queuedConfigSelections.removeFirstOrNull() ?: currentSelection
    }

    private fun nextInput(): String? = queuedInputs.removeFirstOrNull()
}
