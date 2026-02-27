package com.aichallenge.day2.agent.presentation.cli

import com.aichallenge.day2.agent.core.config.ModelPricing
import com.aichallenge.day2.agent.core.config.ModelProperties
import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.CompactedSessionSummary
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.MemoryEstimateSource
import com.aichallenge.day2.agent.domain.model.MemoryUsageSnapshot
import com.aichallenge.day2.agent.domain.model.MessageRole
import com.aichallenge.day2.agent.domain.model.RollingWindowCompactionStartPolicy
import com.aichallenge.day2.agent.domain.model.SessionCompactionStrategy
import com.aichallenge.day2.agent.domain.model.SessionMemoryState
import com.aichallenge.day2.agent.domain.model.TokenUsage
import com.aichallenge.day2.agent.domain.repository.AgentRepository
import com.aichallenge.day2.agent.domain.repository.SessionMemoryStore
import com.aichallenge.day2.agent.domain.usecase.SessionMemoryCompactionCoordinator
import com.aichallenge.day2.agent.domain.usecase.SendPromptUseCase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        val io = FakeCliIO(inputs = listOf("prompt one", "prompt two", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
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
        assertEquals(2, io.showThinkingIndicatorCalls)
        assertEquals(2, io.hideThinkingIndicatorCalls)
    }

    @Test
    fun promptRequestShowsAndHidesThinkingIndicator() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "answer one")),
            ),
        )
        val io = FakeCliIO(inputs = listOf("prompt one", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
        )

        controller.runInteractive()

        assertEquals(1, io.showThinkingIndicatorCalls)
        assertEquals(1, io.hideThinkingIndicatorCalls)
        assertTrue(io.updateThinkingIndicatorCalls >= 1)
        assertContains(io.lastThinkingProgressText.orEmpty(), "s")
    }

    @Test
    fun failedPromptStillHidesThinkingIndicator() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.failure(IllegalStateException("boom")),
            ),
        )
        val io = FakeCliIO(inputs = listOf("prompt one", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
        )

        controller.runInteractive()

        assertEquals(1, io.showThinkingIndicatorCalls)
        assertEquals(1, io.hideThinkingIndicatorCalls)
        assertTrue(io.updateThinkingIndicatorCalls >= 1)
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
    fun rollingCompactionUsesSummaryAndPersistsCompactedState() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = (1..7).map { index ->
                Result.success(AgentResponse(content = "answer $index"))
            },
        )
        val store = RecordingSessionMemoryStore()
        val strategy = RecordingCompactionStrategy(
            summariesToReturn = listOf("summary one"),
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(
                inputs = (1..7).map { index -> "prompt $index" } + "/exit",
            ),
            sessionMemoryStore = store,
            sessionMemoryCompactionCoordinator = SessionMemoryCompactionCoordinator(
                startPolicy = RollingWindowCompactionStartPolicy(
                    threshold = 12,
                    compactCount = 10,
                    keepCount = 2,
                ),
                strategy = strategy,
            ),
        )

        controller.runInteractive()

        assertEquals(listOf<String?>(null), strategy.previousSummaries)
        assertEquals(1, strategy.compactedMessageBatches.size)
        assertEquals(10, strategy.compactedMessageBatches.single().size)
        assertEquals("prompt 1", strategy.compactedMessageBatches.single()[0].content)
        assertEquals("answer 5", strategy.compactedMessageBatches.single()[9].content)

        val requestAfterCompaction = repository.conversations[6]
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER),
            requestAfterCompaction.map { it.role },
        )
        assertContains(requestAfterCompaction[1].content, "summary one")
        assertEquals("prompt 6", requestAfterCompaction[2].content)
        assertEquals("answer 6", requestAfterCompaction[3].content)
        assertEquals("prompt 7", requestAfterCompaction[4].content)

        val savedWithSummary = assertNotNull(
            store.saveStates.firstOrNull { it.compactedSummary != null },
        )
        assertEquals("summary one", savedWithSummary.compactedSummary?.content)
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT),
            savedWithSummary.messages.map { it.role },
        )
    }

    @Test
    fun secondRollingCompactionReceivesPreviousSummary() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = (1..12).map { index ->
                Result.success(AgentResponse(content = "answer $index"))
            },
        )
        val strategy = RecordingCompactionStrategy(
            summariesToReturn = listOf("summary one", "summary two"),
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(
                inputs = (1..12).map { index -> "prompt $index" } + "/exit",
            ),
            sessionMemoryCompactionCoordinator = SessionMemoryCompactionCoordinator(
                startPolicy = RollingWindowCompactionStartPolicy(
                    threshold = 12,
                    compactCount = 10,
                    keepCount = 2,
                ),
                strategy = strategy,
            ),
        )

        controller.runInteractive()

        assertEquals(listOf<String?>(null, "summary one"), strategy.previousSummaries)
        assertEquals(2, strategy.compactedMessageBatches.size)
        assertEquals("prompt 6", strategy.compactedMessageBatches[1][0].content)
        assertEquals("answer 10", strategy.compactedMessageBatches[1][9].content)

        val requestAfterSecondCompaction = repository.conversations[11]
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER),
            requestAfterSecondCompaction.map { it.role },
        )
        assertContains(requestAfterSecondCompaction[1].content, "summary two")
        assertEquals("prompt 11", requestAfterSecondCompaction[2].content)
        assertEquals("answer 11", requestAfterSecondCompaction[3].content)
        assertEquals("prompt 12", requestAfterSecondCompaction[4].content)
    }

    @Test
    fun restoredCompactedSummaryIsIncludedInFirstPromptContext() = runBlocking {
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
                compactedSummary = CompactedSessionSummary(
                    strategyId = "rolling-summary-v1",
                    content = "persisted summary",
                ),
                usage = null,
            ),
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = listOf("new question", "/exit")),
            sessionMemoryStore = store,
        )

        controller.runInteractive()

        val request = repository.conversations.single()
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER),
            request.map { it.role },
        )
        assertContains(request[1].content, "persisted summary")
        assertEquals("old question", request[2].content)
        assertEquals("old answer", request[3].content)
        assertEquals("new question", request[4].content)
    }

    @Test
    fun fileReferenceCommandDefersFileReadUntilPromptSubmit() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val fileReferenceReader = RecordingFileReferenceReader(
            contentsByPath = mapOf("notes.txt" to "ignored"),
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = listOf("@notes.txt", "/exit")),
            fileReferenceReader = fileReferenceReader,
        )

        controller.runInteractive()

        assertEquals(0, fileReferenceReader.readPaths.size)
        assertEquals(0, repository.conversations.size)
    }

    @Test
    fun fileReferenceCommandInjectsFileContentIntoPromptAndPersistsIt() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "answer one")),
            ),
        )
        val io = FakeCliIO(inputs = listOf("@notes.txt", "summarize this file", "/exit"))
        val store = RecordingSessionMemoryStore()
        val fileReferenceReader = RecordingFileReferenceReader(
            contentsByPath = mapOf("notes.txt" to "line one\nline two"),
        )
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = store,
            fileReferenceReader = fileReferenceReader,
        )

        controller.runInteractive()

        assertEquals(listOf("notes.txt"), fileReferenceReader.readPaths)
        assertEquals(1, repository.conversations.size)
        val userPrompt = repository.conversations.single()[1].content
        assertContains(userPrompt, "summarize this file")
        assertContains(userPrompt, "The CLI already read the following local files")
        assertContains(userPrompt, "[FILE] notes.txt")
        assertContains(userPrompt, "line one\nline two")

        val output = io.outputText()
        assertContains(output, "ref> notes.txt")
        assertFalse(output.contains("line one\nline two"))

        assertEquals(1, store.saveStates.size)
        assertContains(store.saveStates.single().messages[1].content, "line one\nline two")
    }

    @Test
    fun fileReferenceAppliesToOnlyNextSuccessfulPrompt() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "answer one")),
                Result.success(AgentResponse(content = "answer two")),
            ),
        )
        val fileReferenceReader = RecordingFileReferenceReader(
            contentsByPath = mapOf("notes.txt" to "file body"),
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = listOf("@notes.txt", "first prompt", "second prompt", "/exit")),
            fileReferenceReader = fileReferenceReader,
        )

        controller.runInteractive()

        assertEquals(2, repository.conversations.size)
        assertContains(repository.conversations[0][1].content, "[FILE] notes.txt")
        assertEquals("second prompt", repository.conversations[1].last().content)
        assertFalse(repository.conversations[1].last().content.contains("[FILE] notes.txt"))
        assertEquals(listOf("notes.txt"), fileReferenceReader.readPaths)
    }

    @Test
    fun inlineFileReferenceWithSpacesIsReadAndAttached() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "review result")),
            ),
        )
        val path = "/Users/sergei.bakhtiarov/AI Advent Challenge/day2/kotlin-agent-cli/src/nativeMain/kotlin/com/aichallenge/day2/agent/presentation/cli/CliIO.kt"
        val fileReferenceReader = RecordingFileReferenceReader(
            contentsByPath = mapOf(path to "class CliIO {}"),
        )
        val io = FakeCliIO(
            inputs = listOf("Do code review of the file @$path", "/exit"),
        )
        val controller = createController(
            repository = repository,
            io = io,
            fileReferenceReader = fileReferenceReader,
        )

        controller.runInteractive()

        assertEquals(listOf(path), fileReferenceReader.readPaths)
        val userPrompt = repository.conversations.single()[1].content
        assertContains(userPrompt, "Do code review of the file")
        assertContains(userPrompt, "[FILE] $path")
        assertContains(userPrompt, "class CliIO {}")
        assertFalse(userPrompt.contains("@$path"))

        val output = io.outputText()
        assertContains(output, "ref> $path")
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
        assertContains(output, "commands: /help, /models, /model <id|number>, /memory, /config, /temp <0..2>, /reset, /exit, @<path>")
        assertContains(output, "/memory              show session-memory context usage")
        assertContains(output, "@<path>              attach file for the next prompt")
    }

    private fun createController(
        repository: RecordingAgentRepository,
        io: CliIO,
        sessionMemoryStore: SessionMemoryStore? = null,
        persistentMemoryEnabled: Boolean = true,
        fileReferenceReader: FileReferenceReader = RecordingFileReferenceReader(emptyMap()),
        sessionMemoryCompactionCoordinator: SessionMemoryCompactionCoordinator = SessionMemoryCompactionCoordinator.disabled(),
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
            fileReferenceReader = fileReferenceReader,
            sessionMemoryCompactionCoordinator = sessionMemoryCompactionCoordinator,
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
    var showThinkingIndicatorCalls: Int = 0
        private set
    var updateThinkingIndicatorCalls: Int = 0
        private set
    var lastThinkingProgressText: String? = null
        private set
    var hideThinkingIndicatorCalls: Int = 0
        private set

    override fun clearScreen() = Unit

    override fun hideCursor() = Unit

    override fun showCursor() = Unit

    override fun writeLine(text: String) {
        lines += text
    }

    override fun readLine(prompt: String): String? = nextInput()

    override fun readLineInFooter(prompt: String, divider: String, systemPromptText: String): String? = nextInput()

    override fun showThinkingIndicator() {
        showThinkingIndicatorCalls += 1
    }

    override fun updateThinkingIndicator(progressText: String) {
        updateThinkingIndicatorCalls += 1
        lastThinkingProgressText = progressText
    }

    override fun hideThinkingIndicator() {
        hideThinkingIndicatorCalls += 1
    }

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
            compactedSummary = loadedState.compactedSummary?.copy(),
            usage = loadedState.usage?.copy(),
        )
    }

    override fun save(state: SessionMemoryState) {
        saveStates += SessionMemoryState(
            messages = state.messages.toList(),
            compactedSummary = state.compactedSummary?.copy(),
            usage = state.usage?.copy(),
        )
    }

    override fun clear() {
        clearCalls += 1
    }
}

private class RecordingFileReferenceReader(
    private val contentsByPath: Map<String, String>,
) : FileReferenceReader {
    val readPaths = mutableListOf<String>()

    override fun read(path: String): String {
        readPaths += path
        return contentsByPath[path]
            ?: throw IllegalStateException("No prepared file content for '$path'.")
    }
}

private class RecordingCompactionStrategy(
    private val summariesToReturn: List<String>,
) : SessionCompactionStrategy {
    private val queuedSummaries = ArrayDeque(summariesToReturn)
    val previousSummaries = mutableListOf<String?>()
    val compactedMessageBatches = mutableListOf<List<ConversationMessage>>()

    override val id: String = "rolling-summary-v1"

    override suspend fun compact(
        previousSummary: String?,
        messagesToCompact: List<ConversationMessage>,
        model: String,
    ): String {
        previousSummaries += previousSummary
        compactedMessageBatches += messagesToCompact
        return queuedSummaries.removeFirstOrNull()
            ?: error("No prepared summary for compaction call #${previousSummaries.size}")
    }
}
