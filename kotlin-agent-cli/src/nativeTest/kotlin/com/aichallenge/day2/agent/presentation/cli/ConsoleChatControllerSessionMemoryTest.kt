package com.aichallenge.day2.agent.presentation.cli

import com.aichallenge.day2.agent.core.config.ModelPricing
import com.aichallenge.day2.agent.core.config.ModelProperties
import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.MemoryEstimateSource
import com.aichallenge.day2.agent.domain.model.MemoryUsageSnapshot
import com.aichallenge.day2.agent.domain.model.MessageRole
import com.aichallenge.day2.agent.domain.model.SessionMemoryState
import com.aichallenge.day2.agent.domain.model.TokenUsage
import com.aichallenge.day2.agent.domain.repository.AgentRepository
import com.aichallenge.day2.agent.domain.repository.SessionMemoryStore
import com.aichallenge.day2.agent.domain.usecase.SendPromptUseCase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

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
    fun interactiveModeRestoresPersistedSnapshotBeforeFirstPrompt() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "new answer")),
            ),
        )
        val store = RecordingSessionMemoryStore(
            loadedState = SessionMemoryState(
                messages = listOf(
                    ConversationMessage.system("persisted system"),
                    ConversationMessage.user("old question"),
                    ConversationMessage.assistant("old answer"),
                ),
                usage = MemoryUsageSnapshot(
                    estimatedTokens = 300,
                    source = MemoryEstimateSource.HYBRID,
                    messageCount = 3,
                ),
            ),
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = listOf("new question", "/exit")),
            sessionMemoryStore = store,
        )

        controller.runInteractive()

        assertEquals(1, store.loadCalls)
        assertEquals(1, repository.conversations.size)
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER),
            repository.conversations.single().map { it.role },
        )
        assertEquals("persisted system", repository.conversations.single()[0].content)
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
    fun successfulTurnPersistsHybridUsageSnapshot() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = "response",
                        usage = TokenUsage(
                            totalTokens = 120,
                            inputTokens = 100,
                            outputTokens = 20,
                        ),
                    ),
                ),
            ),
        )
        val store = RecordingSessionMemoryStore()
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = listOf("prompt one", "/exit")),
            sessionMemoryStore = store,
        )

        controller.runInteractive()

        assertEquals(1, store.saveStates.size)
        val savedState = store.saveStates.single()
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT),
            savedState.messages.map { it.role },
        )
        val usage = assertNotNull(savedState.usage)
        assertEquals(MemoryEstimateSource.HYBRID, usage.source)
        assertEquals(3, usage.messageCount)
        assertEquals(106, usage.estimatedTokens)
    }

    @Test
    fun failedTurnDoesNotPersistSnapshot() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.failure(IllegalStateException("boom")),
            ),
        )
        val store = RecordingSessionMemoryStore()
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = listOf("prompt one", "/exit")),
            sessionMemoryStore = store,
        )

        controller.runInteractive()

        assertEquals(0, store.saveStates.size)
    }

    @Test
    fun successfulTurnWithoutUsagePersistsHeuristicSnapshot() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "answer one")),
            ),
        )
        val store = RecordingSessionMemoryStore()
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = listOf("prompt one", "/exit")),
            sessionMemoryStore = store,
        )

        controller.runInteractive()

        assertEquals(1, store.saveStates.size)
        val usage = assertNotNull(store.saveStates.single().usage)
        assertEquals(MemoryEstimateSource.HEURISTIC, usage.source)
        assertEquals(3, usage.messageCount)
        assertEquals(true, usage.estimatedTokens > 0)
    }

    @Test
    fun resetCommandClearsSessionMemoryBeforeNextPrompt() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "answer one")),
                Result.success(AgentResponse(content = "answer two")),
            ),
        )
        val store = RecordingSessionMemoryStore()
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = listOf("prompt one", "/reset", "prompt two", "/exit")),
            sessionMemoryStore = store,
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
        assertEquals(0, store.clearCalls)
        assertEquals(3, store.saveStates.size)
        assertEquals(
            listOf(MessageRole.SYSTEM),
            store.saveStates[1].messages.map { it.role },
        )
        val usage = assertNotNull(store.saveStates[1].usage)
        assertEquals(MemoryEstimateSource.HEURISTIC, usage.source)
        assertEquals(1, usage.messageCount)
    }

    @Test
    fun configCommandResetsMemoryAndUsesUpdatedSystemPrompt() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "answer one")),
                Result.success(AgentResponse(content = "answer two")),
            ),
        )
        val store = RecordingSessionMemoryStore()
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
            sessionMemoryStore = store,
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
        assertEquals(3, store.saveStates.size)
        assertEquals(
            listOf(MessageRole.SYSTEM),
            store.saveStates[1].messages.map { it.role },
        )
        assertEquals(secondRequest[0].content, store.saveStates[1].messages[0].content)
        val usage = assertNotNull(store.saveStates[1].usage)
        assertEquals(MemoryEstimateSource.HEURISTIC, usage.source)
        assertEquals(1, usage.messageCount)
    }

    @Test
    fun runSinglePromptDoesNotUsePersistentMemory() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "one-shot answer")),
            ),
        )
        val store = RecordingSessionMemoryStore(
            loadedState = SessionMemoryState(
                messages = listOf(
                    ConversationMessage.system("persisted system"),
                    ConversationMessage.user("old question"),
                    ConversationMessage.assistant("old answer"),
                ),
                usage = MemoryUsageSnapshot(
                    estimatedTokens = 300,
                    source = MemoryEstimateSource.HYBRID,
                    messageCount = 3,
                ),
            ),
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = emptyList()),
            sessionMemoryStore = store,
            persistentMemoryEnabled = false,
        )

        val exitCode = controller.runSinglePrompt("one-shot question")

        assertEquals(0, exitCode)
        assertEquals(0, store.loadCalls)
        assertEquals(0, store.saveStates.size)
        assertEquals(0, store.clearCalls)
    }

    @Test
    fun memoryCommandUsesPersistedUsageOnStartup() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val io = FakeCliIO(inputs = listOf("/memory", "/exit"))
        val store = RecordingSessionMemoryStore(
            loadedState = SessionMemoryState(
                messages = listOf(
                    ConversationMessage.system("persisted system"),
                    ConversationMessage.user("old question"),
                    ConversationMessage.assistant("old answer"),
                ),
                usage = MemoryUsageSnapshot(
                    estimatedTokens = 321,
                    source = MemoryEstimateSource.HYBRID,
                    messageCount = 3,
                ),
            ),
        )
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = store,
        )

        controller.runInteractive()

        assertEquals(1, store.loadCalls)
        assertContains(io.outputText(), "memory> Used: 321/1,047,576")
        assertContains(io.outputText(), "memory> Estimate: hybrid (usage+assistant)")
    }

    @Test
    fun memoryCommandFallsBackToHeuristicWhenPersistedUsageIsInvalid() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val io = FakeCliIO(inputs = listOf("/memory", "/exit"))
        val store = RecordingSessionMemoryStore(
            loadedState = SessionMemoryState(
                messages = listOf(
                    ConversationMessage.system("persisted system"),
                    ConversationMessage.user("old question"),
                    ConversationMessage.assistant("old answer"),
                ),
                usage = MemoryUsageSnapshot(
                    estimatedTokens = 321,
                    source = MemoryEstimateSource.HYBRID,
                    messageCount = 2,
                ),
            ),
        )
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = store,
        )

        controller.runInteractive()

        assertContains(io.outputText(), "memory> Estimate: heuristic (text-length)")
    }

    @Test
    fun helpAndHeaderIncludeMemoryCommand() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val io = FakeCliIO(inputs = listOf("/help", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
        )

        controller.runInteractive()

        val output = io.outputText()
        assertContains(output, "commands: /help, /models, /model <id|number>, /memory, /config, /temp <0..2>, /reset, /exit")
        assertContains(output, "/memory              show session-memory context usage")
    }

    private fun createController(
        repository: RecordingAgentRepository,
        io: CliIO,
        sessionMemoryStore: SessionMemoryStore? = null,
        persistentMemoryEnabled: Boolean = true,
    ): ConsoleChatController {
        return ConsoleChatController(
            sendPromptUseCase = SendPromptUseCase(repository),
            initialSystemPrompt = "Base system prompt",
            initialModel = "gpt-4.1-mini",
            models = listOf(
                ModelProperties(
                    id = "gpt-4.1-mini",
                    pricing = ModelPricing(
                        inputUsdPer1M = 0.40,
                        outputUsdPer1M = 1.60,
                    ),
                    contextWindowTokens = 1_047_576,
                ),
            ),
            io = io,
            sessionMemoryStore = sessionMemoryStore,
            persistentMemoryEnabled = persistentMemoryEnabled,
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
    private val lines = mutableListOf<String>()

    override fun clearScreen() = Unit

    override fun hideCursor() = Unit

    override fun showCursor() = Unit

    override fun writeLine(text: String) {
        lines += text
    }

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

    fun outputText(): String = lines.joinToString(separator = "\n")
}

private class RecordingSessionMemoryStore(
    private val loadedState: SessionMemoryState? = null,
) : SessionMemoryStore {
    var loadCalls: Int = 0
        private set
    var clearCalls: Int = 0
        private set
    val saveStates = mutableListOf<SessionMemoryState>()

    override fun load(): SessionMemoryState? {
        loadCalls += 1
        return loadedState?.copy(
            messages = loadedState.messages.toList(),
            usage = loadedState.usage?.copy(),
        )
    }

    override fun save(state: SessionMemoryState) {
        saveStates += SessionMemoryState(
            messages = state.messages.toList(),
            usage = state.usage?.copy(),
        )
    }

    override fun clear() {
        clearCalls += 1
    }
}
