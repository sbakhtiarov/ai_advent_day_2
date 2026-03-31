package com.aichallenge.day2.agent.presentation.cli

import com.aichallenge.day2.agent.core.config.ApiSettings
import com.aichallenge.day2.agent.core.config.ApiSettingsService
import com.aichallenge.day2.agent.core.config.ConfiguredApi
import com.aichallenge.day2.agent.core.config.ModelPricing
import com.aichallenge.day2.agent.core.config.ModelProperties
import com.aichallenge.day2.agent.core.config.MutableApiSettingsService
import com.aichallenge.day2.agent.data.tools.BuiltInPrivateToolProvider
import com.aichallenge.day2.agent.data.tools.BuiltInToolDefinition
import com.aichallenge.day2.agent.data.tools.BuiltInToolRegistration
import com.aichallenge.day2.agent.data.tools.BuiltInToolRegistry
import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.BranchingMemoryState
import com.aichallenge.day2.agent.domain.model.CompactedSessionSummary
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.MemoryEstimateSource
import com.aichallenge.day2.agent.domain.model.MemoryUsageSnapshot
import com.aichallenge.day2.agent.domain.model.MessageRole
import com.aichallenge.day2.agent.domain.model.McpServerConfig
import com.aichallenge.day2.agent.domain.model.McpRuntimeStatus
import com.aichallenge.day2.agent.domain.model.McpServerRuntimeState
import com.aichallenge.day2.agent.domain.model.McpToolCallResult
import com.aichallenge.day2.agent.domain.model.McpToolCatalogState
import com.aichallenge.day2.agent.domain.model.McpToolCatalogStatus
import com.aichallenge.day2.agent.domain.model.McpToolDefinition
import com.aichallenge.day2.agent.domain.model.McpTransportConfig
import com.aichallenge.day2.agent.domain.model.PrivateToolResult
import com.aichallenge.day2.agent.domain.model.PrivateToolTarget
import com.aichallenge.day2.agent.domain.model.PromptRequestData
import com.aichallenge.day2.agent.domain.model.RagRetrievedChunk
import com.aichallenge.day2.agent.domain.model.ProfilePreferenceState
import com.aichallenge.day2.agent.domain.model.RollingWindowCompactionStartPolicy
import com.aichallenge.day2.agent.domain.model.SessionCompactionMode
import com.aichallenge.day2.agent.domain.model.SessionCompactionSummaryMode
import com.aichallenge.day2.agent.domain.model.SessionCompactionStrategy
import com.aichallenge.day2.agent.domain.model.SessionMemoryState
import com.aichallenge.day2.agent.domain.model.SlidingWindowCompactionStartPolicy
import com.aichallenge.day2.agent.domain.model.SubtopicBranchState
import com.aichallenge.day2.agent.domain.model.TokenUsage
import com.aichallenge.day2.agent.domain.model.TopicBranchState
import com.aichallenge.day2.agent.domain.model.ToolCallTraceEvent
import com.aichallenge.day2.agent.domain.model.ToolCallTraceObserver
import com.aichallenge.day2.agent.domain.model.UserProfileOption
import com.aichallenge.day2.agent.domain.model.UserWorkflowDefinition
import com.aichallenge.day2.agent.domain.model.UserWorkflowOption
import com.aichallenge.day2.agent.domain.model.WorkflowRuntimeState
import com.aichallenge.day2.agent.domain.model.WorkflowStep
import com.aichallenge.day2.agent.domain.repository.AgentRepository
import com.aichallenge.day2.agent.domain.repository.ApiSettingsStore
import com.aichallenge.day2.agent.domain.repository.InvariantConstraintStore
import com.aichallenge.day2.agent.domain.repository.McpServerStore
import com.aichallenge.day2.agent.domain.repository.SessionMemoryStore
import com.aichallenge.day2.agent.domain.repository.UserDefinedProfileStore
import com.aichallenge.day2.agent.domain.repository.UserDefinedWorkflowStore
import com.aichallenge.day2.agent.domain.service.McpConnectedSession
import com.aichallenge.day2.agent.domain.service.McpRuntimeService
import com.aichallenge.day2.agent.domain.service.WireAppRagRetriever
import com.aichallenge.day2.agent.domain.usecase.SessionMemoryCompactionCoordinator
import com.aichallenge.day2.agent.domain.usecase.SendPromptUseCase
import com.aichallenge.day2.agent.domain.usecase.SlidingWindowCompactionStrategy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        assertContains(repository.conversations.single()[0].content, "Base system prompt")
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
    fun llmToolCallsAreShownLiveAndPersistedBeforeAssistantResponse() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "answer one")),
            ),
            toolTraceEvents = listOf(
                listOf(
                    ToolCallTraceEvent.Started(toolLabel = "built-in 'scheduler'"),
                ),
            ),
        )
        val io = FakeCliIO(inputs = listOf("prompt one", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
        )

        controller.runInteractive()

        assertEquals(
            listOf("system> tool call: built-in 'scheduler'"),
            io.liveDialogLines,
        )
        val output = io.outputText()
        assertContains(output, "system> tool call: built-in 'scheduler'")
        assertTrue(output.indexOf("system> tool call: built-in 'scheduler'") < output.indexOf("⏺ answer one"))
    }

    @Test
    fun sequentialLlmToolCallsAreShownInOrder() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "answer one")),
            ),
            toolTraceEvents = listOf(
                listOf(
                    ToolCallTraceEvent.Started(toolLabel = "MCP 'Linear/search_issues'"),
                    ToolCallTraceEvent.Started(toolLabel = "built-in 'save_to_file'"),
                ),
            ),
        )
        val io = FakeCliIO(inputs = listOf("prompt one", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
        )

        controller.runInteractive()

        assertEquals(
            listOf(
                "system> tool call: MCP 'Linear/search_issues'",
                "system> tool call: built-in 'save_to_file'",
            ),
            io.liveDialogLines,
        )
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
            listOf(MessageRole.USER, MessageRole.ASSISTANT),
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
        assertEquals(1, store.clearCalls)
        assertEquals(3, store.saveStates.size)
        assertEquals(emptyList(), store.saveStates[1].messages)
        assertFalse(store.saveStates[1].workflowModeEnabled)
        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT),
            store.saveStates[2].messages.map { it.role },
        )
        val usage = assertNotNull(store.saveStates[2].usage)
        assertEquals(MemoryEstimateSource.HEURISTIC, usage.source)
        assertEquals(3, usage.messageCount)
        assertEquals("prompt two", store.saveStates[2].messages[0].content)
    }

    @Test
    fun workflowCommandWhenDisabledSelectsWorkflowEnablesAndPersistsSnapshot() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val store = RecordingSessionMemoryStore()
        val workflowStore = RecordingSelectableUserDefinedWorkflowStore(
            workflows = listOf(
                UserWorkflowOption("workflow-default.json", "Default"),
                UserWorkflowOption("workflow-review.json", "Review"),
            ),
            workflowsByFileName = mapOf(
                "workflow-default.json" to UserWorkflowDefinition(
                    fileName = "workflow-default.json",
                    name = "Default",
                    planning = "Plan",
                    execution = "Execute",
                    validation = "Validate",
                ),
                "workflow-review.json" to UserWorkflowDefinition(
                    fileName = "workflow-review.json",
                    name = "Review",
                    planning = "Plan review",
                    execution = "Run review",
                    validation = "Confirm review",
                ),
            ),
            activeFileName = "workflow-default.json",
        )
        val io = FakeCliIO(
            inputs = listOf("/workflow", "/exit"),
            workflowSelections = listOf(1),
        )
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = store,
            userDefinedWorkflowStore = workflowStore,
        )

        controller.runInteractive()

        assertEquals(1, store.saveStates.size)
        assertTrue(store.saveStates.single().workflowModeEnabled)
        assertEquals(listOf("workflow-review.json"), workflowStore.setActiveCalls)
    }

    @Test
    fun workflowCommandSelectingDifferentWorkflowResetsConversationAndPersistsSnapshot() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "plan one")),
                Result.success(AgentResponse(content = "execution one")),
                Result.success(
                    AgentResponse(
                        content = """{"status":"PASS","summary":"validated","details":"ok"}""",
                    ),
                ),
            ),
        )
        val sessionStore = RecordingSessionMemoryStore(
            loadedState = SessionMemoryState(
                messages = listOf(
                    ConversationMessage.user("old question"),
                    ConversationMessage.assistant("old answer"),
                ),
                usage = MemoryUsageSnapshot(
                    estimatedTokens = 200,
                    source = MemoryEstimateSource.HYBRID,
                    messageCount = 3,
                ),
            ),
        )
        val workflowStore = RecordingSelectableUserDefinedWorkflowStore(
            workflows = listOf(
                UserWorkflowOption("workflow-default.json", "Default"),
                UserWorkflowOption("workflow-review.json", "Review"),
            ),
            workflowsByFileName = mapOf(
                "workflow-default.json" to UserWorkflowDefinition(
                    fileName = "workflow-default.json",
                    name = "Default",
                    planning = "Plan",
                    execution = "Execute",
                    validation = "Validate",
                ),
                "workflow-review.json" to UserWorkflowDefinition(
                    fileName = "workflow-review.json",
                    name = "Review",
                    planning = "Plan review",
                    execution = "Run review",
                    validation = "Confirm review",
                ),
            ),
            activeFileName = "workflow-default.json",
        )
        val io = FakeCliIO(
            inputs = listOf("/workflow", "new question", "1", "1", "/exit"),
            workflowSelections = listOf(1),
        )
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = sessionStore,
            userDefinedWorkflowStore = workflowStore,
        )

        controller.runInteractive()

        assertEquals(listOf("workflow-review.json"), workflowStore.setActiveCalls)
        assertTrue(sessionStore.saveStates.size >= 2)
        assertTrue(sessionStore.saveStates[0].workflowModeEnabled)
        assertEquals(emptyList(), sessionStore.saveStates[0].messages)
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER),
            repository.conversations.first().map { it.role },
        )
        assertContains(repository.conversations.first()[1].content, "new question")
    }

    @Test
    fun workflowCommandPersistsSelectionWithoutResetWhenPersistedActiveWorkflowIsMissing() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "plan one")),
                Result.success(AgentResponse(content = "execution one")),
                Result.success(
                    AgentResponse(
                        content = """{"status":"PASS","summary":"validated","details":"ok"}""",
                    ),
                ),
            ),
        )
        val sessionStore = RecordingSessionMemoryStore(
            loadedState = SessionMemoryState(
                messages = listOf(
                    ConversationMessage.user("old question"),
                    ConversationMessage.assistant("old answer"),
                ),
                usage = MemoryUsageSnapshot(
                    estimatedTokens = 200,
                    source = MemoryEstimateSource.HYBRID,
                    messageCount = 3,
                ),
            ),
        )
        val workflowStore = RecordingSelectableUserDefinedWorkflowStore(
            workflows = listOf(
                UserWorkflowOption("workflow-default.json", "Default"),
                UserWorkflowOption("workflow-review.json", "Review"),
            ),
            workflowsByFileName = mapOf(
                "workflow-default.json" to UserWorkflowDefinition(
                    fileName = "workflow-default.json",
                    name = "Default",
                    planning = "Plan",
                    execution = "Execute",
                    validation = "Validate",
                ),
                "workflow-review.json" to UserWorkflowDefinition(
                    fileName = "workflow-review.json",
                    name = "Review",
                    planning = "Plan review",
                    execution = "Run review",
                    validation = "Confirm review",
                ),
            ),
            activeFileName = null,
        )
        val io = FakeCliIO(
            inputs = listOf("/workflow", "new question", "1", "1", "/exit"),
            workflowSelections = listOf(0),
        )
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = sessionStore,
            userDefinedWorkflowStore = workflowStore,
        )

        controller.runInteractive()

        assertEquals(listOf("workflow-default.json"), workflowStore.setActiveCalls)
        val request = repository.conversations.first()
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER),
            request.map { it.role },
        )
        assertContains(request[1].content, "new question")
        assertFalse(request[1].content.contains("old question"))
        assertFalse(request[1].content.contains("old answer"))
    }

    @Test
    fun workflowCommandCancelKeepsDisabledAndDoesNotPersistSnapshot() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val store = RecordingSessionMemoryStore()
        val workflowStore = RecordingSelectableUserDefinedWorkflowStore(
            workflows = listOf(
                UserWorkflowOption("workflow-default.json", "Default"),
            ),
            workflowsByFileName = mapOf(
                "workflow-default.json" to UserWorkflowDefinition(
                    fileName = "workflow-default.json",
                    name = "Default",
                    planning = "Plan",
                    execution = "Execute",
                    validation = "Validate",
                ),
            ),
            activeFileName = "workflow-default.json",
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = listOf("/workflow", "/exit"), workflowSelections = listOf(null)),
            sessionMemoryStore = store,
            userDefinedWorkflowStore = workflowStore,
        )

        controller.runInteractive()

        assertEquals(emptyList(), workflowStore.setActiveCalls)
        assertEquals(0, store.saveStates.size)
    }

    @Test
    fun workflowCommandShowsMessageWhenNoValidWorkflowsFound() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val io = FakeCliIO(inputs = listOf("/workflow", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            userDefinedWorkflowStore = RecordingSelectableUserDefinedWorkflowStore(
                workflows = emptyList(),
            ),
        )

        controller.runInteractive()

        assertContains(io.outputText(), "system> no valid workflows found")
    }

    @Test
    fun workflowCommandWhenEnabledDisablesAndPersistsSnapshot() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val store = RecordingSessionMemoryStore()
        val workflowStore = RecordingSelectableUserDefinedWorkflowStore(
            workflows = listOf(
                UserWorkflowOption("workflow-default.json", "Default"),
            ),
            workflowsByFileName = mapOf(
                "workflow-default.json" to UserWorkflowDefinition(
                    fileName = "workflow-default.json",
                    name = "Default",
                    planning = "Plan",
                    execution = "Execute",
                    validation = "Validate",
                ),
            ),
            activeFileName = "workflow-default.json",
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(
                inputs = listOf("/workflow", "/workflow", "/exit"),
                workflowSelections = listOf(0),
            ),
            sessionMemoryStore = store,
            userDefinedWorkflowStore = workflowStore,
        )

        controller.runInteractive()

        assertEquals(2, store.saveStates.size)
        assertFalse(store.saveStates.last().workflowModeEnabled)
    }

    @Test
    fun interactiveModeRestoresWorkflowModeAndPassesFooterLabel() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val io = FakeCliIO(inputs = listOf("/exit"))
        val store = RecordingSessionMemoryStore(
            loadedState = SessionMemoryState(
                messages = emptyList(),
                workflowModeEnabled = true,
            ),
        )
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = store,
        )

        controller.runInteractive()

        assertEquals(1, store.loadCalls)
        assertEquals(listOf<String?>("Workflow: user input"), io.footerLabels)
    }

    @Test
    fun resetCommandPreservesWorkflowModeInPersistedSnapshot() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val store = RecordingSessionMemoryStore()
        val workflowStore = RecordingSelectableUserDefinedWorkflowStore(
            workflows = listOf(
                UserWorkflowOption("workflow-default.json", "Default"),
            ),
            workflowsByFileName = mapOf(
                "workflow-default.json" to UserWorkflowDefinition(
                    fileName = "workflow-default.json",
                    name = "Default",
                    planning = "Plan",
                    execution = "Execute",
                    validation = "Validate",
                ),
            ),
            activeFileName = "workflow-default.json",
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = listOf("/workflow", "/reset", "/exit"), workflowSelections = listOf(0)),
            sessionMemoryStore = store,
            userDefinedWorkflowStore = workflowStore,
        )

        controller.runInteractive()

        assertEquals(1, store.clearCalls)
        assertEquals(2, store.saveStates.size)
        assertTrue(store.saveStates[0].workflowModeEnabled)
        assertTrue(store.saveStates[1].workflowModeEnabled)
        assertEquals(emptyList(), store.saveStates[1].messages)
        val resetRuntime = assertNotNull(store.saveStates[1].workflowRuntimeState)
        assertEquals(WorkflowStep.USER_INPUT, resetRuntime.step)
        assertEquals("", resetRuntime.originalUserPrompt)
        assertEquals(emptyList(), resetRuntime.planningFeedback)
        assertEquals(emptyList(), resetRuntime.executionFeedback)
        assertEquals(null, resetRuntime.latestPlanningOutput)
        assertEquals(null, resetRuntime.approvedPlan)
        assertEquals(null, resetRuntime.latestExecutionOutput)
    }

    @Test
    fun workflowRunUsesStepSpecificPromptStackAndCompletesOnValidationPass() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "planning output")),
                Result.success(AgentResponse(content = "execution output")),
                Result.success(AgentResponse(content = """{"status":"PASS","summary":"all good","details":"validated"}""")),
            ),
        )
        val store = RecordingSessionMemoryStore()
        val workflowStore = RecordingSelectableUserDefinedWorkflowStore(
            workflows = listOf(
                UserWorkflowOption("workflow-default.json", "Default"),
            ),
            workflowsByFileName = mapOf(
                "workflow-default.json" to UserWorkflowDefinition(
                    fileName = "workflow-default.json",
                    name = "Default",
                    basePrompt = "Workflow base prompt",
                    planning = "Planning step prompt",
                    execution = "Execution step prompt",
                    validation = "Validation step prompt",
                ),
            ),
            activeFileName = "workflow-default.json",
        )
        val io = FakeCliIO(inputs = listOf("/workflow", "build feature", "1", "1", "/exit"), workflowSelections = listOf(0))
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = store,
            userDefinedWorkflowStore = workflowStore,
        )

        controller.runInteractive()

        assertEquals(3, repository.conversations.size)
        val planningRequest = repository.conversations[0]
        val executionRequest = repository.conversations[1]
        val validationRequest = repository.conversations[2]
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER),
            planningRequest.map { it.role },
        )
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER),
            executionRequest.map { it.role },
        )
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER),
            validationRequest.map { it.role },
        )

        assertContains(planningRequest[0].content, "Base system prompt")
        assertContains(planningRequest[0].content, "Workflow base prompt")
        assertContains(planningRequest[0].content, "Planning step prompt")
        assertContains(planningRequest.last().content, "build feature")

        assertContains(executionRequest[0].content, "Execution step prompt")
        assertContains(validationRequest[0].content, "Validation step prompt")
        assertContains(validationRequest[0].content, "\"status\":\"PASS|FAIL\"")
        val output = io.outputText()
        assertContains(
            output,
            "Planning result approval:\n1. Approve\n2. Cancel\nComment (type feedback)",
        )
        assertContains(
            output,
            "Execution result approval:\n1. Approve\n2. Cancel\nComment (type feedback to re-plan)",
        )
        assertFalse(output.contains("workflow> planning:"))
        assertFalse(output.contains("workflow> execution:"))

        val finalState = store.saveStates.last()
        assertFalse(finalState.workflowModeEnabled)
        assertNull(finalState.workflowRuntimeState)
    }

    @Test
    fun planningCommentRerunsPlanningWithCommentInPrompt() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "planning output one")),
                Result.success(AgentResponse(content = "planning output two")),
                Result.success(AgentResponse(content = "execution output")),
                Result.success(AgentResponse(content = """{"status":"PASS","summary":"ok","details":"ok"}""")),
            ),
        )
        val workflowStore = RecordingSelectableUserDefinedWorkflowStore(
            workflows = listOf(UserWorkflowOption("workflow-default.json", "Default")),
            workflowsByFileName = mapOf(
                "workflow-default.json" to UserWorkflowDefinition(
                    fileName = "workflow-default.json",
                    name = "Default",
                    planning = "Planning step",
                    execution = "Execution step",
                    validation = "Validation step",
                ),
            ),
            activeFileName = "workflow-default.json",
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(
                inputs = listOf("/workflow", "implement thing", "add rollback section", "1", "1", "/exit"),
                workflowSelections = listOf(0),
            ),
            userDefinedWorkflowStore = workflowStore,
        )

        controller.runInteractive()

        assertEquals(4, repository.conversations.size)
        val secondPlanningRequest = repository.conversations[1]
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER),
            secondPlanningRequest.map { it.role },
        )
        assertContains(secondPlanningRequest[0].content, "Planning step")
        assertContains(secondPlanningRequest.last().content, "add rollback section")
    }

    @Test
    fun executionCommentRoutesBackToPlanningWithFeedback() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "planning output one")),
                Result.success(AgentResponse(content = "execution output one")),
                Result.success(AgentResponse(content = "planning output two")),
                Result.success(AgentResponse(content = "execution output two")),
                Result.success(AgentResponse(content = """{"status":"PASS","summary":"ok","details":"ok"}""")),
            ),
        )
        val workflowStore = RecordingSelectableUserDefinedWorkflowStore(
            workflows = listOf(UserWorkflowOption("workflow-default.json", "Default")),
            workflowsByFileName = mapOf(
                "workflow-default.json" to UserWorkflowDefinition(
                    fileName = "workflow-default.json",
                    name = "Default",
                    planning = "Planning step",
                    execution = "Execution step",
                    validation = "Validation step",
                ),
            ),
            activeFileName = "workflow-default.json",
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(
                inputs = listOf("/workflow", "task", "1", "please refine plan", "1", "1", "/exit"),
                workflowSelections = listOf(0),
            ),
            userDefinedWorkflowStore = workflowStore,
        )

        controller.runInteractive()

        assertEquals(5, repository.conversations.size)
        val replanningRequest = repository.conversations[2]
        assertContains(replanningRequest[0].content, "Planning step")
        assertContains(replanningRequest.last().content, "please refine plan")
    }

    @Test
    fun validationFailRerunsExecutionWithValidationFeedback() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "planning output")),
                Result.success(AgentResponse(content = "execution output one")),
                Result.success(AgentResponse(content = """{"status":"FAIL","summary":"missing test evidence","details":"need screenshots"}""")),
                Result.success(AgentResponse(content = "execution output two")),
                Result.success(AgentResponse(content = """{"status":"PASS","summary":"ok","details":"ok"}""")),
            ),
        )
        val workflowStore = RecordingSelectableUserDefinedWorkflowStore(
            workflows = listOf(UserWorkflowOption("workflow-default.json", "Default")),
            workflowsByFileName = mapOf(
                "workflow-default.json" to UserWorkflowDefinition(
                    fileName = "workflow-default.json",
                    name = "Default",
                    planning = "Planning step",
                    execution = "Execution step",
                    validation = "Validation step",
                ),
            ),
            activeFileName = "workflow-default.json",
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = listOf("/workflow", "task", "1", "1", "1", "/exit"), workflowSelections = listOf(0)),
            userDefinedWorkflowStore = workflowStore,
        )

        controller.runInteractive()

        assertEquals(5, repository.conversations.size)
        val retriedExecutionRequest = repository.conversations[3]
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER),
            retriedExecutionRequest.map { it.role },
        )
        assertContains(retriedExecutionRequest[0].content, "Execution step")
        assertContains(retriedExecutionRequest.last().content, "Validation failed: missing test evidence")
    }

    @Test
    fun invalidValidationJsonIsTreatedAsFailAndRerunsExecution() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "planning output")),
                Result.success(AgentResponse(content = "execution output one")),
                Result.success(AgentResponse(content = "not-json-validation-output")),
                Result.success(AgentResponse(content = "execution output two")),
                Result.success(AgentResponse(content = """{"status":"PASS","summary":"ok","details":"ok"}""")),
            ),
        )
        val workflowStore = RecordingSelectableUserDefinedWorkflowStore(
            workflows = listOf(UserWorkflowOption("workflow-default.json", "Default")),
            workflowsByFileName = mapOf(
                "workflow-default.json" to UserWorkflowDefinition(
                    fileName = "workflow-default.json",
                    name = "Default",
                    planning = "Planning step",
                    execution = "Execution step",
                    validation = "Validation step",
                ),
            ),
            activeFileName = "workflow-default.json",
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = listOf("/workflow", "task", "1", "1", "1", "/exit"), workflowSelections = listOf(0)),
            userDefinedWorkflowStore = workflowStore,
        )

        controller.runInteractive()

        assertEquals(5, repository.conversations.size)
        val retriedExecutionRequest = repository.conversations[3]
        assertContains(retriedExecutionRequest.last().content, "not valid JSON")
    }

    @Test
    fun validationJsonInsideCodeFenceIsParsedAsPass() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "planning output")),
                Result.success(AgentResponse(content = "execution output")),
                Result.success(
                    AgentResponse(
                        content = """
                        ```json
                        {"status":"PASS","summary":"all checks passed","details":"validator accepted output"}
                        ```
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val store = RecordingSessionMemoryStore()
        val io = FakeCliIO(inputs = listOf("/workflow", "task", "1", "1", "/exit"), workflowSelections = listOf(0))
        val workflowStore = RecordingSelectableUserDefinedWorkflowStore(
            workflows = listOf(UserWorkflowOption("workflow-default.json", "Default")),
            workflowsByFileName = mapOf(
                "workflow-default.json" to UserWorkflowDefinition(
                    fileName = "workflow-default.json",
                    name = "Default",
                    planning = "Planning step",
                    execution = "Execution step",
                    validation = "Validation step",
                ),
            ),
            activeFileName = "workflow-default.json",
        )
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = store,
            userDefinedWorkflowStore = workflowStore,
        )

        controller.runInteractive()

        assertEquals(3, repository.conversations.size)
        val finalState = store.saveStates.last()
        assertFalse(finalState.workflowModeEnabled)
        assertNull(finalState.workflowRuntimeState)
        val output = io.outputText()
        assertContains(output, "Validation status: PASS")
        assertContains(output, "workflow> completed")
        assertFalse(output.contains("Validation output is not valid JSON"))
    }

    @Test
    fun planningStructuredQuestionsAreAskedOneByOneAndFedBackIntoPlanning() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "needs_user_input": true,
                          "questions": [
                            {
                              "question": "Who is the target audience?",
                              "options": [
                                "Backend engineers",
                                "Product managers"
                              ]
                            },
                            {
                              "question": "What deadline should be used?",
                              "options": [
                                "Friday",
                                "End of month"
                              ]
                            }
                          ],
                          "answer": ""
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "needs_user_input": false,
                          "questions": [],
                          "answer": "approved planning output"
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "needs_user_input": false,
                          "questions": [],
                          "answer": "execution output"
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(AgentResponse(content = """{"status":"PASS","summary":"ok","details":"ok"}""")),
            ),
        )
        val workflowStore = RecordingSelectableUserDefinedWorkflowStore(
            workflows = listOf(UserWorkflowOption("workflow-default.json", "Default")),
            workflowsByFileName = mapOf(
                "workflow-default.json" to UserWorkflowDefinition(
                    fileName = "workflow-default.json",
                    name = "Default",
                    planning = "Planning step",
                    execution = "Execution step",
                    validation = "Validation step",
                ),
            ),
            activeFileName = "workflow-default.json",
        )
        val io = FakeCliIO(
            inputs = listOf("/workflow", "prepare release note", "Backend engineers", "Friday", "1", "1", "/exit"),
            workflowSelections = listOf(0),
        )
        val controller = createController(
            repository = repository,
            io = io,
            userDefinedWorkflowStore = workflowStore,
        )

        controller.runInteractive()

        assertEquals(4, repository.conversations.size)
        val secondPlanningRequest = repository.conversations[1]
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER),
            secondPlanningRequest.map { it.role },
        )
        assertContains(secondPlanningRequest.last().content, "Question: Who is the target audience?")
        assertContains(secondPlanningRequest.last().content, "Answer: Backend engineers")
        assertContains(secondPlanningRequest.last().content, "Question: What deadline should be used?")
        assertContains(secondPlanningRequest.last().content, "Answer: Friday")

        val output = io.outputText()
        assertFalse(output.contains("\"needs_user_input\""))
        assertFalse(output.contains("Questions:"))
        assertContains(output, "Planning needs additional user input.")
        assertContains(output, "workflow> question 1/2")
        assertContains(output, "question> Who is the target audience?")
        assertContains(output, "- Backend engineers")
        assertContains(output, "workflow> question 2/2")
        assertContains(output, "question> What deadline should be used?")
        assertContains(output, "- Friday")
        val answerPromptOneIndex = io.footerPrompts.indexOf("answer 1/2> ")
        val answerPromptTwoIndex = io.footerPrompts.indexOf("answer 2/2> ")
        assertTrue(answerPromptOneIndex >= 0)
        assertTrue(answerPromptTwoIndex > answerPromptOneIndex)
    }

    @Test
    fun executionStructuredQuestionsAreAskedOneByOneAndFedBackIntoExecution() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "needs_user_input": false,
                          "questions": [],
                          "answer": "approved plan"
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "needs_user_input": true,
                          "questions": [
                            {
                              "question": "Which API base URL should be used?",
                              "options": [
                                "https://api.example.com",
                                "https://staging-api.example.com"
                              ]
                            },
                            {
                              "question": "Which auth method should be used?",
                              "options": [
                                "OAuth2",
                                "API key"
                              ]
                            }
                          ],
                          "answer": ""
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "needs_user_input": false,
                          "questions": [],
                          "answer": "execution output"
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(AgentResponse(content = """{"status":"PASS","summary":"ok","details":"ok"}""")),
            ),
        )
        val workflowStore = RecordingSelectableUserDefinedWorkflowStore(
            workflows = listOf(UserWorkflowOption("workflow-default.json", "Default")),
            workflowsByFileName = mapOf(
                "workflow-default.json" to UserWorkflowDefinition(
                    fileName = "workflow-default.json",
                    name = "Default",
                    planning = "Planning step",
                    execution = "Execution step",
                    validation = "Validation step",
                ),
            ),
            activeFileName = "workflow-default.json",
        )
        val io = FakeCliIO(
            inputs = listOf("/workflow", "implement integration", "1", "https://api.example.com", "OAuth2", "1", "/exit"),
            workflowSelections = listOf(0),
        )
        val controller = createController(
            repository = repository,
            io = io,
            userDefinedWorkflowStore = workflowStore,
        )

        controller.runInteractive()

        assertEquals(4, repository.conversations.size)
        val secondExecutionRequest = repository.conversations[2]
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER),
            secondExecutionRequest.map { it.role },
        )
        assertContains(secondExecutionRequest.last().content, "Question: Which API base URL should be used?")
        assertContains(secondExecutionRequest.last().content, "Answer: https://api.example.com")
        assertContains(secondExecutionRequest.last().content, "Question: Which auth method should be used?")
        assertContains(secondExecutionRequest.last().content, "Answer: OAuth2")

        val output = io.outputText()
        assertFalse(output.contains("\"needs_user_input\""))
        assertFalse(output.contains("Questions:"))
        assertContains(output, "Execution needs additional user input.")
        assertContains(output, "workflow> question 1/2")
        assertContains(output, "question> Which API base URL should be used?")
        assertContains(output, "- https://api.example.com")
        assertContains(output, "workflow> question 2/2")
        assertContains(output, "question> Which auth method should be used?")
        assertContains(output, "- OAuth2")
        val answerPromptOneIndex = io.footerPrompts.indexOf("answer 1/2> ")
        val answerPromptTwoIndex = io.footerPrompts.indexOf("answer 2/2> ")
        assertTrue(answerPromptOneIndex >= 0)
        assertTrue(answerPromptTwoIndex > answerPromptOneIndex)
    }

    @Test
    fun restoredPlanningApprovalStateResumesPlanningOnNextUserInput() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "planning output updated")),
                Result.success(AgentResponse(content = "execution output")),
                Result.success(AgentResponse(content = """{"status":"PASS","summary":"ok","details":"ok"}""")),
            ),
        )
        val sessionStore = RecordingSessionMemoryStore(
            loadedState = SessionMemoryState(
                messages = emptyList(),
                workflowModeEnabled = true,
                workflowRuntimeState = WorkflowRuntimeState(
                    step = WorkflowStep.PLANNING_APPROVAL,
                    originalUserPrompt = "original task",
                    planningFeedback = emptyList(),
                    executionFeedback = emptyList(),
                    latestPlanningOutput = "old plan",
                    approvedPlan = null,
                    latestExecutionOutput = null,
                ),
            ),
        )
        val workflowStore = RecordingSelectableUserDefinedWorkflowStore(
            workflows = listOf(UserWorkflowOption("workflow-default.json", "Default")),
            workflowsByFileName = mapOf(
                "workflow-default.json" to UserWorkflowDefinition(
                    fileName = "workflow-default.json",
                    name = "Default",
                    planning = "Planning step",
                    execution = "Execution step",
                    validation = "Validation step",
                ),
            ),
            activeFileName = "workflow-default.json",
        )
        val io = FakeCliIO(inputs = listOf("new detail", "1", "1", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = sessionStore,
            userDefinedWorkflowStore = workflowStore,
        )

        controller.runInteractive()

        assertContains(repository.conversations.first()[0].content, "Planning step")
        assertContains(repository.conversations.first().last().content, "original task")
        assertContains(repository.conversations.first().last().content, "new detail")
        assertEquals(listOf<String?>("Workflow: planning"), io.footerLabels.take(1))
    }

    @Test
    fun restoredPlanningApprovalStateIgnoresPersistedConversationHistoryInNextWorkflowStep() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "planning output updated")),
                Result.success(AgentResponse(content = "execution output")),
                Result.success(AgentResponse(content = """{"status":"PASS","summary":"ok","details":"ok"}""")),
            ),
        )
        val sessionStore = RecordingSessionMemoryStore(
            loadedState = SessionMemoryState(
                messages = listOf(
                    ConversationMessage.user("persisted old question"),
                    ConversationMessage.assistant("persisted old answer"),
                ),
                workflowModeEnabled = true,
                workflowRuntimeState = WorkflowRuntimeState(
                    step = WorkflowStep.PLANNING_APPROVAL,
                    originalUserPrompt = "original task",
                    planningFeedback = emptyList(),
                    executionFeedback = emptyList(),
                    latestPlanningOutput = "old plan",
                    approvedPlan = null,
                    latestExecutionOutput = null,
                ),
            ),
        )
        val workflowStore = RecordingSelectableUserDefinedWorkflowStore(
            workflows = listOf(UserWorkflowOption("workflow-default.json", "Default")),
            workflowsByFileName = mapOf(
                "workflow-default.json" to UserWorkflowDefinition(
                    fileName = "workflow-default.json",
                    name = "Default",
                    planning = "Planning step",
                    execution = "Execution step",
                    validation = "Validation step",
                ),
            ),
            activeFileName = "workflow-default.json",
        )
        val io = FakeCliIO(inputs = listOf("new detail", "1", "1", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = sessionStore,
            userDefinedWorkflowStore = workflowStore,
        )

        controller.runInteractive()

        val firstWorkflowRequest = repository.conversations.first()
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER),
            firstWorkflowRequest.map { it.role },
        )
        val firstWorkflowPayload = firstWorkflowRequest.joinToString(separator = "\n") { message -> message.content }
        assertFalse(firstWorkflowPayload.contains("persisted old question"))
        assertFalse(firstWorkflowPayload.contains("persisted old answer"))
        assertContains(firstWorkflowRequest.last().content, "original task")
        assertContains(firstWorkflowRequest.last().content, "new detail")
    }

    @Test
    fun removedConfigCommandIsHandledAsUnknownAndDoesNotResetMemory() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "answer one")),
                Result.success(AgentResponse(content = "answer two")),
            ),
        )
        val store = RecordingSessionMemoryStore()
        val io = FakeCliIO(inputs = listOf("prompt one", "/config", "prompt two", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
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
            listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER),
            secondRequest.map { it.role },
        )
        assertEquals(firstRequest[0].content, secondRequest[0].content)
        assertEquals("prompt two", secondRequest[3].content)
        assertEquals(0, store.clearCalls)
        assertEquals(2, store.saveStates.size)
        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT),
            store.saveStates[1].messages.map { it.role },
        )
        assertContains(io.outputText(), "system> unknown command. Type /help for available commands.")
    }

    @Test
    fun removedTempCommandIsHandledAsUnknownAndDoesNotResetMemory() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "answer one")),
                Result.success(AgentResponse(content = "answer two")),
            ),
        )
        val store = RecordingSessionMemoryStore()
        val io = FakeCliIO(inputs = listOf("prompt one", "/temp 0.7", "prompt two", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
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
            listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER),
            secondRequest.map { it.role },
        )
        assertEquals(firstRequest[0].content, secondRequest[0].content)
        assertEquals("prompt two", secondRequest[3].content)
        assertEquals(0, store.clearCalls)
        assertEquals(2, store.saveStates.size)
        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT),
            store.saveStates[1].messages.map { it.role },
        )
        assertContains(io.outputText(), "system> unknown command. Type /help for available commands.")
    }

    @Test
    fun runSinglePromptDoesNotUsePersistentMemory() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "one-shot answer")),
                Result.success(AgentResponse(content = """{"status":"PASS","failed_constraints":[]}""")),
            ),
        )
        val store = RecordingSessionMemoryStore(
            loadedState = SessionMemoryState(
                messages = listOf(
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
    fun runSinglePromptLoadsInvariantConstraintsOnStartup() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "one-shot answer")),
                Result.success(AgentResponse(content = """{"status":"PASS","failed_constraints":[]}""")),
            ),
        )
        val invariantStore = RecordingInvariantConstraintStore(
            loadedConstraints = listOf("Always run tests"),
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = emptyList()),
            invariantConstraintStore = invariantStore,
            persistentMemoryEnabled = false,
        )

        val exitCode = controller.runSinglePrompt("one-shot question")

        assertEquals(0, exitCode)
        assertEquals(1, invariantStore.loadCalls)
        assertEquals(0, invariantStore.saveConstraints.size)
        assertEquals(2, repository.conversations.size)
    }

    @Test
    fun runSinglePromptUsesConfiguredTemperatureOverride() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(Result.success(AgentResponse(content = "one-shot answer"))),
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = emptyList()),
            apiSettingsService = MutableApiSettingsService(defaultApiSettings().copy(temperature = 0.9)),
            persistentMemoryEnabled = false,
        )

        val exitCode = controller.runSinglePrompt("one-shot question")

        assertEquals(0, exitCode)
        assertEquals(listOf<Double?>(0.9), repository.requestedTemperatures)
    }

    @Test
    fun interactiveModeLoadsInvariantConstraintsOnStartup() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val invariantStore = RecordingInvariantConstraintStore(
            loadedConstraints = listOf("Always run tests"),
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = listOf("/exit")),
            invariantConstraintStore = invariantStore,
        )

        controller.runInteractive()

        assertEquals(1, invariantStore.loadCalls)
        assertEquals(0, invariantStore.saveConstraints.size)
    }

    @Test
    fun invariantValidationPassesOnFirstAttempt() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "answer one")),
                Result.success(AgentResponse(content = """{"status":"PASS","failed_constraints":[]}""")),
            ),
        )
        val invariantStore = RecordingInvariantConstraintStore(
            loadedConstraints = listOf("Always run tests"),
        )
        val io = FakeCliIO(inputs = listOf("prompt one", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            invariantConstraintStore = invariantStore,
        )

        controller.runInteractive()

        assertEquals(2, repository.conversations.size)
        assertEquals("prompt one", repository.conversations[0][1].content)
        assertContains(
            repository.conversations[1][0].content,
            "You validate assistant responses against strict invariant constraints.",
        )
        assertContains(repository.conversations[1][1].content, "1. [Strict] Always run tests")
        assertContains(io.outputText(), "answer one")
    }

    @Test
    fun invariantConstraintsAreInjectedIntoEachMainPromptSystemMessage() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "answer one")),
                Result.success(AgentResponse(content = """{"status":"PASS","failed_constraints":[]}""")),
            ),
        )
        val invariantStore = RecordingInvariantConstraintStore(
            loadedConstraints = listOf(" Always run tests ", "[Strict] Avoid destructive changes", "[strict] always run tests"),
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = listOf("prompt one", "/exit")),
            invariantConstraintStore = invariantStore,
        )

        controller.runInteractive()

        val systemPrompt = repository.conversations[0][0].content
        assertContains(systemPrompt, "Invariant constraints (strict requirements):")
        assertContains(systemPrompt, "1. [Strict] Always run tests")
        assertContains(systemPrompt, "2. [Strict] Avoid destructive changes")
        assertContains(systemPrompt, "must be satisfied in every response")
    }

    @Test
    fun invariantValidationRetriesAndStoresOnlyAcceptedResponse() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "bad answer")),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "status":"FAIL",
                          "failed_constraints":[
                            {
                              "constraint":"[Strict] Always run tests",
                              "source":"llm",
                              "user_message":"Include concrete test evidence."
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(AgentResponse(content = "good answer")),
                Result.success(AgentResponse(content = """{"status":"PASS","failed_constraints":[]}""")),
                Result.success(AgentResponse(content = "second answer")),
                Result.success(AgentResponse(content = """{"status":"PASS","failed_constraints":[]}""")),
            ),
        )
        val invariantStore = RecordingInvariantConstraintStore(
            loadedConstraints = listOf("Always run tests"),
        )
        val io = FakeCliIO(inputs = listOf("prompt one", "prompt two", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            invariantConstraintStore = invariantStore,
        )

        controller.runInteractive()

        assertEquals(6, repository.conversations.size)
        assertContains(
            repository.conversations[2][1].content,
            "Your previous response was rejected by strict invariant validation.",
        )
        assertContains(repository.conversations[2][1].content, "Include concrete test evidence.")
        val secondPromptRequest = repository.conversations[4]
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER),
            secondPromptRequest.map { it.role },
        )
        assertEquals("good answer", secondPromptRequest[2].content)
        assertFalse(io.outputText().contains("bad answer"))
        assertContains(io.outputText(), "good answer")
    }

    @Test
    fun invariantValidationUserSourceDoesNotRetryGeneration() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "bad answer")),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "status":"FAIL",
                          "failed_constraints":[
                            {
                              "constraint":"[Strict] Always run tests",
                              "source":"user",
                              "user_message":"Your request explicitly asks to skip tests."
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(AgentResponse(content = "good answer")),
                Result.success(AgentResponse(content = """{"status":"PASS","failed_constraints":[]}""")),
            ),
        )
        val invariantStore = RecordingInvariantConstraintStore(
            loadedConstraints = listOf("Always run tests"),
        )
        val io = FakeCliIO(inputs = listOf("prompt one", "prompt two", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            invariantConstraintStore = invariantStore,
        )

        controller.runInteractive()

        assertEquals(4, repository.conversations.size)
        val secondPromptRequest = repository.conversations[2]
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER),
            secondPromptRequest.map { it.role },
        )
        assertEquals("prompt two", secondPromptRequest[1].content)
        assertContains(io.outputText(), "Invariant validation failed due to user prompt constraint violation.")
        assertContains(io.outputText(), "Source: user")
        assertContains(io.outputText(), "Your request explicitly asks to skip tests.")
        assertFalse(io.outputText().contains("bad answer"))
    }

    @Test
    fun invariantValidationStopsAfterRetryLimitAndKeepsMemoryClean() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "bad answer 1")),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "status":"FAIL",
                          "failed_constraints":[
                            {"constraint":"[Strict] Always run tests","source":"llm","user_message":"Add test evidence."}
                          ]
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(AgentResponse(content = "bad answer 2")),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "status":"FAIL",
                          "failed_constraints":[
                            {"constraint":"[Strict] Always run tests","source":"llm","user_message":"Still missing tests."}
                          ]
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(AgentResponse(content = "bad answer 3")),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "status":"FAIL",
                          "failed_constraints":[
                            {"constraint":"[Strict] Always run tests","source":"llm","user_message":"Tests are required."}
                          ]
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(AgentResponse(content = "good answer")),
                Result.success(AgentResponse(content = """{"status":"PASS","failed_constraints":[]}""")),
            ),
        )
        val invariantStore = RecordingInvariantConstraintStore(
            loadedConstraints = listOf("Always run tests"),
        )
        val io = FakeCliIO(inputs = listOf("prompt one", "prompt two", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            invariantConstraintStore = invariantStore,
        )

        controller.runInteractive()

        assertEquals(8, repository.conversations.size)
        assertContains(io.outputText(), "Invariant validation failed after 3 attempts.")
        val secondPromptRequest = repository.conversations[6]
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER),
            secondPromptRequest.map { it.role },
        )
        assertEquals("prompt two", secondPromptRequest[1].content)
    }

    @Test
    fun runSinglePromptAppliesInvariantValidationAndRetry() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "bad answer")),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "status":"FAIL",
                          "failed_constraints":[
                            {"constraint":"[Strict] Always run tests","source":"llm","user_message":"Add test evidence."}
                          ]
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(AgentResponse(content = "good answer")),
                Result.success(AgentResponse(content = """{"status":"PASS","failed_constraints":[]}""")),
            ),
        )
        val invariantStore = RecordingInvariantConstraintStore(
            loadedConstraints = listOf("Always run tests"),
        )
        val io = FakeCliIO(inputs = emptyList())
        val controller = createController(
            repository = repository,
            io = io,
            invariantConstraintStore = invariantStore,
            persistentMemoryEnabled = false,
        )

        val exitCode = controller.runSinglePrompt("one-shot question")

        assertEquals(0, exitCode)
        assertEquals(4, repository.conversations.size)
        assertContains(
            repository.conversations[2][1].content,
            "Your previous response was rejected by strict invariant validation.",
        )
        assertContains(io.outputText(), "good answer")
        assertFalse(io.outputText().contains("bad answer"))
    }

    @Test
    fun branchingModeValidatesOnlyMainAssistantResponse() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "assistant answer")),
                Result.success(AgentResponse(content = """{"status":"PASS","failed_constraints":[]}""")),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "topic": {"kind": "new", "key": "", "name": "Building new application"},
                          "subtopic": {"kind": "new", "key": "", "name": "Architecture"}
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "allowNewTopic": true,
                          "reuseTopicKey": "",
                          "allowNewSubtopic": true,
                          "reuseSubtopicKey": ""
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(AgentResponse(content = "updated topic summary")),
            ),
        )
        val invariantStore = RecordingInvariantConstraintStore(
            loadedConstraints = listOf("Always run tests"),
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = listOf("Create architecture", "/exit")),
            invariantConstraintStore = invariantStore,
            compactionCoordinators = mapOf(
                SessionCompactionMode.BRANCHING to SessionMemoryCompactionCoordinator.disabled(),
            ),
            defaultCompactionMode = SessionCompactionMode.BRANCHING,
        )

        controller.runInteractive()

        assertEquals(5, repository.conversations.size)
        val invariantValidationRequests = repository.conversations.count { conversation ->
            conversation.firstOrNull()?.content?.contains(
                "You validate assistant responses against strict invariant constraints.",
            ) == true
        }
        assertEquals(1, invariantValidationRequests)
        assertContains(repository.conversations[2][0].content, "You classify conversation turns into topic and subtopic")
    }

    @Test
    fun memoryCommandUsesPersistedUsageOnStartup() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val io = FakeCliIO(inputs = listOf("/memory", "/exit"))
        val store = RecordingSessionMemoryStore(
            loadedState = SessionMemoryState(
                messages = listOf(
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
    fun invalidLegacySystemSnapshotIsClearedOnStartup() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val io = FakeCliIO(inputs = listOf("/exit"))
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
        assertEquals(1, store.clearCalls)
        assertEquals(0, store.saveStates.size)
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
        val io = FakeCliIO(
            inputs = (1..7).map { index -> "prompt $index" } + "/exit",
        )
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = store,
            compactionCoordinators = mapOf(
                SessionCompactionMode.ROLLING_SUMMARY to SessionMemoryCompactionCoordinator(
                    startPolicy = RollingWindowCompactionStartPolicy(
                        threshold = 12,
                        compactCount = 10,
                        keepCount = 2,
                    ),
                    strategy = strategy,
                ),
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
            listOf(MessageRole.USER, MessageRole.ASSISTANT),
            savedWithSummary.messages.map { it.role },
        )
        assertContains(io.outputText(), "system> session memory compacted")
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
            compactionCoordinators = mapOf(
                SessionCompactionMode.ROLLING_SUMMARY to SessionMemoryCompactionCoordinator(
                    startPolicy = RollingWindowCompactionStartPolicy(
                        threshold = 12,
                        compactCount = 10,
                        keepCount = 2,
                    ),
                    strategy = strategy,
                ),
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
        assertContains(store.saveStates.single().messages[0].content, "line one\nline two")
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
    fun helpAndHeaderIncludeApiAndMemoryCommands() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val io = FakeCliIO(inputs = listOf("/help", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
        )

        controller.runInteractive()

        val output = io.outputText()
        assertContains(
            output,
            "commands: /help, /project_help, /api, /models, /model <id|number>, /temperature [0..2|default], /memory, /compact, /profile, /workflow, /mcp, /invariant, /reset, /exit, @<path>",
        )
        assertContains(output, "/project_help        toggle Wire project-information mode")
        assertContains(output, "/api                 select the active API from api-settings.json")
        assertContains(output, "/temperature [arg]   show or set global temperature override (arg: 0..2 or default)")
        assertContains(output, "/memory              show session-memory context usage")
        assertContains(output, "/compact             choose memory compaction strategy")
        assertContains(output, "/profile             choose active user profile")
        assertContains(output, "/workflow            enable workflow mode with workflow selection (toggle off when enabled)")
        assertContains(output, "/mcp                 configure MCP servers")
        assertContains(output, "/invariant           configure invariant constraints")
        assertContains(output, "@<path>              attach file for the next prompt")
    }

    @Test
    fun interactiveFooterReceivesSharedCommandDescriptors() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val io = FakeCliIO(inputs = listOf("/exit"))
        val controller = createController(
            repository = repository,
            io = io,
        )

        controller.runInteractive()

        assertEquals(1, io.footerCommandDescriptors.size)
        assertEquals(
            CLI_COMMAND_DESCRIPTORS.map(CliCommandDescriptor::name),
            io.footerCommandDescriptors.single().map(CliCommandDescriptor::name),
        )
    }

    @Test
    fun projectHelpTogglesProjectInformationModeAndShowsFooterLabel() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(Result.success(AgentResponse(content = "Grounded answer"))),
        )
        val io = FakeCliIO(inputs = listOf("/project_help", "How does navigation work?", "/project_help", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            wireAppRagRetriever = RecordingWireAppRagRetriever(
                results = listOf(
                    Result.success(
                        listOf(
                            RagRetrievedChunk(
                                chunkId = "architecture-1",
                                sectionName = "Navigation Architecture",
                                headingPath = "Architecture > Navigation Architecture",
                                sourcePath = "architecture.md",
                                score = 0.91,
                                content = "Navigation is coordinated by the app module.",
                            ),
                        ),
                    ),
                ),
            ),
        )

        controller.runInteractive()

        val output = io.outputText()
        assertContains(output, "system> project information mode enabled")
        assertContains(output, "system> project information mode disabled")
        assertEquals("Mode: project info", io.footerLabels.firstOrNull { it != null })
    }

    @Test
    fun projectInformationModeReturnsNoDataAvailableWithoutCallingLlm() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val io = FakeCliIO(inputs = listOf("/project_help", "Unknown topic", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            wireAppRagRetriever = RecordingWireAppRagRetriever(
                results = listOf(Result.success(emptyList())),
            ),
        )

        controller.runInteractive()

        assertEquals(0, repository.conversations.size)
        assertContains(io.outputText(), "No data available")
    }

    @Test
    fun projectInformationModeUsesFreshRagGroundedPromptWithoutSessionHistory() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "Regular answer")),
                Result.success(AgentResponse(content = "Grounded answer")),
            ),
        )
        val io = FakeCliIO(inputs = listOf("normal prompt", "/project_help", "How does navigation work?", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            wireAppRagRetriever = RecordingWireAppRagRetriever(
                results = listOf(
                    Result.success(
                        listOf(
                            RagRetrievedChunk(
                                chunkId = "architecture-1",
                                sectionName = "Navigation Architecture",
                                headingPath = "Architecture > Navigation Architecture",
                                sourcePath = "architecture.md",
                                score = 0.91,
                                content = "Navigation is coordinated by the app module.",
                            ),
                        ),
                    ),
                ),
            ),
        )

        controller.runInteractive()

        assertEquals(2, repository.conversations.size)
        val projectConversation = repository.conversations[1]
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.SYSTEM, MessageRole.USER),
            projectConversation.map { it.role },
        )
        assertContains(projectConversation[0].content, "Wire App project")
        assertContains(projectConversation[1].content, "architecture.md")
        assertContains(projectConversation[1].content, "Navigation is coordinated by the app module.")
        assertEquals("How does navigation work?", projectConversation[2].content)
        assertEquals(4, repository.prompts[1].toolCapabilities.privateTools.size)
        assertTrue(repository.prompts[1].toolCapabilities.publicMcpServers.isEmpty())
        assertTrue(repository.prompts[1].toolCapabilities.privateTools.any { tool -> tool.modelToolName == "notify_user" })
        assertTrue(repository.prompts[1].toolCapabilities.privateTools.any { tool -> tool.modelToolName == "scheduler" })
        assertFalse(projectConversation.any { message -> message.content.contains("normal prompt") })
        assertFalse(projectConversation.any { message -> message.content.contains("Regular answer") })
    }

    @Test
    fun projectInformationModeDoesNotInjectPendingFileReferences() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(Result.success(AgentResponse(content = "Grounded answer"))),
        )
        val io = FakeCliIO(inputs = listOf("@notes.txt", "/project_help", "How does navigation work?", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            fileReferenceReader = RecordingFileReferenceReader(
                contentsByPath = mapOf("notes.txt" to "this should not be injected"),
            ),
            wireAppRagRetriever = RecordingWireAppRagRetriever(
                results = listOf(
                    Result.success(
                        listOf(
                            RagRetrievedChunk(
                                chunkId = "architecture-1",
                                sectionName = "Navigation Architecture",
                                headingPath = "Architecture > Navigation Architecture",
                                sourcePath = "architecture.md",
                                score = 0.91,
                                content = "Navigation is coordinated by the app module.",
                            ),
                        ),
                    ),
                ),
            ),
        )

        controller.runInteractive()

        assertEquals(1, repository.conversations.size)
        val projectConversation = repository.conversations.single()
        assertEquals(4, repository.prompts.single().toolCapabilities.privateTools.size)
        assertFalse(projectConversation.any { message -> message.content.contains("[FILE]") })
        assertFalse(projectConversation.any { message -> message.content.contains("this should not be injected") })
    }

    @Test
    fun projectInformationModeAttachesPublicAndPrivateMcpCapabilitiesAlongsideBuiltIns() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(Result.success(AgentResponse(content = "Grounded answer"))),
        )
        val publicServer = httpMcpServer(
            name = "Weather",
            url = "https://weather.chukai.io/mcp",
            enabled = true,
            isPublic = true,
        )
        val privateServer = httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true)
        val runtimeService = RecordingMcpRuntimeService(
            runtimeStates = mapOf(
                "Linear" to McpServerRuntimeState(
                    server = privateServer,
                    status = McpRuntimeStatus.READY,
                    toolCatalogStatus = McpToolCatalogStatus.LOADED,
                    tools = listOf(
                        McpToolDefinition(
                            name = "search_issues",
                            description = "Search Linear issues",
                            inputSchemaJson = """{"type":"object"}""",
                        ),
                    ),
                ),
            ),
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = listOf("/project_help", "How does navigation work?", "/exit")),
            mcpServerStore = RecordingMcpServerStore(
                loadedServers = listOf(publicServer, privateServer),
            ),
            mcpRuntimeService = runtimeService,
            wireAppRagRetriever = RecordingWireAppRagRetriever(
                results = listOf(
                    Result.success(
                        listOf(
                            RagRetrievedChunk(
                                chunkId = "architecture-1",
                                sectionName = "Navigation Architecture",
                                headingPath = "Architecture > Navigation Architecture",
                                sourcePath = "architecture.md",
                                score = 0.91,
                                content = "Navigation is coordinated by the app module.",
                            ),
                        ),
                    ),
                ),
            ),
        )

        controller.runInteractive()

        assertEquals(1, repository.prompts.size)
        assertEquals(1, repository.prompts.single().toolCapabilities.publicMcpServers.size)
        assertEquals(5, repository.prompts.single().toolCapabilities.privateTools.size)
        assertTrue(repository.prompts.single().toolCapabilities.privateTools.any { tool -> tool.modelToolName == "notify_user" })
        assertTrue(repository.prompts.single().toolCapabilities.privateTools.any { tool -> tool.modelToolName == "scheduler" })
        assertTrue(repository.prompts.single().toolCapabilities.privateTools.any { tool ->
            tool.target == PrivateToolTarget.Mcp(server = privateServer, sourceToolName = "search_issues")
        })
    }

    @Test
    fun projectInformationModeShowsToolCallsLive() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(Result.success(AgentResponse(content = "Grounded answer"))),
            toolTraceEvents = listOf(
                listOf(
                    ToolCallTraceEvent.Started(toolLabel = "built-in 'scheduler'"),
                ),
            ),
        )
        val io = FakeCliIO(inputs = listOf("/project_help", "How does navigation work?", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            wireAppRagRetriever = RecordingWireAppRagRetriever(
                results = listOf(
                    Result.success(
                        listOf(
                            RagRetrievedChunk(
                                chunkId = "architecture-1",
                                sectionName = "Navigation Architecture",
                                headingPath = "Architecture > Navigation Architecture",
                                sourcePath = "architecture.md",
                                score = 0.91,
                                content = "Navigation is coordinated by the app module.",
                            ),
                        ),
                    ),
                ),
            ),
        )

        controller.runInteractive()

        assertEquals(
            listOf("system> tool call: built-in 'scheduler'"),
            io.liveDialogLines,
        )
        val output = io.outputText()
        assertContains(output, "system> tool call: built-in 'scheduler'")
        assertTrue(output.indexOf("system> tool call: built-in 'scheduler'") < output.indexOf("⏺ Grounded answer"))
    }

    @Test
    fun promptWithoutConfiguredApiProviderShowsGuidance() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(Result.success(AgentResponse(content = "unused"))),
        )
        val io = FakeCliIO(inputs = listOf("prompt one", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            apiSettingsService = MutableApiSettingsService(null),
        )

        controller.runInteractive()

        assertTrue(repository.conversations.isEmpty())
        assertContains(
            io.outputText(),
            "system> no API configured. Define APIs in ~/.kotlin-agent-cli/api-settings.json and use /api to select one.",
        )
    }

    @Test
    fun apiCommandSwitchesToSelectedConfiguredApiAndPersists() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(Result.success(AgentResponse(content = "local answer"))),
        )
        val apiSettingsStore = RecordingApiSettingsStore(defaultApiSettings())
        val io = FakeCliIO(
            inputs = listOf("/api", "/models", "prompt one", "/exit"),
            apiSelections = listOf(1),
        )
        val controller = createController(
            repository = repository,
            io = io,
            apiSettingsStore = apiSettingsStore,
        )

        controller.runInteractive()

        assertEquals("local", apiSettingsStore.saveStates.last().activeApiId)
        assertEquals("Local", io.apiMenuOptions.last())
        assertEquals("gpt-4.1-mini", apiSettingsStore.saveStates.last().activeApiOrNull()?.selectedModel)
        assertEquals(listOf<String?>("gpt-4.1-mini"), repository.requestedModels)
        val output = io.outputText()
        assertContains(output, "system> active API set to 'Local'")
        assertContains(output, "default_model='gpt-4.1-mini'")
        assertContains(output, "Available models for Local:")
        assertContains(output, "gpt-4.1-mini")
        assertContains(output, "gpt-4.1-nano")
    }

    @Test
    fun modelCommandPersistsSelectionForActiveProvider() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(Result.success(AgentResponse(content = "answer one"))),
        )
        val apiSettingsStore = RecordingApiSettingsStore(defaultApiSettings())
        val io = FakeCliIO(inputs = listOf("/model 2", "prompt one", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            apiSettingsStore = apiSettingsStore,
        )

        controller.runInteractive()

        assertEquals("gpt-4.1-nano", apiSettingsStore.saveStates.last().activeApiOrNull()?.selectedModel)
        assertEquals(listOf<String?>("gpt-4.1-nano"), repository.requestedModels)
        assertContains(io.outputText(), "system> model switched to 'gpt-4.1-nano'")
    }

    @Test
    fun temperatureCommandShowsCurrentOverrideWhenNoArgumentProvided() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val io = FakeCliIO(inputs = listOf("/temperature", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
        )

        controller.runInteractive()

        assertContains(io.outputText(), "system> temperature override: default. Use /temperature <0..2|default> to change it.")
    }

    @Test
    fun temperatureCommandPersistsGlobalOverrideAndUsesItForNextPrompt() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(Result.success(AgentResponse(content = "answer one"))),
        )
        val apiSettingsStore = RecordingApiSettingsStore(defaultApiSettings())
        val io = FakeCliIO(inputs = listOf("/temperature 0.7", "prompt one", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            apiSettingsStore = apiSettingsStore,
        )

        controller.runInteractive()

        assertEquals(0.7, apiSettingsStore.saveStates.last().temperature)
        assertEquals(listOf<Double?>(0.7), repository.requestedTemperatures)
        assertContains(io.outputText(), "system> temperature override set to 0.7")
    }

    @Test
    fun temperatureCommandClearRestoresDefaultWithoutResettingMemory() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "answer one")),
                Result.success(AgentResponse(content = "answer two")),
            ),
        )
        val sessionStore = RecordingSessionMemoryStore()
        val warmedSettings = defaultApiSettings().copy(temperature = 0.7)
        val apiSettingsStore = RecordingApiSettingsStore(warmedSettings)
        val io = FakeCliIO(inputs = listOf("prompt one", "/temperature default", "prompt two", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = sessionStore,
            apiSettingsService = MutableApiSettingsService(warmedSettings),
            apiSettingsStore = apiSettingsStore,
        )

        controller.runInteractive()

        assertEquals(null, apiSettingsStore.saveStates.last().temperature)
        assertEquals(listOf<Double?>(0.7, null), repository.requestedTemperatures)
        assertEquals(0, sessionStore.clearCalls)
        assertEquals(2, sessionStore.saveStates.size)
        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT),
            sessionStore.saveStates[1].messages.map { it.role },
        )
        assertContains(io.outputText(), "system> temperature override cleared (provider default)")
    }

    @Test
    fun temperatureCommandRejectsInvalidValues() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val io = FakeCliIO(inputs = listOf("/temperature nope", "/temperature 2.5", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
        )

        controller.runInteractive()

        val output = io.outputText()
        assertContains(output, "system> usage: /temperature <0..2|default>. Current override: default")
        assertTrue(repository.conversations.isEmpty())
    }

    @Test
    fun temperatureOverrideSurvivesApiSwitch() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(Result.success(AgentResponse(content = "local answer"))),
        )
        val warmedSettings = defaultApiSettings().copy(temperature = 0.4)
        val apiSettingsStore = RecordingApiSettingsStore(warmedSettings)
        val io = FakeCliIO(
            inputs = listOf("/api", "prompt one", "/exit"),
            apiSelections = listOf(1),
        )
        val controller = createController(
            repository = repository,
            io = io,
            apiSettingsService = MutableApiSettingsService(warmedSettings),
            apiSettingsStore = apiSettingsStore,
        )

        controller.runInteractive()

        assertEquals(0.4, apiSettingsStore.saveStates.last().temperature)
        assertEquals(listOf<Double?>(0.4), repository.requestedTemperatures)
    }

    @Test
    fun modelCommandRejectsModelsOutsideActiveApiCatalog() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(Result.success(AgentResponse(content = "unused"))),
        )
        val restrictedSettings = ApiSettings(
            activeApiId = "local",
            apis = listOf(
                ConfiguredApi(
                    id = "local",
                    name = "Local",
                    baseUrl = "http://127.0.0.1:11434/v1",
                    apiKey = "local-key",
                    availableModels = listOf("gpt-4.1-nano"),
                    defaultModel = "gpt-4.1-nano",
                    selectedModel = "gpt-4.1-nano",
                ),
            ),
        )
        val io = FakeCliIO(inputs = listOf("/model gpt-4.1-mini", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            apiSettingsService = MutableApiSettingsService(restrictedSettings),
            apiSettingsStore = RecordingApiSettingsStore(restrictedSettings),
        )

        controller.runInteractive()

        assertTrue(repository.conversations.isEmpty())
        assertContains(io.outputText(), "system> unknown model 'gpt-4.1-mini'. Run /models to view available models.")
    }

    @Test
    fun modelsCommandListsOnlyModelsConfiguredForActiveApi() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(Result.success(AgentResponse(content = "unused"))),
        )
        val restrictedSettings = ApiSettings(
            activeApiId = "local",
            apis = listOf(
                ConfiguredApi(
                    id = "prod",
                    name = "Production",
                    baseUrl = "https://api.openai.com/v1",
                    apiKey = "test-key",
                    availableModels = listOf("gpt-4.1-mini", "gpt-4.1-nano"),
                    defaultModel = "gpt-4.1-mini",
                    selectedModel = "gpt-4.1-mini",
                ),
                ConfiguredApi(
                    id = "local",
                    name = "Local",
                    baseUrl = "http://127.0.0.1:11434/v1",
                    apiKey = "local-key",
                    availableModels = listOf("gpt-4.1-nano"),
                    defaultModel = "gpt-4.1-nano",
                    selectedModel = "gpt-4.1-nano",
                ),
            ),
        )
        val io = FakeCliIO(inputs = listOf("/models", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            apiSettingsService = MutableApiSettingsService(restrictedSettings),
        )

        controller.runInteractive()

        val output = io.outputText()
        assertContains(output, "Available models for Local:")
        assertContains(output, "gpt-4.1-nano")
        assertFalse(output.contains("gpt-4.1-mini"))
    }

    @Test
    fun modelsCommandShowsConfiguredUnknownModelsWithUnavailableMetadata() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(Result.success(AgentResponse(content = "unused"))),
        )
        val restrictedSettings = ApiSettings(
            activeApiId = "local",
            apis = listOf(
                ConfiguredApi(
                    id = "local",
                    name = "Local",
                    baseUrl = "http://127.0.0.1:11434/v1",
                    apiKey = "local-key",
                    availableModels = listOf("qwen3:8b"),
                    defaultModel = "qwen3:8b",
                    selectedModel = "qwen3:8b",
                ),
            ),
        )
        val io = FakeCliIO(inputs = listOf("/models", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            apiSettingsService = MutableApiSettingsService(restrictedSettings),
            apiSettingsStore = RecordingApiSettingsStore(restrictedSettings),
        )

        controller.runInteractive()

        val output = io.outputText()
        assertContains(output, "Available models for Local:")
        assertContains(output, "qwen3:8b (ctx=n/a; in=n/a; out=n/a)")
    }

    @Test
    fun apiCommandSaveFailureLeavesCurrentProviderAndModelUnchanged() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(Result.success(AgentResponse(content = "answer one"))),
        )
        val apiSettingsStore = RecordingApiSettingsStore(
            loadedSettings = defaultApiSettings(),
            failOnSaveCalls = setOf(1),
        )
        val io = FakeCliIO(
            inputs = listOf("/api", "prompt one", "/exit"),
            apiSelections = listOf(1),
        )
        val controller = createController(
            repository = repository,
            io = io,
            apiSettingsStore = apiSettingsStore,
        )

        controller.runInteractive()

        assertEquals(listOf<String?>("gpt-4.1-mini"), repository.requestedModels)
        assertContains(io.outputText(), "system> failed to persist API settings")
    }

    @Test
    fun invariantCommandAddsConstraintAndPersists() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val invariantStore = RecordingInvariantConstraintStore()
        val io = FakeCliIO(
            inputs = listOf("/invariant", "Always run tests before finalizing", "/exit"),
            invariantSelections = listOf(
                InvariantMenuResult(action = InvariantMenuAction.CONFIRM, selectedIndex = 0),
                null,
            ),
        )
        val controller = createController(
            repository = repository,
            io = io,
            invariantConstraintStore = invariantStore,
        )

        controller.runInteractive()

        assertEquals(
            listOf(listOf("Always run tests before finalizing")),
            invariantStore.saveConstraints,
        )
        assertContains(io.outputText(), "system> invariant constraint added")
    }

    @Test
    fun invariantCommandDeletesConstraintAndPersists() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val invariantStore = RecordingInvariantConstraintStore(
            loadedConstraints = listOf(
                "Always run tests",
                "Keep commits atomic",
            ),
        )
        val io = FakeCliIO(
            inputs = listOf("/invariant", "/exit"),
            invariantSelections = listOf(
                InvariantMenuResult(action = InvariantMenuAction.DELETE, selectedIndex = 1),
                null,
            ),
        )
        val controller = createController(
            repository = repository,
            io = io,
            invariantConstraintStore = invariantStore,
        )

        controller.runInteractive()

        assertEquals(
            listOf(listOf("Always run tests")),
            invariantStore.saveConstraints,
        )
        assertContains(io.outputText(), "system> invariant constraint removed: \"Keep commits atomic\"")
    }

    @Test
    fun invariantCommandRejectsBlankConstraint() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val invariantStore = RecordingInvariantConstraintStore()
        val io = FakeCliIO(
            inputs = listOf("/invariant", "   ", "/exit"),
            invariantSelections = listOf(
                InvariantMenuResult(action = InvariantMenuAction.CONFIRM, selectedIndex = 0),
                null,
            ),
        )
        val controller = createController(
            repository = repository,
            io = io,
            invariantConstraintStore = invariantStore,
        )

        controller.runInteractive()

        assertEquals(0, invariantStore.saveConstraints.size)
        assertContains(io.outputText(), "system> invariant constraint must not be blank")
    }

    @Test
    fun invariantCommandRejectsDuplicateConstraintCaseInsensitively() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val invariantStore = RecordingInvariantConstraintStore(
            loadedConstraints = listOf("Always run tests"),
        )
        val io = FakeCliIO(
            inputs = listOf("/invariant", "  always RUN tests  ", "/exit"),
            invariantSelections = listOf(
                InvariantMenuResult(action = InvariantMenuAction.CONFIRM, selectedIndex = 1),
                null,
            ),
        )
        val controller = createController(
            repository = repository,
            io = io,
            invariantConstraintStore = invariantStore,
        )

        controller.runInteractive()

        assertEquals(0, invariantStore.saveConstraints.size)
        assertContains(io.outputText(), "system> invariant constraint already exists")
    }

    @Test
    fun invariantCommandDeleteOnAddNewItemIsIgnored() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val invariantStore = RecordingInvariantConstraintStore()
        val io = FakeCliIO(
            inputs = listOf("/invariant", "/exit"),
            invariantSelections = listOf(
                InvariantMenuResult(action = InvariantMenuAction.DELETE, selectedIndex = 0),
                null,
            ),
        )
        val controller = createController(
            repository = repository,
            io = io,
            invariantConstraintStore = invariantStore,
        )

        controller.runInteractive()

        assertEquals(0, invariantStore.saveConstraints.size)
        assertContains(io.outputText(), "system> 'Add new constraint' cannot be removed")
    }

    @Test
    fun invariantCommandKeepsMenuOpenForBatchEdits() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val invariantStore = RecordingInvariantConstraintStore()
        val io = FakeCliIO(
            inputs = listOf("/invariant", "Keep PR scope small", "/exit"),
            invariantSelections = listOf(
                InvariantMenuResult(action = InvariantMenuAction.CONFIRM, selectedIndex = 0),
                InvariantMenuResult(action = InvariantMenuAction.DELETE, selectedIndex = 0),
                null,
            ),
        )
        val controller = createController(
            repository = repository,
            io = io,
            invariantConstraintStore = invariantStore,
        )

        controller.runInteractive()

        assertEquals(
            listOf(
                listOf("Keep PR scope small"),
                emptyList(),
            ),
            invariantStore.saveConstraints,
        )
    }

    @Test
    fun invariantCommandEnterOnExistingConstraintIsNoOp() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val invariantStore = RecordingInvariantConstraintStore(
            loadedConstraints = listOf("Always run tests"),
        )
        val io = FakeCliIO(
            inputs = listOf("/invariant", "/exit"),
            invariantSelections = listOf(
                InvariantMenuResult(action = InvariantMenuAction.CONFIRM, selectedIndex = 0),
                null,
            ),
        )
        val controller = createController(
            repository = repository,
            io = io,
            invariantConstraintStore = invariantStore,
        )

        controller.runInteractive()

        assertEquals(0, invariantStore.saveConstraints.size)
    }

    @Test
    fun profileCommandSwitchesActiveProfileResetsConversationAndPersistsSnapshot() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val sessionStore = RecordingSessionMemoryStore(
            loadedState = SessionMemoryState(
                messages = listOf(
                    ConversationMessage.user("old question"),
                    ConversationMessage.assistant("old answer"),
                ),
                usage = MemoryUsageSnapshot(
                    estimatedTokens = 200,
                    source = MemoryEstimateSource.HYBRID,
                    messageCount = 3,
                ),
            ),
        )
        val userDefinedProfileStore = RecordingSelectableUserDefinedProfileStore(
            profiles = listOf(
                UserProfileOption("user-profile-default.json", "Default"),
                UserProfileOption("user-profile-work.json", "Work"),
            ),
            profilesByFileName = mapOf(
                "user-profile-default.json" to ProfilePreferenceState(writingStyle = "default style"),
                "user-profile-work.json" to ProfilePreferenceState(writingStyle = "work style"),
            ),
            activeFileName = "user-profile-default.json",
        )
        val io = FakeCliIO(
            inputs = listOf("/profile", "/exit"),
            profileSelections = listOf(1),
        )
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = sessionStore,
            userDefinedProfileStore = userDefinedProfileStore,
        )

        controller.runInteractive()

        assertEquals(listOf("user-profile-work.json"), userDefinedProfileStore.setActiveCalls)
        assertEquals(1, sessionStore.saveStates.size)
        assertEquals(emptyList(), sessionStore.saveStates.single().messages)
        assertContains(io.outputText(), "system> active profile set to 'Work'")
    }

    @Test
    fun profileCommandCancelKeepsCurrentProfileAndDoesNotPersistSnapshot() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val sessionStore = RecordingSessionMemoryStore()
        val userDefinedProfileStore = RecordingSelectableUserDefinedProfileStore(
            profiles = listOf(
                UserProfileOption("user-profile-default.json", "Default"),
                UserProfileOption("user-profile-work.json", "Work"),
            ),
            activeFileName = "user-profile-default.json",
        )
        val io = FakeCliIO(
            inputs = listOf("/profile", "/exit"),
            profileSelections = listOf(null),
        )
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = sessionStore,
            userDefinedProfileStore = userDefinedProfileStore,
        )

        controller.runInteractive()

        assertEquals(emptyList(), userDefinedProfileStore.setActiveCalls)
        assertEquals(0, sessionStore.saveStates.size)
        assertFalse(io.outputText().contains("active profile set"))
    }

    @Test
    fun profileCommandSelectingAlreadyActiveShowsMessage() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val sessionStore = RecordingSessionMemoryStore()
        val userDefinedProfileStore = RecordingSelectableUserDefinedProfileStore(
            profiles = listOf(
                UserProfileOption("user-profile-default.json", "Default"),
                UserProfileOption("user-profile-work.json", "Work"),
            ),
            activeFileName = "user-profile-default.json",
        )
        val io = FakeCliIO(
            inputs = listOf("/profile", "/exit"),
            profileSelections = listOf(0),
        )
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = sessionStore,
            userDefinedProfileStore = userDefinedProfileStore,
        )

        controller.runInteractive()

        assertEquals(emptyList(), userDefinedProfileStore.setActiveCalls)
        assertEquals(0, sessionStore.saveStates.size)
        assertContains(io.outputText(), "system> profile 'Default' is already active")
    }

    @Test
    fun profileCommandShowsMessageWhenNoValidProfilesFound() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val io = FakeCliIO(inputs = listOf("/profile", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            userDefinedProfileStore = RecordingSelectableUserDefinedProfileStore(
                profiles = emptyList(),
            ),
        )

        controller.runInteractive()

        assertContains(io.outputText(), "system> no valid user profiles found")
    }

    @Test
    fun mcpCommandShowsMessageWhenNoValidServersFound() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val mcpStore = RecordingMcpServerStore()
        val io = FakeCliIO(inputs = listOf("/mcp", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            mcpServerStore = mcpStore,
        )

        controller.runInteractive()

        assertEquals(1, mcpStore.loadCalls)
        assertContains(io.outputText(), "system> no valid MCP servers found")
    }

    @Test
    fun mcpToolCommandInvokesToolWithEmptyArgumentsByDefault() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val server = httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true)
        val runtimeService = RecordingMcpRuntimeService(
            runtimeStates = mapOf(
                "Linear" to McpServerRuntimeState(
                    server = server,
                    status = McpRuntimeStatus.READY,
                    toolCatalogStatus = McpToolCatalogStatus.LOADED,
                    tools = listOf(
                        McpToolDefinition(
                            name = "search_issues",
                            description = "Search Linear issues",
                            inputSchemaJson = """{"type":"object"}""",
                        ),
                    ),
                ),
            ),
            toolCallResultsByKey = mapOf(
                ("Linear" to "search_issues") to Result.success(
                    McpToolCallResult(
                        isError = false,
                        content = buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", "ok")
                                },
                            )
                        },
                        structuredContent = buildJsonObject {
                            put("count", 1)
                        },
                        meta = buildJsonObject {
                            put("request_id", "req-1")
                        },
                    ),
                ),
            ),
        )
        val io = FakeCliIO(inputs = listOf("/mcp 1 search_issues", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            mcpServerStore = RecordingMcpServerStore(loadedServers = listOf(server)),
            mcpRuntimeService = runtimeService,
        )

        controller.runInteractive()

        assertEquals(
            listOf(
                RecordedMcpToolCall(
                    server = server,
                    toolName = "search_issues",
                    arguments = buildJsonObject {},
                ),
            ),
            runtimeService.callToolRequests,
        )
        val output = io.outputText()
        assertContains(output, "mcp> Linear/search_issues")
        assertContains(output, "\"is_error\": false")
        assertContains(output, "\"structured_content\": {")
        assertContains(output, "\"_meta\": {")
    }

    @Test
    fun mcpToolCommandParsesJsonObjectArguments() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val server = httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true)
        val runtimeService = RecordingMcpRuntimeService(
            runtimeStates = mapOf(
                "Linear" to McpServerRuntimeState(
                    server = server,
                    status = McpRuntimeStatus.READY,
                    toolCatalogStatus = McpToolCatalogStatus.LOADED,
                    tools = listOf(
                        McpToolDefinition(
                            name = "search_issues",
                            description = "Search Linear issues",
                            inputSchemaJson = """{"type":"object"}""",
                        ),
                    ),
                ),
            ),
            toolCallResultsByKey = mapOf(
                ("Linear" to "search_issues") to Result.success(
                    McpToolCallResult(
                        isError = false,
                        content = buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", "ok")
                                },
                            )
                        },
                    ),
                ),
            ),
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = listOf("/mcp 1 search_issues {\"city\":\"Berlin\",\"days\":1}", "/exit")),
            mcpServerStore = RecordingMcpServerStore(loadedServers = listOf(server)),
            mcpRuntimeService = runtimeService,
        )

        controller.runInteractive()

        assertEquals(
            buildJsonObject {
                put("city", "Berlin")
                put("days", 1)
            },
            runtimeService.callToolRequests.single().arguments,
        )
    }

    @Test
    fun mcpToolCommandRejectsOutOfRangeServerIndex() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val io = FakeCliIO(inputs = listOf("/mcp 2 search_issues", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            mcpServerStore = RecordingMcpServerStore(
                loadedServers = listOf(
                    httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true),
                ),
            ),
        )

        controller.runInteractive()

        assertContains(io.outputText(), "system> invalid MCP server index '2'")
    }

    @Test
    fun mcpToolCommandRejectsDisabledServer() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val runtimeService = RecordingMcpRuntimeService()
        val io = FakeCliIO(inputs = listOf("/mcp 1 search_issues", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            mcpServerStore = RecordingMcpServerStore(
                loadedServers = listOf(
                    httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = false),
                ),
            ),
            mcpRuntimeService = runtimeService,
        )

        controller.runInteractive()

        assertTrue(runtimeService.callToolRequests.isEmpty())
        assertContains(io.outputText(), "system> MCP server 'Linear' is disabled; enable the server first")
    }

    @Test
    fun mcpToolCommandRejectsServerThatIsNotReady() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val server = httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true)
        val runtimeService = RecordingMcpRuntimeService(
            runtimeStates = mapOf(
                "Linear" to McpServerRuntimeState(
                    server = server,
                    status = McpRuntimeStatus.FAILED,
                    failureMessage = "Connection refused",
                ),
            ),
        )
        val io = FakeCliIO(inputs = listOf("/mcp 1 search_issues", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            mcpServerStore = RecordingMcpServerStore(loadedServers = listOf(server)),
            mcpRuntimeService = runtimeService,
        )

        controller.runInteractive()

        assertTrue(runtimeService.callToolRequests.isEmpty())
        assertContains(io.outputText(), "system> MCP server 'Linear' is not initialized")
    }

    @Test
    fun mcpToolCommandRejectsInvalidJsonArguments() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val server = httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true)
        val io = FakeCliIO(inputs = listOf("/mcp 1 search_issues {\"city\":", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            mcpServerStore = RecordingMcpServerStore(loadedServers = listOf(server)),
            mcpRuntimeService = RecordingMcpRuntimeService(
                runtimeStates = mapOf(
                    "Linear" to McpServerRuntimeState(
                        server = server,
                        status = McpRuntimeStatus.READY,
                        toolCatalogStatus = McpToolCatalogStatus.LOADED,
                        tools = listOf(
                            McpToolDefinition(
                                name = "search_issues",
                                description = "Search Linear issues",
                                inputSchemaJson = """{"type":"object"}""",
                            ),
                        ),
                    ),
                ),
            ),
        )

        controller.runInteractive()

        assertContains(io.outputText(), "system> MCP tool arguments must be valid JSON")
    }

    @Test
    fun mcpToolCommandRejectsNonObjectJsonArguments() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val server = httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true)
        val io = FakeCliIO(inputs = listOf("/mcp 1 search_issues [1,2,3]", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            mcpServerStore = RecordingMcpServerStore(loadedServers = listOf(server)),
            mcpRuntimeService = RecordingMcpRuntimeService(
                runtimeStates = mapOf(
                    "Linear" to McpServerRuntimeState(
                        server = server,
                        status = McpRuntimeStatus.READY,
                        toolCatalogStatus = McpToolCatalogStatus.LOADED,
                        tools = listOf(
                            McpToolDefinition(
                                name = "search_issues",
                                description = "Search Linear issues",
                                inputSchemaJson = """{"type":"object"}""",
                            ),
                        ),
                    ),
                ),
            ),
        )

        controller.runInteractive()

        assertContains(io.outputText(), "system> MCP tool arguments must be a JSON object")
    }

    @Test
    fun mcpToolCommandRejectsUnknownToolName() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val server = httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true)
        val runtimeService = RecordingMcpRuntimeService(
            runtimeStates = mapOf(
                "Linear" to McpServerRuntimeState(
                    server = server,
                    status = McpRuntimeStatus.READY,
                    toolCatalogStatus = McpToolCatalogStatus.LOADED,
                    tools = listOf(
                        McpToolDefinition(
                            name = "search_issues",
                            description = "Search Linear issues",
                            inputSchemaJson = """{"type":"object"}""",
                        ),
                    ),
                ),
            ),
        )
        val io = FakeCliIO(inputs = listOf("/mcp 1 create_issue", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            mcpServerStore = RecordingMcpServerStore(loadedServers = listOf(server)),
            mcpRuntimeService = runtimeService,
        )

        controller.runInteractive()

        assertTrue(runtimeService.callToolRequests.isEmpty())
        assertContains(io.outputText(), "system> MCP server 'Linear' has no tool named 'create_issue'")
    }

    @Test
    fun mcpToolCommandPrintsRuntimeFailureMessage() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val server = httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true)
        val runtimeService = RecordingMcpRuntimeService(
            runtimeStates = mapOf(
                "Linear" to McpServerRuntimeState(
                    server = server,
                    status = McpRuntimeStatus.READY,
                    toolCatalogStatus = McpToolCatalogStatus.LOADED,
                    tools = listOf(
                        McpToolDefinition(
                            name = "search_issues",
                            description = "Search Linear issues",
                            inputSchemaJson = """{"type":"object"}""",
                        ),
                    ),
                ),
            ),
            toolCallResultsByKey = mapOf(
                ("Linear" to "search_issues") to Result.failure(IllegalStateException("Broken pipe")),
            ),
        )
        val io = FakeCliIO(inputs = listOf("/mcp 1 search_issues", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            mcpServerStore = RecordingMcpServerStore(loadedServers = listOf(server)),
            mcpRuntimeService = runtimeService,
        )

        controller.runInteractive()

        assertContains(io.outputText(), "system> MCP tool 'search_issues' failed: Broken pipe")
    }

    @Test
    fun mcpCommandTogglesSelectedServerAndPersists() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val mcpStore = RecordingMcpServerStore(
            loadedServers = listOf(
                httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true),
                httpMcpServer(name = "GitHub", url = "http://localhost:3001", enabled = false),
            ),
        )
        val io = FakeCliIO(
            inputs = listOf("/mcp", "/exit"),
            mcpSelections = listOf(
                McpMenuResult(action = McpMenuAction.TOGGLE, selectedIndex = 0),
                null,
            ),
        )
        val controller = createController(
            repository = repository,
            io = io,
            mcpServerStore = mcpStore,
        )

        controller.runInteractive()

        assertEquals(1, mcpStore.saveStates.size)
        assertEquals(false, mcpStore.saveStates.single()[0].enabled)
        assertEquals(false, mcpStore.currentServers()[0].enabled)
        assertEquals(false, mcpStore.currentServers()[1].enabled)
    }

    @Test
    fun mcpCommandKeepsMenuOpenForMultipleToggles() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val mcpStore = RecordingMcpServerStore(
            loadedServers = listOf(
                httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true),
                httpMcpServer(name = "GitHub", url = "http://localhost:3001", enabled = false),
            ),
        )
        val io = FakeCliIO(
            inputs = listOf("/mcp", "/exit"),
            mcpSelections = listOf(
                McpMenuResult(action = McpMenuAction.TOGGLE, selectedIndex = 0),
                McpMenuResult(action = McpMenuAction.TOGGLE, selectedIndex = 1),
                null,
            ),
        )
        val controller = createController(
            repository = repository,
            io = io,
            mcpServerStore = mcpStore,
        )

        controller.runInteractive()

        assertEquals(2, mcpStore.saveStates.size)
        assertEquals(false, mcpStore.saveStates[0][0].enabled)
        assertEquals(true, mcpStore.saveStates[1][1].enabled)
        assertEquals(false, mcpStore.currentServers()[0].enabled)
        assertEquals(true, mcpStore.currentServers()[1].enabled)
    }

    @Test
    fun mcpCommandCancelKeepsStateUnchanged() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val mcpStore = RecordingMcpServerStore(
            loadedServers = listOf(
                httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true),
            ),
        )
        val io = FakeCliIO(
            inputs = listOf("/mcp", "/exit"),
            mcpSelections = listOf(null),
        )
        val controller = createController(
            repository = repository,
            io = io,
            mcpServerStore = mcpStore,
        )

        controller.runInteractive()

        assertEquals(0, mcpStore.saveStates.size)
        assertEquals(true, mcpStore.currentServers().single().enabled)
    }

    @Test
    fun mcpCommandSaveFailureRevertsStateAndShowsMessage() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val mcpStore = RecordingMcpServerStore(
            loadedServers = listOf(
                httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true),
            ),
            failOnSaveCalls = setOf(1),
        )
        val io = FakeCliIO(
            inputs = listOf("/mcp", "/exit"),
            mcpSelections = listOf(
                McpMenuResult(action = McpMenuAction.TOGGLE, selectedIndex = 0),
                null,
            ),
        )
        val controller = createController(
            repository = repository,
            io = io,
            mcpServerStore = mcpStore,
        )

        controller.runInteractive()

        assertEquals(0, mcpStore.saveStates.size)
        assertEquals(true, mcpStore.currentServers().single().enabled)
        assertContains(io.outputText(), "system> failed to persist MCP server state")
    }

    @Test
    fun interactiveStartupShowsMcpInitializationFailureMessage() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val io = FakeCliIO(inputs = listOf("/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            mcpServerStore = RecordingMcpServerStore(
                loadedServers = listOf(
                    httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true),
                ),
            ),
            mcpRuntimeService = RecordingMcpRuntimeService(
                runtimeStates = mapOf(
                    "Linear" to McpServerRuntimeState(
                        server = httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true),
                        status = McpRuntimeStatus.FAILED,
                        failureMessage = "Connection refused",
                    ),
                ),
            ),
        )

        controller.runInteractive()

        assertContains(io.outputText(), "system> MCP server 'Linear' initialization failed: Connection refused")
    }

    @Test
    fun interactiveStartupSkipsPublicMcpServersInitialization() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val publicServer = httpMcpServer(
            name = "Weather",
            url = "https://weather.chukai.io/mcp",
            enabled = true,
            isPublic = true,
        )
        val privateServer = httpMcpServer(
            name = "Linear",
            url = "http://localhost:3000",
            enabled = true,
        )
        val runtimeService = RecordingMcpRuntimeService()
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = listOf("/exit")),
            mcpServerStore = RecordingMcpServerStore(
                loadedServers = listOf(publicServer, privateServer),
            ),
            mcpRuntimeService = runtimeService,
        )

        controller.runInteractive()

        assertEquals(1, runtimeService.initializeCalls)
        assertEquals(listOf(listOf(privateServer)), runtimeService.initializeRequests)
    }

    @Test
    fun interactiveMainTurnAttachesMcpCapabilitiesButInvariantValidationRemainsToolFree() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "assistant answer")),
                Result.success(
                    AgentResponse(
                        content = """{"status":"PASS","failed_constraints":[]}""",
                    ),
                ),
            ),
        )
        val privateServer = httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true)
        val runtimeService = RecordingMcpRuntimeService(
            runtimeStates = mapOf(
                "Linear" to McpServerRuntimeState(
                    server = privateServer,
                    status = McpRuntimeStatus.READY,
                    toolCatalogStatus = McpToolCatalogStatus.LOADED,
                    tools = listOf(
                        McpToolDefinition(
                            name = "search_issues",
                            description = "Search Linear issues",
                            inputSchemaJson = """{"type":"object"}""",
                        ),
                    ),
                ),
            ),
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = listOf("Find bugs", "/exit")),
            invariantConstraintStore = RecordingInvariantConstraintStore(
                loadedConstraints = listOf("Always mention test evidence"),
            ),
            mcpServerStore = RecordingMcpServerStore(loadedServers = listOf(privateServer)),
            mcpRuntimeService = runtimeService,
        )

        controller.runInteractive()

        assertEquals(2, repository.prompts.size)
        assertEquals(5, repository.prompts[0].toolCapabilities.privateTools.size)
        assertTrue(repository.prompts[0].toolCapabilities.publicMcpServers.isEmpty())
        assertTrue(repository.prompts[0].toolCapabilities.privateTools.any { tool -> tool.modelToolName == "notify_user" })
        assertTrue(repository.prompts[0].toolCapabilities.privateTools.any { tool -> tool.modelToolName == "scheduler" })
        assertTrue(repository.prompts[1].toolCapabilities.isEmpty())
    }

    @Test
    fun runSinglePromptAttachesMcpCapabilitiesForMainTurn() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "one-shot answer")),
            ),
        )
        val io = FakeCliIO(inputs = emptyList())
        val publicServer = httpMcpServer(
            name = "Weather",
            url = "https://weather.chukai.io/mcp",
            enabled = true,
            isPublic = true,
        )
        val privateServer = httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true)
        val mcpStore = RecordingMcpServerStore(
            loadedServers = listOf(
                publicServer,
                privateServer,
            ),
        )
        val runtimeService = RecordingMcpRuntimeService(
            runtimeStates = mapOf(
                "Linear" to McpServerRuntimeState(
                    server = privateServer,
                    status = McpRuntimeStatus.READY,
                    toolCatalogStatus = McpToolCatalogStatus.LOADED,
                    tools = listOf(
                        McpToolDefinition(
                            name = "search_issues",
                            description = "Search Linear issues",
                            inputSchemaJson = """{"type":"object"}""",
                        ),
                    ),
                ),
            ),
        )
        val controller = createController(
            repository = repository,
            io = io,
            mcpServerStore = mcpStore,
            mcpRuntimeService = runtimeService,
        )

        val exitCode = controller.runSinglePrompt("one-shot question")

        assertEquals(0, exitCode)
        assertEquals(1, mcpStore.loadCalls)
        assertEquals(1, runtimeService.initializeCalls)
        assertEquals(1, repository.prompts.size)
        assertEquals(1, repository.prompts.single().toolCapabilities.publicMcpServers.size)
        assertEquals(5, repository.prompts.single().toolCapabilities.privateTools.size)
        assertTrue(repository.prompts.single().toolCapabilities.privateTools.any { tool -> tool.modelToolName == "notify_user" })
        assertTrue(repository.prompts.single().toolCapabilities.privateTools.any { tool -> tool.modelToolName == "scheduler" })
        assertContains(io.outputText(), "one-shot answer")
    }

    @Test
    fun runSinglePromptExposesNotifyUserBuiltInToolWithoutMcpServers() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "one-shot answer")),
            ),
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = emptyList()),
        )

        val exitCode = controller.runSinglePrompt("notify me when this is done")

        assertEquals(0, exitCode)
        assertEquals(
            listOf("notify_user", "scheduler", "save_to_file", "convert_to_pdf"),
            repository.prompts.single().toolCapabilities.privateTools.map { tool -> tool.modelToolName },
        )
        assertTrue(repository.prompts.single().toolCapabilities.publicMcpServers.isEmpty())
    }

    @Test
    fun runSinglePromptInjectsResolvedCurrentTimeForLocalSchedulePrompt() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "one-shot answer")),
            ),
        )
        val schedulerRecorder = RecordingCurrentTimeBuiltInTool()
        val builtInPrivateToolProvider = BuiltInPrivateToolProvider(
            BuiltInToolRegistry(
                registrations = listOf(
                    BuiltInToolRegistration(
                        definition = BuiltInToolDefinition(
                            toolId = "scheduler",
                            modelToolName = "scheduler",
                            description = "Scheduler test tool",
                            parametersSchema = buildJsonObject {
                                put("type", "object")
                            },
                        ),
                        executor = schedulerRecorder::execute,
                    ),
                ),
            ),
        )
        val io = FakeCliIO(inputs = emptyList())
        val controller = createController(
            repository = repository,
            io = io,
            builtInPrivateToolProvider = builtInPrivateToolProvider,
        )

        val exitCode = controller.runSinglePrompt("Show me test notification at 07:55.")

        assertEquals(0, exitCode)
        assertEquals(1, schedulerRecorder.currentTimeCalls)
        assertTrue(io.liveDialogLines.isEmpty())
        val promptConversation = repository.conversations.single()
        val resolvedTimeMessage = promptConversation.firstOrNull { message ->
            message.role == MessageRole.SYSTEM &&
                message.content.contains("Resolved current local time for this turn")
        } ?: error("Missing resolved current time system message.")
        assertContains(resolvedTimeMessage.content, "2026-03-12T07:54:00+01:00")
        assertContains(resolvedTimeMessage.content, "Europe/Berlin")
    }

    @Test
    fun runSinglePromptDoesNotInjectResolvedCurrentTimeForRelativeSchedulePrompt() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "one-shot answer")),
            ),
        )
        val schedulerRecorder = RecordingCurrentTimeBuiltInTool()
        val builtInPrivateToolProvider = BuiltInPrivateToolProvider(
            BuiltInToolRegistry(
                registrations = listOf(
                    BuiltInToolRegistration(
                        definition = BuiltInToolDefinition(
                            toolId = "scheduler",
                            modelToolName = "scheduler",
                            description = "Scheduler test tool",
                            parametersSchema = buildJsonObject {
                                put("type", "object")
                            },
                        ),
                        executor = schedulerRecorder::execute,
                    ),
                ),
            ),
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = emptyList()),
            builtInPrivateToolProvider = builtInPrivateToolProvider,
        )

        val exitCode = controller.runSinglePrompt("Schedule notification in 5 minutes.")

        assertEquals(0, exitCode)
        assertEquals(0, schedulerRecorder.currentTimeCalls)
        val promptConversation = repository.conversations.single()
        val resolvedTimeMessage = promptConversation.firstOrNull { message ->
            message.role == MessageRole.SYSTEM &&
                message.content.contains("Resolved current local time for this turn")
        }
        assertNull(resolvedTimeMessage)
        val relativeDelayPolicyMessage = promptConversation.firstOrNull { message ->
            message.role == MessageRole.SYSTEM &&
                message.content.contains("Relative scheduling policy for this turn")
        } ?: error("Missing relative scheduling policy system message.")
        assertContains(relativeDelayPolicyMessage.content, "MUST call `scheduler` with `action: \"delay\"`")
        assertContains(relativeDelayPolicyMessage.content, "Omit `schedule_type`")
        assertContains(relativeDelayPolicyMessage.content, "do NOT reject the request as a past time")
    }

    @Test
    fun driveListFilesPrivateToolAddsDriveQueryGuidanceToSchemaAndDescription() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "one-shot answer")),
            ),
        )
        val driveServer = httpMcpServer(name = "Google Drive", url = "http://localhost:3000", enabled = true)
        val runtimeService = RecordingMcpRuntimeService(
            runtimeStates = mapOf(
                "Google Drive" to McpServerRuntimeState(
                    server = driveServer,
                    status = McpRuntimeStatus.READY,
                    toolCatalogStatus = McpToolCatalogStatus.LOADED,
                    tools = listOf(
                        McpToolDefinition(
                            name = "drive_list_files",
                            description = "List Google Drive files using Google Drive's native `q` search syntax.",
                            inputSchemaJson = """
                                {
                                  "type": "object",
                                  "properties": {
                                    "q": {
                                      "type": "string"
                                    }
                                  }
                                }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = emptyList()),
            mcpServerStore = RecordingMcpServerStore(loadedServers = listOf(driveServer)),
            mcpRuntimeService = runtimeService,
        )

        val exitCode = controller.runSinglePrompt("find my reports")

        assertEquals(0, exitCode)
        val privateTool = repository.prompts.single().toolCapabilities.privateTools.first { tool ->
            tool.target is PrivateToolTarget.Mcp
        }
        assertContains(privateTool.description.orEmpty(), "call this tool with `{}` and no arguments")
        val qDescription = privateTool.parametersSchema["properties"]
            ?.jsonObject
            ?.get("q")
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.contentOrNull
        assertNotNull(qDescription)
        assertContains(qDescription, "Omit this field to list recent non-trashed Drive files")
        assertContains(qDescription, "trashed = false and name contains 'report'")
    }

    @Test
    fun interactiveMainTurnLazilyInitializesPrivateServersForLlmExposure() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "assistant answer")),
            ),
        )
        val privateServer = httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true)
        val runtimeService = RecordingMcpRuntimeService(
            runtimeStates = mapOf(
                "Linear" to McpServerRuntimeState(
                    server = privateServer,
                    status = McpRuntimeStatus.READY,
                    toolCatalogStatus = McpToolCatalogStatus.LOADED,
                    tools = listOf(
                        McpToolDefinition(
                            name = "search_issues",
                            description = "Search Linear issues",
                            inputSchemaJson = """{"type":"object"}""",
                        ),
                    ),
                ),
            ),
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = listOf("Find bugs", "/exit")),
            mcpServerStore = RecordingMcpServerStore(loadedServers = listOf(privateServer)),
            mcpRuntimeService = runtimeService,
        )

        controller.runInteractive()

        assertEquals(2, runtimeService.initializeCalls)
        assertEquals(listOf(privateServer), runtimeService.initializeRequests[1])
        assertEquals(5, repository.prompts.single().toolCapabilities.privateTools.size)
        assertTrue(repository.prompts.single().toolCapabilities.privateTools.any { tool -> tool.modelToolName == "notify_user" })
        assertTrue(repository.prompts.single().toolCapabilities.privateTools.any { tool -> tool.modelToolName == "scheduler" })
    }

    @Test
    fun newlyEnabledPrivateServerBecomesAvailableForLlmWithoutRestart() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "assistant answer")),
            ),
        )
        val disabledServer = httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = false)
        val enabledServer = disabledServer.copy(enabled = true)
        val runtimeService = RecordingMcpRuntimeService(
            runtimeStates = mapOf(
                "Linear" to McpServerRuntimeState(
                    server = enabledServer,
                    status = McpRuntimeStatus.READY,
                    toolCatalogStatus = McpToolCatalogStatus.LOADED,
                    tools = listOf(
                        McpToolDefinition(
                            name = "search_issues",
                            description = "Search Linear issues",
                            inputSchemaJson = """{"type":"object"}""",
                        ),
                    ),
                ),
            ),
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(
                inputs = listOf("/mcp", "Find bugs", "/exit"),
                mcpSelections = listOf(
                    McpMenuResult(action = McpMenuAction.TOGGLE, selectedIndex = 0),
                    null,
                ),
            ),
            mcpServerStore = RecordingMcpServerStore(loadedServers = listOf(disabledServer)),
            mcpRuntimeService = runtimeService,
        )

        controller.runInteractive()

        assertEquals(listOf(enabledServer), runtimeService.initializeRequests.last())
        assertEquals(5, repository.prompts.single().toolCapabilities.privateTools.size)
        assertTrue(repository.prompts.single().toolCapabilities.privateTools.any { tool -> tool.modelToolName == "notify_user" })
        assertTrue(repository.prompts.single().toolCapabilities.privateTools.any { tool -> tool.modelToolName == "scheduler" })
    }

    @Test
    fun mcpCommandPassesFailedRuntimeStatusToMenu() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val io = FakeCliIO(
            inputs = listOf("/mcp", "/exit"),
            mcpSelections = listOf(null),
        )
        val controller = createController(
            repository = repository,
            io = io,
            mcpServerStore = RecordingMcpServerStore(
                loadedServers = listOf(
                    httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true),
                ),
            ),
            mcpRuntimeService = RecordingMcpRuntimeService(
                runtimeStates = mapOf(
                    "Linear" to McpServerRuntimeState(
                        server = httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true),
                        status = McpRuntimeStatus.FAILED,
                        failureMessage = "Connection refused",
                    ),
                ),
            ),
        )

        controller.runInteractive()

        assertEquals(McpRuntimeStatus.FAILED, io.mcpMenuOptions.single().runtimeStatus)
    }

    @Test
    fun interactiveStartupShowsMcpToolLoadingFailureMessage() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val io = FakeCliIO(inputs = listOf("/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            mcpServerStore = RecordingMcpServerStore(
                loadedServers = listOf(
                    httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true),
                ),
            ),
            mcpRuntimeService = RecordingMcpRuntimeService(
                runtimeStates = mapOf(
                    "Linear" to McpServerRuntimeState(
                        server = httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true),
                        status = McpRuntimeStatus.READY,
                        toolCatalogStatus = McpToolCatalogStatus.FAILED,
                        toolCatalogFailureMessage = "tools unavailable",
                    ),
                ),
            ),
        )

        controller.runInteractive()

        assertContains(io.outputText(), "system> MCP tools for 'Linear' could not be loaded: tools unavailable")
    }

    @Test
    fun mcpMenuInfoPrintsAllToolsAndDescriptions() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val io = FakeCliIO(
            inputs = listOf("/mcp", "/exit"),
            mcpSelections = listOf(
                McpMenuResult(action = McpMenuAction.INFO, selectedIndex = 0),
            ),
        )
        val controller = createController(
            repository = repository,
            io = io,
            mcpServerStore = RecordingMcpServerStore(
                loadedServers = listOf(
                    httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true),
                ),
            ),
            mcpRuntimeService = RecordingMcpRuntimeService(
                runtimeStates = mapOf(
                    "Linear" to McpServerRuntimeState(
                        server = httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true),
                        status = McpRuntimeStatus.READY,
                        toolCatalogStatus = McpToolCatalogStatus.LOADED,
                        tools = listOf(
                            McpToolDefinition(
                                name = "search_issues",
                                title = "Search issues",
                                description = "Search Linear issues",
                                inputSchemaJson = """{"type":"object"}""",
                            ),
                            McpToolDefinition(
                                name = "create_issue",
                                description = null,
                                inputSchemaJson = """{"type":"object"}""",
                            ),
                        ),
                    ),
                ),
            ),
        )

        controller.runInteractive()

        val output = io.outputText()
        assertContains(output, "mcp> tools for 'Linear'")
        assertContains(output, "1. Search issues (search_issues)")
        assertContains(output, "Search Linear issues")
        assertContains(output, "2. create_issue")
        assertContains(output, "No description provided.")
    }

    @Test
    fun mcpMenuInfoPrintsNoToolsMessageForEmptyCatalog() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val io = FakeCliIO(
            inputs = listOf("/mcp", "/exit"),
            mcpSelections = listOf(
                McpMenuResult(action = McpMenuAction.INFO, selectedIndex = 0),
            ),
        )
        val controller = createController(
            repository = repository,
            io = io,
            mcpServerStore = RecordingMcpServerStore(
                loadedServers = listOf(
                    httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true),
                ),
            ),
            mcpRuntimeService = RecordingMcpRuntimeService(
                runtimeStates = mapOf(
                    "Linear" to McpServerRuntimeState(
                        server = httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true),
                        status = McpRuntimeStatus.READY,
                        toolCatalogStatus = McpToolCatalogStatus.LOADED,
                    ),
                ),
            ),
        )

        controller.runInteractive()

        assertContains(io.outputText(), "system> MCP server 'Linear' has no tools")
    }

    @Test
    fun mcpMenuInfoPrintsCatalogFailureMessage() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val io = FakeCliIO(
            inputs = listOf("/mcp", "/exit"),
            mcpSelections = listOf(
                McpMenuResult(action = McpMenuAction.INFO, selectedIndex = 0),
            ),
        )
        val controller = createController(
            repository = repository,
            io = io,
            mcpServerStore = RecordingMcpServerStore(
                loadedServers = listOf(
                    httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true),
                ),
            ),
            mcpRuntimeService = RecordingMcpRuntimeService(
                runtimeStates = mapOf(
                    "Linear" to McpServerRuntimeState(
                        server = httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true),
                        status = McpRuntimeStatus.READY,
                        toolCatalogStatus = McpToolCatalogStatus.FAILED,
                        toolCatalogFailureMessage = "tools unavailable",
                    ),
                ),
            ),
        )

        controller.runInteractive()

        assertContains(io.outputText(), "system> MCP tools for 'Linear' could not be loaded: tools unavailable")
    }

    @Test
    fun mcpMenuInfoPrintsNotInitializedMessage() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val io = FakeCliIO(
            inputs = listOf("/mcp", "/exit"),
            mcpSelections = listOf(
                McpMenuResult(action = McpMenuAction.INFO, selectedIndex = 0),
            ),
        )
        val controller = createController(
            repository = repository,
            io = io,
            mcpServerStore = RecordingMcpServerStore(
                loadedServers = listOf(
                    httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true),
                ),
            ),
            mcpRuntimeService = RecordingMcpRuntimeService(
                runtimeStates = mapOf(
                    "Linear" to McpServerRuntimeState(
                        server = httpMcpServer(name = "Linear", url = "http://localhost:3000", enabled = true),
                        status = McpRuntimeStatus.FAILED,
                        failureMessage = "Connection refused",
                    ),
                ),
            ),
        )

        controller.runInteractive()

        assertContains(io.outputText(), "system> MCP server 'Linear' is not initialized")
    }

    @Test
    fun compactCommandSwitchesModeAndPersistsIt() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val store = RecordingSessionMemoryStore()
        val io = FakeCliIO(
            inputs = listOf("/compact", "/exit"),
            compactionSelections = listOf(1),
        )
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = store,
            compactionCoordinators = mapOf(
                SessionCompactionMode.ROLLING_SUMMARY to SessionMemoryCompactionCoordinator.disabled(),
                SessionCompactionMode.SLIDING_WINDOW to SessionMemoryCompactionCoordinator.disabled(),
            ),
        )

        controller.runInteractive()

        assertEquals(1, store.saveStates.size)
        assertEquals("sliding-window", store.saveStates.single().activeCompactionModeId)
        assertContains(io.outputText(), "system> compaction strategy set to 'Sliding window'")
    }

    @Test
    fun compactCommandCancelKeepsCurrentModeAndDoesNotPersist() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val store = RecordingSessionMemoryStore()
        val io = FakeCliIO(
            inputs = listOf("/compact", "/exit"),
            compactionSelections = listOf(null),
        )
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = store,
            compactionCoordinators = mapOf(
                SessionCompactionMode.ROLLING_SUMMARY to SessionMemoryCompactionCoordinator.disabled(),
                SessionCompactionMode.SLIDING_WINDOW to SessionMemoryCompactionCoordinator.disabled(),
            ),
        )

        controller.runInteractive()

        assertEquals(0, store.saveStates.size)
        assertFalse(io.outputText().contains("compaction strategy set"))
    }

    @Test
    fun switchingToSlidingWindowClearsPersistedSummaryImmediately() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val store = RecordingSessionMemoryStore(
            loadedState = SessionMemoryState(
                messages = listOf(
                    ConversationMessage.user("old question"),
                    ConversationMessage.assistant("old answer"),
                ),
                compactedSummary = CompactedSessionSummary(
                    strategyId = "rolling-summary-v1",
                    content = "persisted summary",
                ),
                usage = null,
                activeCompactionModeId = "rolling-summary",
            ),
        )
        val io = FakeCliIO(
            inputs = listOf("/compact", "/exit"),
            compactionSelections = listOf(1),
        )
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = store,
            compactionCoordinators = mapOf(
                SessionCompactionMode.ROLLING_SUMMARY to SessionMemoryCompactionCoordinator.disabled(),
                SessionCompactionMode.SLIDING_WINDOW to SessionMemoryCompactionCoordinator.disabled(),
            ),
        )

        controller.runInteractive()

        assertEquals(1, store.saveStates.size)
        assertEquals(null, store.saveStates.single().compactedSummary)
        assertEquals("sliding-window", store.saveStates.single().activeCompactionModeId)
    }

    @Test
    fun switchingToFactMapClearsPersistedSummaryImmediately() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val store = RecordingSessionMemoryStore(
            loadedState = SessionMemoryState(
                messages = listOf(
                    ConversationMessage.user("old question"),
                    ConversationMessage.assistant("old answer"),
                ),
                compactedSummary = CompactedSessionSummary(
                    strategyId = "rolling-summary-v1",
                    content = "persisted summary",
                ),
                usage = null,
                activeCompactionModeId = "rolling-summary",
            ),
        )
        val io = FakeCliIO(
            inputs = listOf("/compact", "/exit"),
            compactionSelections = listOf(2),
        )
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = store,
            compactionCoordinators = mapOf(
                SessionCompactionMode.ROLLING_SUMMARY to SessionMemoryCompactionCoordinator.disabled(),
                SessionCompactionMode.SLIDING_WINDOW to SessionMemoryCompactionCoordinator.disabled(),
                SessionCompactionMode.FACT_MAP to SessionMemoryCompactionCoordinator.disabled(),
            ),
        )

        controller.runInteractive()

        assertEquals(1, store.saveStates.size)
        assertEquals(null, store.saveStates.single().compactedSummary)
        assertEquals("fact-map", store.saveStates.single().activeCompactionModeId)
    }

    @Test
    fun slidingWindowModeCompactsToLastTenMessagesWithoutSummaryInjection() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = (1..7).map { index ->
                Result.success(AgentResponse(content = "answer $index"))
            },
        )
        val store = RecordingSessionMemoryStore()
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = (1..7).map { index -> "prompt $index" } + "/exit"),
            sessionMemoryStore = store,
            compactionCoordinators = mapOf(
                SessionCompactionMode.ROLLING_SUMMARY to SessionMemoryCompactionCoordinator.disabled(),
                SessionCompactionMode.SLIDING_WINDOW to SessionMemoryCompactionCoordinator(
                    startPolicy = SlidingWindowCompactionStartPolicy(maxMessages = 10),
                    strategy = SlidingWindowCompactionStrategy(),
                ),
            ),
            defaultCompactionMode = SessionCompactionMode.SLIDING_WINDOW,
        )

        controller.runInteractive()

        val requestAfterCompaction = repository.conversations[6]
        assertEquals(MessageRole.SYSTEM, requestAfterCompaction.first().role)
        assertFalse(requestAfterCompaction.drop(1).any { message -> message.role == MessageRole.SYSTEM })
        assertEquals("prompt 2", requestAfterCompaction[1].content)
        assertEquals("prompt 7", requestAfterCompaction.last().content)

        val finalSaved = store.saveStates.last()
        assertEquals(10, finalSaved.messages.size)
        assertEquals("prompt 3", finalSaved.messages[0].content)
        assertEquals(null, finalSaved.compactedSummary)
        assertEquals("sliding-window", finalSaved.activeCompactionModeId)
    }

    @Test
    fun factMapModeCompactsToLastTenMessagesAndInjectsSummary() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = (1..7).map { index ->
                Result.success(AgentResponse(content = "answer $index"))
            },
        )
        val store = RecordingSessionMemoryStore()
        val strategy = RecordingCompactionStrategy(
            summariesToReturn = listOf(
                """
                {
                  "goal": "implement fact map",
                  "constraints": ["keep 10 messages"],
                  "decisions": ["use fact-map strategy"],
                  "preferences": [],
                  "agreements": ["ship in this iteration"]
                }
                """.trimIndent(),
                """
                {
                  "goal": "implement fact map",
                  "constraints": ["keep 10 messages"],
                  "decisions": ["use fact-map strategy"],
                  "preferences": [],
                  "agreements": ["ship in this iteration"]
                }
                """.trimIndent(),
            ),
            id = "fact-map-v1",
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = (1..7).map { index -> "prompt $index" } + "/exit"),
            sessionMemoryStore = store,
            compactionCoordinators = mapOf(
                SessionCompactionMode.FACT_MAP to SessionMemoryCompactionCoordinator(
                    startPolicy = SlidingWindowCompactionStartPolicy(maxMessages = 10),
                    strategy = strategy,
                ),
            ),
            defaultCompactionMode = SessionCompactionMode.FACT_MAP,
        )

        controller.runInteractive()

        assertEquals(2, strategy.previousSummaries.size)
        assertEquals(null, strategy.previousSummaries[0])
        assertContains(strategy.previousSummaries[1].orEmpty(), "\"goal\": \"implement fact map\"")
        assertEquals(2, strategy.compactedMessageBatches.size)
        assertEquals(2, strategy.compactedMessageBatches[0].size)
        assertEquals(2, strategy.compactedMessageBatches[1].size)
        assertEquals("prompt 1", strategy.compactedMessageBatches[0][0].content)
        assertEquals("answer 1", strategy.compactedMessageBatches[0][1].content)
        assertEquals("prompt 2", strategy.compactedMessageBatches[1][0].content)
        assertEquals("answer 2", strategy.compactedMessageBatches[1][1].content)

        val requestAfterCompaction = repository.conversations[6]
        assertEquals(
            listOf(
                MessageRole.SYSTEM,
                MessageRole.SYSTEM,
                MessageRole.USER,
                MessageRole.ASSISTANT,
                MessageRole.USER,
                MessageRole.ASSISTANT,
                MessageRole.USER,
                MessageRole.ASSISTANT,
                MessageRole.USER,
                MessageRole.ASSISTANT,
                MessageRole.USER,
                MessageRole.ASSISTANT,
                MessageRole.USER,
            ),
            requestAfterCompaction.map { it.role },
        )
        assertContains(requestAfterCompaction[1].content, "\"goal\": \"implement fact map\"")
        assertEquals("prompt 2", requestAfterCompaction[2].content)
        assertEquals("prompt 7", requestAfterCompaction.last().content)

        val finalSaved = store.saveStates.last()
        assertEquals(10, finalSaved.messages.size)
        assertEquals("prompt 3", finalSaved.messages[0].content)
        assertEquals("fact-map-v1", finalSaved.compactedSummary?.strategyId)
        assertContains(finalSaved.compactedSummary?.content.orEmpty(), "\"goal\": \"implement fact map\"")
        assertEquals("fact-map", finalSaved.activeCompactionModeId)
    }

    @Test
    fun compactCommandCanSelectBranchingMode() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val store = RecordingSessionMemoryStore()
        val io = FakeCliIO(
            inputs = listOf("/compact", "/exit"),
            compactionSelections = listOf(1),
        )
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = store,
            compactionCoordinators = mapOf(
                SessionCompactionMode.ROLLING_SUMMARY to SessionMemoryCompactionCoordinator.disabled(),
                SessionCompactionMode.BRANCHING to SessionMemoryCompactionCoordinator.disabled(),
            ),
        )

        controller.runInteractive()

        assertEquals(1, store.saveStates.size)
        assertEquals("branching", store.saveStates.single().activeCompactionModeId)
        assertNotNull(store.saveStates.single().branchingState)
        assertContains(io.outputText(), "system> compaction strategy set to 'Branching'")
    }

    @Test
    fun switchingToBranchingResetsLinearConversationAndPersistsFreshBranchingState() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val store = RecordingSessionMemoryStore(
            loadedState = SessionMemoryState(
                messages = listOf(
                    ConversationMessage.user("old question"),
                    ConversationMessage.assistant("old answer"),
                ),
                compactedSummary = CompactedSessionSummary(
                    strategyId = "rolling-summary-v1",
                    content = "persisted summary",
                ),
                usage = null,
                activeCompactionModeId = "rolling-summary",
            ),
        )
        val io = FakeCliIO(
            inputs = listOf("/compact", "/exit"),
            compactionSelections = listOf(1),
        )
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = store,
            compactionCoordinators = mapOf(
                SessionCompactionMode.ROLLING_SUMMARY to SessionMemoryCompactionCoordinator.disabled(),
                SessionCompactionMode.BRANCHING to SessionMemoryCompactionCoordinator.disabled(),
            ),
        )

        controller.runInteractive()

        val saved = store.saveStates.single()
        assertEquals(emptyList(), saved.messages)
        assertEquals(null, saved.compactedSummary)
        assertEquals("branching", saved.activeCompactionModeId)
        val branchingState = assertNotNull(saved.branchingState)
        assertEquals("", branchingState.activeTopicKey)
        assertEquals("", branchingState.activeSubtopicKey)
        assertTrue(branchingState.topics.isEmpty())
    }

    @Test
    fun switchingFromBranchingToLinearModeResetsConversationAndClearsBranchingState() = runBlocking {
        val repository = RecordingAgentRepository(responses = emptyList())
        val store = RecordingSessionMemoryStore(
            loadedState = SessionMemoryState(
                messages = listOf(
                    ConversationMessage.user("legacy question"),
                    ConversationMessage.assistant("legacy answer"),
                ),
                compactedSummary = null,
                usage = null,
                activeCompactionModeId = "branching",
                branchingState = BranchingMemoryState(
                    activeTopicKey = "building new application",
                    activeSubtopicKey = "architecture",
                    topics = listOf(
                        TopicBranchState(
                            key = "building new application",
                            displayName = "Building new application",
                            rollingSummary = "summary one",
                            subtopics = listOf(
                                SubtopicBranchState(
                                    key = "architecture",
                                    displayName = "Architecture",
                                    messages = listOf(
                                        ConversationMessage.user("old q"),
                                        ConversationMessage.assistant("old a"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val io = FakeCliIO(
            inputs = listOf("/compact", "/exit"),
            compactionSelections = listOf(0),
        )
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = store,
            compactionCoordinators = mapOf(
                SessionCompactionMode.ROLLING_SUMMARY to SessionMemoryCompactionCoordinator.disabled(),
                SessionCompactionMode.BRANCHING to SessionMemoryCompactionCoordinator.disabled(),
            ),
            defaultCompactionMode = SessionCompactionMode.ROLLING_SUMMARY,
        )

        controller.runInteractive()

        val saved = store.saveStates.single()
        assertEquals(emptyList(), saved.messages)
        assertEquals("rolling-summary", saved.activeCompactionModeId)
        assertEquals(null, saved.branchingState)
    }

    @Test
    fun branchingModeEmitsNewTopicAndSubtopicMessages() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "assistant answer")),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "topic": {"kind": "new", "key": "", "name": "Building new application"},
                          "subtopic": {"kind": "new", "key": "", "name": "Architecture"}
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "allowNewTopic": true,
                          "reuseTopicKey": "",
                          "allowNewSubtopic": true,
                          "reuseSubtopicKey": ""
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(AgentResponse(content = "updated topic summary")),
            ),
        )
        val store = RecordingSessionMemoryStore()
        val io = FakeCliIO(inputs = listOf("Create architecture", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = store,
            compactionCoordinators = mapOf(
                SessionCompactionMode.BRANCHING to SessionMemoryCompactionCoordinator.disabled(),
            ),
            defaultCompactionMode = SessionCompactionMode.BRANCHING,
        )

        controller.runInteractive()

        val output = io.outputText()
        assertContains(output, "system> new topic found: 'Building new application'")
        assertContains(output, "system> new subtopic found in 'Building new application': 'Architecture'")
        val branchingState = assertNotNull(store.saveStates.last().branchingState)
        assertEquals("building new application", branchingState.activeTopicKey)
        assertEquals("architecture", branchingState.activeSubtopicKey)
    }

    @Test
    fun branchingModeEmitsSwitchMessageWhenClassifierReturnsExistingBranch() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "answer one")),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "topic": {"kind": "new", "key": "", "name": "Building new application"},
                          "subtopic": {"kind": "new", "key": "", "name": "Architecture"}
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "allowNewTopic": true,
                          "reuseTopicKey": "",
                          "allowNewSubtopic": true,
                          "reuseSubtopicKey": ""
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(AgentResponse(content = "summary one")),
                Result.success(AgentResponse(content = "answer two")),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "topic": {"kind": "existing", "key": "building new application", "name": "Building new application"},
                          "subtopic": {"kind": "new", "key": "", "name": "Network API"}
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "createNewTopic": false,
                          "topic": "",
                          "subtopic": ""
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "allowNewTopic": false,
                          "reuseTopicKey": "building new application",
                          "allowNewSubtopic": true,
                          "reuseSubtopicKey": ""
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(AgentResponse(content = "summary two")),
                Result.success(AgentResponse(content = "answer three")),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "topic": {"kind": "existing", "key": "building new application", "name": "Building new application"},
                          "subtopic": {"kind": "existing", "key": "architecture", "name": "Architecture"}
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(AgentResponse(content = "summary three")),
            ),
        )
        val io = FakeCliIO(inputs = listOf("prompt one", "prompt two", "prompt three", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            compactionCoordinators = mapOf(
                SessionCompactionMode.BRANCHING to SessionMemoryCompactionCoordinator.disabled(),
            ),
            defaultCompactionMode = SessionCompactionMode.BRANCHING,
        )

        controller.runInteractive()

        assertContains(
            io.outputText(),
            "system> switched to topic 'Building new application' / subtopic 'Architecture'",
        )
    }

    @Test
    fun branchingClassificationRetriesThenFallsBackWithWarning() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "assistant answer")),
                Result.success(AgentResponse(content = "not json")),
                Result.success(AgentResponse(content = "still not json")),
                Result.success(AgentResponse(content = "summary one")),
            ),
        )
        val io = FakeCliIO(inputs = listOf("prompt one", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            compactionCoordinators = mapOf(
                SessionCompactionMode.BRANCHING to SessionMemoryCompactionCoordinator.disabled(),
            ),
            defaultCompactionMode = SessionCompactionMode.BRANCHING,
        )

        controller.runInteractive()

        assertContains(io.outputText(), "system> branch classification failed twice; using strict specific fallback routing")
        assertEquals(4, repository.conversations.size)
    }

    @Test
    fun branchingValidationRejectsNewSubtopicAndKeepsExistingDesignSubtopic() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "answer one")),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "topic": {"kind": "new", "key": "", "name": "Game Development"},
                          "subtopic": {"kind": "new", "key": "", "name": "Game Design"}
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "allowNewTopic": true,
                          "reuseTopicKey": "",
                          "allowNewSubtopic": true,
                          "reuseSubtopicKey": ""
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(AgentResponse(content = "summary one")),
                Result.success(AgentResponse(content = "answer two")),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "topic": {"kind": "existing", "key": "game development", "name": "Game Development"},
                          "subtopic": {"kind": "new", "key": "", "name": "Characters System"}
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "allowNewTopic": false,
                          "reuseTopicKey": "game development",
                          "allowNewSubtopic": false,
                          "reuseSubtopicKey": "game design"
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(AgentResponse(content = "summary two")),
            ),
        )
        val store = RecordingSessionMemoryStore()
        val io = FakeCliIO(inputs = listOf("Start game design", "Discuss character traits", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = store,
            compactionCoordinators = mapOf(
                SessionCompactionMode.BRANCHING to SessionMemoryCompactionCoordinator.disabled(),
            ),
            defaultCompactionMode = SessionCompactionMode.BRANCHING,
        )

        controller.runInteractive()

        val output = io.outputText()
        assertFalse(output.contains("new subtopic found in 'Game Development': 'Characters System'"))
        val finalState = assertNotNull(store.saveStates.last().branchingState)
        assertEquals("game development", finalState.activeTopicKey)
        assertEquals("game design", finalState.activeSubtopicKey)
    }

    @Test
    fun branchingTopicShiftDetectorCreatesNewTopicForUnrelatedDomain() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "answer one")),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "topic": {"kind": "new", "key": "", "name": "Game Development"},
                          "subtopic": {"kind": "new", "key": "", "name": "Game Design"}
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "allowNewTopic": true,
                          "reuseTopicKey": "",
                          "allowNewSubtopic": true,
                          "reuseSubtopicKey": ""
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(AgentResponse(content = "summary one")),
                Result.success(AgentResponse(content = "answer two")),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "topic": {"kind": "existing", "key": "game development", "name": "Game Development"},
                          "subtopic": {"kind": "existing", "key": "game design", "name": "Game Design"}
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "createNewTopic": true,
                          "topic": "Apartment Painting",
                          "subtopic": "Wall Preparation"
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(AgentResponse(content = "summary two")),
            ),
        )
        val store = RecordingSessionMemoryStore()
        val io = FakeCliIO(inputs = listOf("Let's design RPG classes", "Need help painting apartment walls", "/exit"))
        val controller = createController(
            repository = repository,
            io = io,
            sessionMemoryStore = store,
            compactionCoordinators = mapOf(
                SessionCompactionMode.BRANCHING to SessionMemoryCompactionCoordinator.disabled(),
            ),
            defaultCompactionMode = SessionCompactionMode.BRANCHING,
        )

        controller.runInteractive()

        val output = io.outputText()
        assertContains(output, "system> new topic found: 'Apartment Painting'")
        assertContains(output, "system> new subtopic found in 'Apartment Painting': 'Wall Preparation'")
        val finalState = assertNotNull(store.saveStates.last().branchingState)
        assertEquals("apartment painting", finalState.activeTopicKey)
        assertEquals("wall preparation", finalState.activeSubtopicKey)
    }

    @Test
    fun branchingClassificationAfterReplyRoutesNextTurnContext() = runBlocking {
        val repository = RecordingAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "answer one")),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "topic": {"kind": "new", "key": "", "name": "Building new application"},
                          "subtopic": {"kind": "new", "key": "", "name": "Architecture"}
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "allowNewTopic": true,
                          "reuseTopicKey": "",
                          "allowNewSubtopic": true,
                          "reuseSubtopicKey": ""
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(AgentResponse(content = "summary one")),
                Result.success(AgentResponse(content = "answer two")),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "topic": {"kind": "existing", "key": "building new application", "name": "Building new application"},
                          "subtopic": {"kind": "existing", "key": "architecture", "name": "Architecture"}
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "createNewTopic": false,
                          "topic": "",
                          "subtopic": ""
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(AgentResponse(content = "summary two")),
            ),
        )
        val controller = createController(
            repository = repository,
            io = FakeCliIO(inputs = listOf("prompt one", "prompt two", "/exit")),
            compactionCoordinators = mapOf(
                SessionCompactionMode.BRANCHING to SessionMemoryCompactionCoordinator.disabled(),
            ),
            defaultCompactionMode = SessionCompactionMode.BRANCHING,
        )

        controller.runInteractive()

        val secondTurnMainRequest = repository.conversations[4]
        assertEquals(
            listOf(
                MessageRole.SYSTEM,
                MessageRole.SYSTEM,
                MessageRole.USER,
                MessageRole.ASSISTANT,
                MessageRole.USER,
            ),
            secondTurnMainRequest.map { it.role },
        )
        assertContains(secondTurnMainRequest[1].content, "summary one")
        assertEquals("prompt one", secondTurnMainRequest[2].content)
        assertEquals("answer one", secondTurnMainRequest[3].content)
        assertEquals("prompt two", secondTurnMainRequest[4].content)
    }

    private fun createController(
        repository: RecordingAgentRepository,
        io: CliIO,
        apiSettingsService: ApiSettingsService = MutableApiSettingsService(defaultApiSettings()),
        apiSettingsStore: ApiSettingsStore? = null,
        availableModels: List<ModelProperties> = defaultModels(),
        sessionMemoryStore: SessionMemoryStore? = null,
        userDefinedProfileStore: UserDefinedProfileStore? = null,
        userDefinedWorkflowStore: UserDefinedWorkflowStore? = null,
        invariantConstraintStore: InvariantConstraintStore? = null,
        mcpServerStore: McpServerStore? = null,
        mcpRuntimeService: McpRuntimeService = RecordingMcpRuntimeService(),
        builtInPrivateToolProvider: BuiltInPrivateToolProvider = BuiltInPrivateToolProvider(BuiltInToolRegistry.createDefault()),
        wireAppRagRetriever: WireAppRagRetriever = RecordingWireAppRagRetriever(),
        persistentMemoryEnabled: Boolean = true,
        fileReferenceReader: FileReferenceReader = RecordingFileReferenceReader(emptyMap()),
        compactionCoordinators: Map<SessionCompactionMode, SessionMemoryCompactionCoordinator> = mapOf(
            SessionCompactionMode.ROLLING_SUMMARY to SessionMemoryCompactionCoordinator.disabled(),
        ),
        defaultCompactionMode: SessionCompactionMode = SessionCompactionMode.ROLLING_SUMMARY,
    ): ConsoleChatController {
        return ConsoleChatController(
            sendPromptUseCase = SendPromptUseCase(repository),
            initialSystemPrompt = "Base system prompt",
            apiSettingsService = apiSettingsService,
            apiSettingsStore = apiSettingsStore,
            availableModels = availableModels,
            io = io,
            sessionMemoryStore = sessionMemoryStore,
            userDefinedProfileStore = userDefinedProfileStore,
            userDefinedWorkflowStore = userDefinedWorkflowStore,
            invariantConstraintStore = invariantConstraintStore,
            mcpServerStore = mcpServerStore,
            mcpRuntimeService = mcpRuntimeService,
            builtInPrivateToolProvider = builtInPrivateToolProvider,
            wireAppRagRetriever = wireAppRagRetriever,
            persistentMemoryEnabled = persistentMemoryEnabled,
            fileReferenceReader = fileReferenceReader,
            compactionCoordinators = compactionCoordinators,
            defaultCompactionMode = defaultCompactionMode,
        )
    }
}

internal fun defaultApiSettings(): ApiSettings {
    return ApiSettings(
        activeApiId = "prod",
        apis = listOf(
            ConfiguredApi(
                id = "prod",
                name = "Production",
                baseUrl = "https://api.openai.com/v1",
                apiKey = "test-key",
                availableModels = listOf("gpt-4.1-mini", "gpt-4.1-nano"),
                defaultModel = "gpt-4.1-mini",
                selectedModel = "gpt-4.1-mini",
            ),
            ConfiguredApi(
                id = "local",
                name = "Local",
                baseUrl = "http://127.0.0.1:11434/v1",
                apiKey = "local-key",
                availableModels = listOf("gpt-4.1-mini", "gpt-4.1-nano"),
                defaultModel = "gpt-4.1-mini",
                selectedModel = "gpt-4.1-nano",
            ),
        ),
    )
}

internal fun defaultModels(): List<ModelProperties> {
    return listOf(
        ModelProperties(
            id = "gpt-4.1-mini",
            pricing = ModelPricing(
                inputUsdPer1M = 0.40,
                outputUsdPer1M = 1.60,
            ),
            contextWindowTokens = 1_047_576,
        ),
        ModelProperties(
            id = "gpt-4.1-nano",
            pricing = ModelPricing(
                inputUsdPer1M = 0.10,
                outputUsdPer1M = 0.40,
            ),
            contextWindowTokens = 1_047_576,
        ),
    )
}

private class RecordingAgentRepository(
    responses: List<Result<AgentResponse>>,
    toolTraceEvents: List<List<ToolCallTraceEvent>> = emptyList(),
) : AgentRepository {
    private val queuedResponses = ArrayDeque(responses)
    private val queuedToolTraceEvents = ArrayDeque(toolTraceEvents)
    val prompts = mutableListOf<PromptRequestData>()
    val conversations = mutableListOf<List<ConversationMessage>>()
    val requestedModels = mutableListOf<String?>()
    val requestedTemperatures = mutableListOf<Double?>()

    override suspend fun complete(
        prompt: PromptRequestData,
        temperature: Double?,
        model: String?,
    ): AgentResponse {
        return complete(
            prompt = prompt,
            temperature = temperature,
            model = model,
            toolCallTraceObserver = null,
        )
    }

    override suspend fun complete(
        prompt: PromptRequestData,
        temperature: Double?,
        model: String?,
        toolCallTraceObserver: ToolCallTraceObserver?,
    ): AgentResponse {
        prompts += prompt
        conversations += prompt.toConversation()
        requestedModels += model
        requestedTemperatures += temperature
        queuedToolTraceEvents.removeFirstOrNull().orEmpty().forEach { event ->
            toolCallTraceObserver?.onToolCallTrace(event)
        }
        val response = queuedResponses.removeFirstOrNull()
            ?: error("No prepared response for conversation #${conversations.size}")
        return response.getOrThrow()
    }
}

private class RecordingCurrentTimeBuiltInTool {
    var currentTimeCalls: Int = 0
        private set

    suspend fun execute(arguments: JsonObject): PrivateToolResult {
        if (arguments["action"]?.jsonPrimitive?.content == "current_time") {
            currentTimeCalls += 1
        }
        return PrivateToolResult(
            isError = false,
            content = JsonArray(
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", "Current local time is 2026-03-12T07:54:00+01:00 in Europe/Berlin.")
                        },
                    )
                },
            ),
            structuredContent = buildJsonObject {
                put("action", "current_time")
                put(
                    "current_time",
                    buildJsonObject {
                        put("local_time", "2026-03-12T07:54:00+01:00")
                        put("timezone", "Europe/Berlin")
                        put("utc_time", "2026-03-12T06:54:00Z")
                        put("unix_epoch_seconds", 1773298440)
                    },
                )
            },
        )
    }
}

private class RecordingWireAppRagRetriever(
    results: List<Result<List<RagRetrievedChunk>>> = emptyList(),
) : WireAppRagRetriever {
    private val queuedResults = ArrayDeque(results)
    val questions = mutableListOf<String>()

    override suspend fun retrieve(question: String): List<RagRetrievedChunk> {
        questions += question
        return queuedResults.removeFirstOrNull()
            ?.getOrThrow()
            ?: emptyList()
    }
}

private fun httpMcpServer(name: String, url: String, enabled: Boolean, isPublic: Boolean = false): McpServerConfig {
    return McpServerConfig(
        name = name,
        enabled = enabled,
        isPublic = isPublic,
        transport = McpTransportConfig.Http(url = url),
    )
}

private class FakeCliIO(
    inputs: List<String>,
    private val compactionSelections: List<Int?> = emptyList(),
    private val apiSelections: List<Int?> = emptyList(),
    private val mcpSelections: List<McpMenuResult?> = emptyList(),
    private val profileSelections: List<Int?> = emptyList(),
    private val workflowSelections: List<Int?> = emptyList(),
    private val invariantSelections: List<InvariantMenuResult?> = emptyList(),
) : CliIO {
    private val queuedInputs = ArrayDeque<String?>(inputs)
    private var nextCompactionSelectionIndex = 0
    private var nextApiSelectionIndex = 0
    private var nextMcpSelectionIndex = 0
    private var nextProfileSelectionIndex = 0
    private var nextWorkflowSelectionIndex = 0
    private var nextInvariantSelectionIndex = 0
    private val lines = mutableListOf<String>()
    var showThinkingIndicatorCalls: Int = 0
        private set
    var updateThinkingIndicatorCalls: Int = 0
        private set
    var lastThinkingProgressText: String? = null
        private set
    var hideThinkingIndicatorCalls: Int = 0
        private set
    val liveDialogLines = mutableListOf<String>()
    val footerLabels = mutableListOf<String?>()
    val footerPrompts = mutableListOf<String>()
    val footerCommandDescriptors = mutableListOf<List<CliCommandDescriptor>>()
    val apiMenuOptions = mutableListOf<String>()
    val mcpMenuOptions = mutableListOf<McpMenuOption>()

    override fun clearScreen() = Unit

    override fun hideCursor() = Unit

    override fun showCursor() = Unit

    override fun writeLine(text: String) {
        lines += text
    }

    override fun readLine(prompt: String): String? = nextInput()

    override fun readLineInFooter(
        prompt: String,
        divider: String,
        footerLabel: String?,
        commandDescriptors: List<CliCommandDescriptor>,
    ): String? {
        footerPrompts += prompt
        footerLabels += footerLabel
        footerCommandDescriptors += listOf(commandDescriptors)
        return nextInput()
    }

    override fun showThinkingIndicator() {
        showThinkingIndicatorCalls += 1
    }

    override fun updateThinkingIndicator(progressText: String) {
        updateThinkingIndicatorCalls += 1
        lastThinkingProgressText = progressText
    }

    override fun writeLiveDialogLine(text: String) {
        liveDialogLines += text
    }

    override fun hideThinkingIndicator() {
        hideThinkingIndicatorCalls += 1
    }

    override fun openCompactionMenu(options: List<String>, currentSelection: Int): Int? {
        val selection = compactionSelections.getOrNull(nextCompactionSelectionIndex)
        if (nextCompactionSelectionIndex < compactionSelections.size) {
            nextCompactionSelectionIndex += 1
            return selection
        }
        return currentSelection
    }

    override fun openApiMenu(options: List<String>, currentSelection: Int): Int? {
        apiMenuOptions.clear()
        apiMenuOptions += options
        val selection = apiSelections.getOrNull(nextApiSelectionIndex)
        if (nextApiSelectionIndex < apiSelections.size) {
            nextApiSelectionIndex += 1
            return selection
        }
        return currentSelection
    }

    override fun openMcpMenu(options: List<McpMenuOption>, currentSelection: Int, reuseAnchor: Boolean): McpMenuResult? {
        mcpMenuOptions.clear()
        mcpMenuOptions += options
        val selection = mcpSelections.getOrNull(nextMcpSelectionIndex)
        if (nextMcpSelectionIndex < mcpSelections.size) {
            nextMcpSelectionIndex += 1
            return selection
        }
        return McpMenuResult(
            action = McpMenuAction.TOGGLE,
            selectedIndex = currentSelection,
        )
    }

    override fun openProfileMenu(options: List<String>, currentSelection: Int): Int? {
        val selection = profileSelections.getOrNull(nextProfileSelectionIndex)
        if (nextProfileSelectionIndex < profileSelections.size) {
            nextProfileSelectionIndex += 1
            return selection
        }
        return currentSelection
    }

    override fun openWorkflowMenu(options: List<String>, currentSelection: Int): Int? {
        val selection = workflowSelections.getOrNull(nextWorkflowSelectionIndex)
        if (nextWorkflowSelectionIndex < workflowSelections.size) {
            nextWorkflowSelectionIndex += 1
            return selection
        }
        return currentSelection
    }

    override fun openInvariantMenu(options: List<String>, currentSelection: Int): InvariantMenuResult? {
        val selection = invariantSelections.getOrNull(nextInvariantSelectionIndex)
        if (nextInvariantSelectionIndex < invariantSelections.size) {
            nextInvariantSelectionIndex += 1
            return selection
        }
        return InvariantMenuResult(
            action = InvariantMenuAction.CONFIRM,
            selectedIndex = currentSelection,
        )
    }

    private fun nextInput(): String? = queuedInputs.removeFirstOrNull()

    fun outputText(): String = lines.joinToString(separator = "\n")
}

private class RecordingApiSettingsStore(
    loadedSettings: ApiSettings? = null,
    private val failOnSaveCalls: Set<Int> = emptySet(),
) : ApiSettingsStore {
    private var settings: ApiSettings? = loadedSettings
    private var saveCallCount: Int = 0
    val saveStates = mutableListOf<ApiSettings>()

    override fun load(): ApiSettings? = settings

    override fun save(settings: ApiSettings) {
        saveCallCount += 1
        if (saveCallCount in failOnSaveCalls) {
            error("save failure")
        }
        this.settings = settings
        saveStates += settings
    }
}

private class RecordingMcpServerStore(
    loadedServers: List<McpServerConfig> = emptyList(),
    private val failOnSaveCalls: Set<Int> = emptySet(),
) : McpServerStore {
    private var servers = loadedServers.map { server -> server.copy() }.toMutableList()
    var loadCalls: Int = 0
        private set
    private var saveCallCount: Int = 0
    val saveStates = mutableListOf<List<McpServerConfig>>()

    override fun load(): List<McpServerConfig> {
        loadCalls += 1
        return servers.map { server -> server.copy() }
    }

    override fun save(servers: List<McpServerConfig>) {
        saveCallCount += 1
        if (saveCallCount in failOnSaveCalls) {
            error("save failure")
        }
        this.servers = servers.map { server -> server.copy() }.toMutableList()
        saveStates += this.servers.map { server -> server.copy() }
    }

    fun currentServers(): List<McpServerConfig> = servers.map { server -> server.copy() }
}

private class RecordingMcpRuntimeService(
    private val runtimeStates: Map<String, McpServerRuntimeState> = emptyMap(),
    private val toolCallResultsByKey: Map<Pair<String, String>, Result<McpToolCallResult>> = emptyMap(),
) : McpRuntimeService {
    var initializeCalls: Int = 0
        private set
    val initializeRequests = mutableListOf<List<McpServerConfig>>()
    val callToolRequests = mutableListOf<RecordedMcpToolCall>()

    override suspend fun initializeEnabledServers(servers: List<McpServerConfig>): List<McpServerRuntimeState> {
        initializeCalls += 1
        initializeRequests += servers.map { server -> server.copy() }
        return servers.map(::runtimeStateFor)
    }

    override suspend fun callTool(server: McpServerConfig, toolName: String, arguments: JsonObject): McpToolCallResult {
        callToolRequests += RecordedMcpToolCall(
            server = server,
            toolName = toolName,
            arguments = arguments,
        )
        return toolCallResultsByKey[server.name to toolName]
            ?.getOrThrow()
            ?: error("No prepared MCP tool result for ${server.name}/$toolName")
    }

    override fun runtimeStateFor(server: McpServerConfig): McpServerRuntimeState {
        return runtimeStates[server.name] ?: McpServerRuntimeState(
            server = server,
            status = if (server.enabled) McpRuntimeStatus.NOT_ATTEMPTED else McpRuntimeStatus.DISABLED,
        )
    }

    override fun toolCatalogFor(server: McpServerConfig): McpToolCatalogState {
        val state = runtimeStateFor(server)
        return McpToolCatalogState(
            server = server,
            status = state.toolCatalogStatus,
            tools = state.tools,
            failureMessage = state.toolCatalogFailureMessage,
        )
    }

    override fun runtimeStates(): List<McpServerRuntimeState> = runtimeStates.values.toList()

    override fun connectedSession(serverName: String): McpConnectedSession? = null

    override fun clearFailureState(server: McpServerConfig) = Unit

    override suspend fun close() = Unit
}

private data class RecordedMcpToolCall(
    val server: McpServerConfig,
    val toolName: String,
    val arguments: JsonObject,
)

private class RecordingSelectableUserDefinedWorkflowStore(
    private val workflows: List<UserWorkflowOption>,
    private val workflowsByFileName: Map<String, UserWorkflowDefinition> = emptyMap(),
    private var activeFileName: String? = null,
) : UserDefinedWorkflowStore {
    val setActiveCalls = mutableListOf<String>()

    override fun listWorkflows(): List<UserWorkflowOption> = workflows.toList()

    override fun loadActiveWorkflow(): UserWorkflowDefinition? {
        val fileName = activeFileName ?: workflows.firstOrNull()?.fileName ?: return null
        return workflowsByFileName[fileName]
    }

    override fun activeWorkflowFileName(): String? = activeFileName

    override fun setActiveWorkflow(fileName: String): Boolean {
        if (workflows.none { workflow -> workflow.fileName == fileName }) {
            return false
        }
        setActiveCalls += fileName
        activeFileName = fileName
        return true
    }
}

private class RecordingSelectableUserDefinedProfileStore(
    private val profiles: List<UserProfileOption>,
    private val profilesByFileName: Map<String, ProfilePreferenceState> = emptyMap(),
    private var activeFileName: String? = null,
) : UserDefinedProfileStore {
    val setActiveCalls = mutableListOf<String>()

    override fun load(): ProfilePreferenceState? {
        val fileName = activeFileName ?: profiles.firstOrNull()?.fileName ?: return null
        return profilesByFileName[fileName]
    }

    override fun listProfiles(): List<UserProfileOption> = profiles.toList()

    override fun activeProfileFileName(): String? = activeFileName

    override fun setActiveProfile(fileName: String): Boolean {
        if (profiles.none { profile -> profile.fileName == fileName }) {
            return false
        }
        setActiveCalls += fileName
        activeFileName = fileName
        return true
    }
}

private class RecordingInvariantConstraintStore(
    private val loadedConstraints: List<String> = emptyList(),
) : InvariantConstraintStore {
    var loadCalls: Int = 0
        private set
    val saveConstraints = mutableListOf<List<String>>()

    override fun load(): List<String> {
        loadCalls += 1
        return loadedConstraints.toList()
    }

    override fun save(constraints: List<String>) {
        saveConstraints += constraints.toList()
    }
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
            activeCompactionModeId = loadedState.activeCompactionModeId,
            branchingState = copyBranchingState(loadedState.branchingState),
            workflowModeEnabled = loadedState.workflowModeEnabled,
            workflowRuntimeState = copyWorkflowRuntimeState(loadedState.workflowRuntimeState),
        )
    }

    override fun save(state: SessionMemoryState) {
        saveStates += SessionMemoryState(
            messages = state.messages.toList(),
            compactedSummary = state.compactedSummary?.copy(),
            usage = state.usage?.copy(),
            activeCompactionModeId = state.activeCompactionModeId,
            branchingState = copyBranchingState(state.branchingState),
            workflowModeEnabled = state.workflowModeEnabled,
            workflowRuntimeState = copyWorkflowRuntimeState(state.workflowRuntimeState),
        )
    }

    override fun clear() {
        clearCalls += 1
    }
}

private fun copyBranchingState(state: BranchingMemoryState?): BranchingMemoryState? {
    if (state == null) {
        return null
    }

    return BranchingMemoryState(
        activeTopicKey = state.activeTopicKey,
        activeSubtopicKey = state.activeSubtopicKey,
        topics = state.topics.map { topic ->
            TopicBranchState(
                key = topic.key,
                displayName = topic.displayName,
                rollingSummary = topic.rollingSummary,
                subtopics = topic.subtopics.map { subtopic ->
                    SubtopicBranchState(
                        key = subtopic.key,
                        displayName = subtopic.displayName,
                        messages = subtopic.messages.map { message -> message.copy() },
                    )
                },
            )
        },
    )
}

private fun copyWorkflowRuntimeState(state: WorkflowRuntimeState?): WorkflowRuntimeState? {
    return state?.copy(
        planningFeedback = state.planningFeedback.toList(),
        executionFeedback = state.executionFeedback.toList(),
    )
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
    override val id: String = "rolling-summary-v1",
) : SessionCompactionStrategy {
    private val queuedSummaries = ArrayDeque(summariesToReturn)
    val previousSummaries = mutableListOf<String?>()
    val compactedMessageBatches = mutableListOf<List<ConversationMessage>>()

    override val summaryMode: SessionCompactionSummaryMode = SessionCompactionSummaryMode.GENERATE

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
