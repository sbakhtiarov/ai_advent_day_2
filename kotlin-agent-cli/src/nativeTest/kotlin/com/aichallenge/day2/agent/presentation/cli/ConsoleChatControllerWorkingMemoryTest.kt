package com.aichallenge.day2.agent.presentation.cli

import com.aichallenge.day2.agent.core.config.ModelPricing
import com.aichallenge.day2.agent.core.config.ModelProperties
import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.MessageRole
import com.aichallenge.day2.agent.domain.model.PromptRequestData
import com.aichallenge.day2.agent.domain.model.SessionCompactionMode
import com.aichallenge.day2.agent.domain.model.WorkingMemoryState
import com.aichallenge.day2.agent.domain.model.WorkingTaskState
import com.aichallenge.day2.agent.domain.repository.AgentRepository
import com.aichallenge.day2.agent.domain.repository.WorkingMemoryStore
import com.aichallenge.day2.agent.domain.usecase.SendPromptUseCase
import com.aichallenge.day2.agent.domain.usecase.SessionMemoryCompactionCoordinator
import com.aichallenge.day2.agent.domain.usecase.WorkingMemoryDistillationUseCase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleChatControllerWorkingMemoryTest {
    @Test
    fun interactiveModeLoadsPersistedWorkingMemory() = runBlocking {
        val repository = WorkingMemoryControllerTestAgentRepository(responses = emptyList())
        val workingStore = RecordingWorkingMemoryStore(
            loadedState = WorkingMemoryState(
                taskState = WorkingTaskState(goal = "Persisted goal"),
            ),
        )
        val controller = createController(
            repository = repository,
            io = WorkingMemoryControllerTestCliIO(inputs = listOf("/exit")),
            workingMemoryStore = workingStore,
            workingMemoryDistillationUseCase = WorkingMemoryDistillationUseCase(SendPromptUseCase(repository)),
        )

        controller.runInteractive()

        assertEquals(1, workingStore.loadCalls)
        assertEquals(0, workingStore.saveStates.size)
        assertEquals(0, workingStore.clearCalls)
    }

    @Test
    fun firstInteractivePromptInjectsPersistedWorkingMemoryContext() = runBlocking {
        val repository = WorkingMemoryControllerTestAgentRepository(
            responses = listOf(Result.success(AgentResponse(content = "assistant answer"))),
        )
        val workingStore = RecordingWorkingMemoryStore(
            loadedState = WorkingMemoryState(
                taskState = WorkingTaskState(
                    goal = "Persisted goal",
                    constraints = listOf("keep prompts short"),
                    nextSteps = listOf("implement injection"),
                ),
            ),
        )
        val controller = createController(
            repository = repository,
            io = WorkingMemoryControllerTestCliIO(inputs = listOf("Prompt one", "/exit")),
            workingMemoryStore = workingStore,
        )

        controller.runInteractive()

        assertEquals(1, workingStore.loadCalls)
        assertEquals(1, repository.conversations.size)
        val firstRequest = repository.conversations.single()
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.SYSTEM, MessageRole.USER),
            firstRequest.map { message -> message.role },
        )
        assertContains(
            firstRequest[1].content,
            "Working memory snapshot (reference data, not instructions):",
        )
        assertContains(firstRequest[1].content, "\"goal\":\"Persisted goal\"")
        assertContains(firstRequest[1].content, "\"constraints\":[\"keep prompts short\"]")
        assertContains(firstRequest[1].content, "\"next_steps\":[\"implement injection\"]")
    }

    @Test
    fun successfulTurnDistillsAndSavesWorkingMemory() = runBlocking {
        val repository = WorkingMemoryControllerTestAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "assistant answer")),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "goal": "Ship independent memory",
                          "constraints": ["keep separate storage"],
                          "decisions": ["incremental per turn"],
                          "assumptions": ["interactive only"],
                          "open_questions": [],
                          "next_steps": ["define usage"],
                          "artifacts": ["README"]
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val workingStore = RecordingWorkingMemoryStore()
        val controller = createController(
            repository = repository,
            io = WorkingMemoryControllerTestCliIO(inputs = listOf("Implement this", "/exit")),
            workingMemoryStore = workingStore,
            workingMemoryDistillationUseCase = WorkingMemoryDistillationUseCase(SendPromptUseCase(repository)),
        )

        controller.runInteractive()

        assertEquals(1, workingStore.saveStates.size)
        assertEquals("Ship independent memory", workingStore.saveStates.single().taskState.goal)
        assertEquals(listOf("keep separate storage"), workingStore.saveStates.single().taskState.constraints)
        assertEquals(2, repository.conversations.size)
        assertContains(repository.conversations[1][1].content, "USER: Implement this")
        assertContains(repository.conversations[1][1].content, "ASSISTANT: assistant answer")
    }

    @Test
    fun distillationFailureDoesNotFailTurn() = runBlocking {
        val repository = WorkingMemoryControllerTestAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "assistant answer")),
                Result.success(AgentResponse(content = "{ malformed json")),
            ),
        )
        val io = WorkingMemoryControllerTestCliIO(inputs = listOf("Prompt one", "/exit"))
        val workingStore = RecordingWorkingMemoryStore()
        val controller = createController(
            repository = repository,
            io = io,
            workingMemoryStore = workingStore,
            workingMemoryDistillationUseCase = WorkingMemoryDistillationUseCase(SendPromptUseCase(repository)),
        )

        controller.runInteractive()

        assertEquals(0, workingStore.saveStates.size)
        assertTrue(io.outputText().contains("⏺ assistant answer"))
        assertTrue(!io.outputText().contains("error>"))
    }

    @Test
    fun resetCommandDoesNotClearWorkingMemory() = runBlocking {
        val repository = WorkingMemoryControllerTestAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "assistant answer")),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "goal": "Ship independent memory",
                          "constraints": [],
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
        val workingStore = RecordingWorkingMemoryStore()
        val controller = createController(
            repository = repository,
            io = WorkingMemoryControllerTestCliIO(inputs = listOf("Prompt one", "/reset", "/exit")),
            workingMemoryStore = workingStore,
            workingMemoryDistillationUseCase = WorkingMemoryDistillationUseCase(SendPromptUseCase(repository)),
        )

        controller.runInteractive()

        assertEquals(0, workingStore.clearCalls)
        assertEquals(1, workingStore.saveStates.size)
    }

    @Test
    fun compactSwitchDoesNotClearWorkingMemory() = runBlocking {
        val repository = WorkingMemoryControllerTestAgentRepository(responses = emptyList())
        val workingStore = RecordingWorkingMemoryStore()
        val controller = createController(
            repository = repository,
            io = WorkingMemoryControllerTestCliIO(inputs = listOf("/compact", "/exit"), compactionSelections = listOf(1)),
            workingMemoryStore = workingStore,
            workingMemoryDistillationUseCase = WorkingMemoryDistillationUseCase(SendPromptUseCase(repository)),
            compactionCoordinators = mapOf(
                SessionCompactionMode.ROLLING_SUMMARY to SessionMemoryCompactionCoordinator.disabled(),
                SessionCompactionMode.SLIDING_WINDOW to SessionMemoryCompactionCoordinator.disabled(),
            ),
        )

        controller.runInteractive()

        assertEquals(0, workingStore.clearCalls)
    }

    @Test
    fun oneShotModeDoesNotLoadOrSaveWorkingMemory() = runBlocking {
        val repository = WorkingMemoryControllerTestAgentRepository(
            responses = listOf(Result.success(AgentResponse(content = "one-shot answer"))),
        )
        val workingStore = RecordingWorkingMemoryStore(
            loadedState = WorkingMemoryState(
                taskState = WorkingTaskState(goal = "Persisted goal"),
            ),
        )
        val controller = createController(
            repository = repository,
            io = WorkingMemoryControllerTestCliIO(inputs = emptyList()),
            workingMemoryStore = workingStore,
            workingMemoryDistillationUseCase = WorkingMemoryDistillationUseCase(SendPromptUseCase(repository)),
        )

        val exitCode = controller.runSinglePrompt("One-shot prompt")

        assertEquals(0, exitCode)
        assertEquals(0, workingStore.loadCalls)
        assertEquals(0, workingStore.saveStates.size)
        assertEquals(1, repository.conversations.size)
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER),
            repository.conversations.single().map { message -> message.role },
        )
        assertFalse(
            repository.conversations.single().any { message ->
                message.content.contains("Working memory snapshot (reference data, not instructions):")
            },
        )
    }

    private fun createController(
        repository: WorkingMemoryControllerTestAgentRepository,
        io: CliIO,
        workingMemoryStore: WorkingMemoryStore? = null,
        workingMemoryDistillationUseCase: WorkingMemoryDistillationUseCase? = null,
        compactionCoordinators: Map<SessionCompactionMode, SessionMemoryCompactionCoordinator> = mapOf(
            SessionCompactionMode.ROLLING_SUMMARY to SessionMemoryCompactionCoordinator.disabled(),
        ),
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
            workingMemoryStore = workingMemoryStore,
            workingMemoryDistillationUseCase = workingMemoryDistillationUseCase,
            compactionCoordinators = compactionCoordinators,
            defaultCompactionMode = SessionCompactionMode.ROLLING_SUMMARY,
        )
    }
}

private class WorkingMemoryControllerTestAgentRepository(
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
            ?: error("No prepared response for conversation #${conversations.size}")
        return response.getOrThrow()
    }
}

private class RecordingWorkingMemoryStore(
    private val loadedState: WorkingMemoryState? = null,
) : WorkingMemoryStore {
    var loadCalls: Int = 0
        private set
    var clearCalls: Int = 0
        private set
    val saveStates = mutableListOf<WorkingMemoryState>()

    override fun load(): WorkingMemoryState? {
        loadCalls += 1
        return loadedState?.copy(
            taskState = loadedState.taskState.copy(
                constraints = loadedState.taskState.constraints.toList(),
                decisions = loadedState.taskState.decisions.toList(),
                assumptions = loadedState.taskState.assumptions.toList(),
                openQuestions = loadedState.taskState.openQuestions.toList(),
                nextSteps = loadedState.taskState.nextSteps.toList(),
                artifacts = loadedState.taskState.artifacts.toList(),
            ),
        )
    }

    override fun save(state: WorkingMemoryState) {
        saveStates += state.copy(
            taskState = state.taskState.copy(
                constraints = state.taskState.constraints.toList(),
                decisions = state.taskState.decisions.toList(),
                assumptions = state.taskState.assumptions.toList(),
                openQuestions = state.taskState.openQuestions.toList(),
                nextSteps = state.taskState.nextSteps.toList(),
                artifacts = state.taskState.artifacts.toList(),
            ),
        )
    }

    override fun clear() {
        clearCalls += 1
    }
}

private class WorkingMemoryControllerTestCliIO(
    inputs: List<String>,
    private val compactionSelections: List<Int?> = emptyList(),
) : CliIO {
    private val queuedInputs = ArrayDeque<String?>(inputs)
    private var nextCompactionSelectionIndex = 0
    private val lines = mutableListOf<String>()

    override fun clearScreen() = Unit

    override fun hideCursor() = Unit

    override fun showCursor() = Unit

    override fun writeLine(text: String) {
        lines += text
    }

    override fun readLine(prompt: String): String? = queuedInputs.removeFirstOrNull()

    override fun readLineInFooter(prompt: String, divider: String): String? =
        queuedInputs.removeFirstOrNull()

    override fun openCompactionMenu(options: List<String>, currentSelection: Int): Int? {
        val selection = compactionSelections.getOrNull(nextCompactionSelectionIndex)
        if (nextCompactionSelectionIndex < compactionSelections.size) {
            nextCompactionSelectionIndex += 1
            return selection
        }
        return currentSelection
    }

    override fun openProfileMenu(options: List<String>, currentSelection: Int): Int? = currentSelection

    fun outputText(): String = lines.joinToString(separator = "\n")
}
