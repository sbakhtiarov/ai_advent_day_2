package com.aichallenge.day2.agent.presentation.cli

import com.aichallenge.day2.agent.core.config.ProfileEnvironmentFactsProvider
import com.aichallenge.day2.agent.core.config.ModelProperties
import com.aichallenge.day2.agent.data.tools.BuiltInPrivateToolProvider
import com.aichallenge.day2.agent.data.tools.BuiltInToolRegistry
import com.aichallenge.day2.agent.data.tools.McpPrivateToolProvider
import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.BranchingSessionMemory
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.LlmToolCapabilities
import com.aichallenge.day2.agent.domain.model.MemoryEstimateSource
import com.aichallenge.day2.agent.domain.model.MemoryUsageSnapshot
import com.aichallenge.day2.agent.domain.model.McpServerConfig
import com.aichallenge.day2.agent.domain.model.McpRuntimeStatus
import com.aichallenge.day2.agent.domain.model.McpServerRuntimeState
import com.aichallenge.day2.agent.domain.model.McpToolCallResult
import com.aichallenge.day2.agent.domain.model.McpToolCatalogStatus
import com.aichallenge.day2.agent.domain.model.McpToolDefinition
import com.aichallenge.day2.agent.domain.model.McpTransportConfig
import com.aichallenge.day2.agent.domain.model.PrivateToolBinding
import com.aichallenge.day2.agent.domain.model.PublicMcpServerCapability
import com.aichallenge.day2.agent.domain.model.ProfileMemoryState
import com.aichallenge.day2.agent.domain.model.ProfilePreferenceState
import com.aichallenge.day2.agent.domain.model.SessionCompactionMode
import com.aichallenge.day2.agent.domain.model.SessionMemory
import com.aichallenge.day2.agent.domain.model.SessionMemoryState
import com.aichallenge.day2.agent.domain.model.TokenUsage
import com.aichallenge.day2.agent.domain.model.UserProfileOption
import com.aichallenge.day2.agent.domain.model.UserWorkflowDefinition
import com.aichallenge.day2.agent.domain.model.UserWorkflowOption
import com.aichallenge.day2.agent.domain.model.WorkflowRuntimeState
import com.aichallenge.day2.agent.domain.model.WorkflowStep
import com.aichallenge.day2.agent.domain.model.WorkingMemoryState
import com.aichallenge.day2.agent.domain.repository.ProfileMemoryStore
import com.aichallenge.day2.agent.domain.repository.SessionMemoryStore
import com.aichallenge.day2.agent.domain.repository.InvariantConstraintStore
import com.aichallenge.day2.agent.domain.repository.McpServerStore
import com.aichallenge.day2.agent.domain.repository.UserDefinedProfileStore
import com.aichallenge.day2.agent.domain.repository.UserDefinedWorkflowStore
import com.aichallenge.day2.agent.domain.repository.WorkingMemoryStore
import com.aichallenge.day2.agent.domain.service.McpRuntimeService
import com.aichallenge.day2.agent.domain.service.NoOpMcpRuntimeService
import com.aichallenge.day2.agent.domain.usecase.BranchClassificationUseCase
import com.aichallenge.day2.agent.domain.usecase.BuildPromptRequest
import com.aichallenge.day2.agent.domain.usecase.BuildPromptUseCase
import com.aichallenge.day2.agent.domain.usecase.InvariantConstraintValidationUseCase
import com.aichallenge.day2.agent.domain.usecase.InvariantViolationSource
import com.aichallenge.day2.agent.domain.usecase.InvariantValidationStatus
import com.aichallenge.day2.agent.domain.usecase.InvariantViolation
import com.aichallenge.day2.agent.domain.usecase.ProfilePreferenceStateMerger
import com.aichallenge.day2.agent.domain.usecase.ProfileMemoryDistillationUseCase
import com.aichallenge.day2.agent.domain.usecase.SessionMemoryCompactionCoordinator
import com.aichallenge.day2.agent.domain.usecase.RollingSummaryCompactionStrategy
import com.aichallenge.day2.agent.domain.usecase.SendPromptUseCase
import com.aichallenge.day2.agent.domain.usecase.WorkingMemoryDistillationUseCase
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.math.roundToInt
import kotlin.time.TimeSource

class ConsoleChatController(
    private val sendPromptUseCase: SendPromptUseCase,
    private val invariantConstraintValidationUseCase: InvariantConstraintValidationUseCase = InvariantConstraintValidationUseCase(sendPromptUseCase),
    initialSystemPrompt: String,
    initialModel: String,
    models: List<ModelProperties>,
    private val io: CliIO = StdCliIO,
    private val sessionMemoryStore: SessionMemoryStore? = null,
    private val workingMemoryStore: WorkingMemoryStore? = null,
    private val profileMemoryStore: ProfileMemoryStore? = null,
    private val userDefinedProfileStore: UserDefinedProfileStore? = null,
    private val userDefinedWorkflowStore: UserDefinedWorkflowStore? = null,
    private val invariantConstraintStore: InvariantConstraintStore? = null,
    private val mcpServerStore: McpServerStore? = null,
    private val mcpRuntimeService: McpRuntimeService = NoOpMcpRuntimeService,
    private val mcpPrivateToolProvider: McpPrivateToolProvider = McpPrivateToolProvider(),
    private val builtInPrivateToolProvider: BuiltInPrivateToolProvider = BuiltInPrivateToolProvider(BuiltInToolRegistry.createDefault()),
    private val persistentMemoryEnabled: Boolean = true,
    private val workingMemoryDistillationUseCase: WorkingMemoryDistillationUseCase? = null,
    private val workingMemoryEnabled: Boolean = true,
    private val profileMemoryDistillationUseCase: ProfileMemoryDistillationUseCase? = null,
    private val profileMemoryEnabled: Boolean = true,
    private val profileEnvironmentFactsProvider: ProfileEnvironmentFactsProvider = ProfileEnvironmentFactsProvider(),
    private val fileReferenceReader: FileReferenceReader = PosixFileReferenceReader,
    private val buildPromptUseCase: BuildPromptUseCase = BuildPromptUseCase(),
    private val systemPromptBuilder: SystemPromptBuilder = SystemPromptBuilder(),
    private val compactionCoordinators: Map<SessionCompactionMode, SessionMemoryCompactionCoordinator> = mapOf(
        SessionCompactionMode.ROLLING_SUMMARY to SessionMemoryCompactionCoordinator.disabled(),
    ),
    private val defaultCompactionMode: SessionCompactionMode = SessionCompactionMode.ROLLING_SUMMARY,
) {
    private var baseSystemPrompt = initialSystemPrompt
    private var userDefinedProfilePreferences: ProfilePreferenceState? = null
    private var systemPrompt = systemPromptBuilder.build(
        basePrompt = baseSystemPrompt,
        userDefinedProfile = userDefinedProfilePreferences,
    )
    private val availableModelIds = models.map { it.id }.distinct().ifEmpty { listOf(initialModel) }
    private val modelById = models.associateBy { it.id }
    private var currentModel = initialModel
    private val sessionMemory = SessionMemory()
    private val branchingSessionMemory = BranchingSessionMemory()
    private val branchClassificationUseCase = BranchClassificationUseCase(sendPromptUseCase)
    private val branchingTopicSummaryStrategy = RollingSummaryCompactionStrategy(sendPromptUseCase)
    private var workingMemoryState: WorkingMemoryState? = null
    private var profileMemoryState: ProfileMemoryState? = null
    private var memoryUsageSnapshot = estimateHeuristicUsage(
        buildPromptUseCase.buildContext(
            systemPrompt = systemPrompt,
            session = sessionMemory.promptDataSnapshot(),
            workingTaskState = workingMemoryState?.taskState,
            profileMemoryState = effectiveProfileMemoryState(),
        ).toConversation(),
    )
    private val dialogBlocks = mutableListOf<String>()
    private val pendingFileReferences = mutableListOf<String>()
    private val inputDivider = "─".repeat(80)
    private var persistentMemoryInitialized = false
    private var workingMemoryInitialized = false
    private var profileMemoryInitialized = false
    private var userDefinedProfileInitialized = false
    private var userDefinedWorkflowInitialized = false
    private var invariantConstraintsInitialized = false
    private var mcpServersLoaded = false
    private var mcpStartupInitialized = false
    private var invariantConstraints = mutableListOf<String>()
    private var mcpServers = mutableListOf<McpServerConfig>()
    private val pendingMcpStartupMessages = mutableListOf<String>()
    private var mcpMenuSelection = 0
    private var workflowModeEnabled = false
    private var activeWorkflow: UserWorkflowDefinition? = null
    private var workflowRuntimeState: WorkflowRuntimeState? = null
    private var workflowUiStep: WorkflowUiStep? = null
    private val availableCompactionModes = SessionCompactionMode.entries.filter { mode ->
        compactionCoordinators.containsKey(mode)
    }
    private var activeCompactionMode = defaultCompactionMode

    init {
        require(currentModel in availableModelIds) {
            "Initial model must be present in available models."
        }
        require(availableCompactionModes.isNotEmpty()) {
            "At least one compaction mode must be available."
        }
        require(defaultCompactionMode in availableCompactionModes) {
            "Default compaction mode must have a coordinator."
        }
    }

    suspend fun runInteractive() {
        initializeMcpServers()
        dialogBlocks += consumePendingMcpStartupMessages()
        initializeUserDefinedProfile()
        initializeUserDefinedWorkflow()
        initializeInvariantConstraints()
        initializePersistentMemory()
        initializeWorkingMemory()
        initializeProfileMemory()

        try {
            while (true) {
                renderScreen()

                val input = io.readLineInFooter(
                    prompt = "> ",
                    divider = inputDivider,
                    footerLabel = workflowFooterLabel(),
                ) ?: break
                if (input.isBlank()) {
                    continue
                }

                io.hideCursor()

                val commandInput = input.trim()
                if (isFileReferenceCommand(commandInput)) {
                    handleFileReferenceCommand(commandInput)
                    continue
                }

                if (commandInput.startsWith("/")) {
                    val shouldContinue = handleCommand(commandInput)
                    if (!shouldContinue) {
                        break
                    }
                    continue
                }

                sendAndStore(input)
            }
        } finally {
            io.showCursor()
        }

        io.writeLine("bye")
    }

    suspend fun runSinglePrompt(prompt: String): Int {
        if (prompt.isBlank()) {
            io.writeLine("error> --prompt must not be empty")
            return 1
        }
        initializeUserDefinedProfile()
        initializeUserDefinedWorkflow()
        initializeInvariantConstraints()

        return runCatching {
            val toolContext = prepareMainTurnToolContext()
            toolContext.systemMessages.forEach(io::writeLine)
            val startedAt = TimeSource.Monotonic.markNow()
            val response = executeAssistantTurnWithInvariantValidation(
                requestPrompt = prompt,
                effectiveSystemPrompt = systemPrompt,
                validateInvariantConstraints = true,
                toolCapabilities = toolContext.capabilities,
            )
            val elapsedSeconds = startedAt.elapsedNow().inWholeMilliseconds / 1000.0
            io.writeLine(formatAssistantResponse(response.content, response.usage, elapsedSeconds))
            0
        }.getOrElse { throwable ->
            io.writeLine("error> ${throwable.message ?: "Unexpected error"}")
            1
        }
    }

    private suspend fun sendAndStore(prompt: String) {
        val preparedPrompt = buildPromptForRequest(prompt) ?: return
        preparedPrompt.inlineReferences.forEach { path ->
            dialogBlocks += "ref> $path"
        }
        dialogBlocks += formatUserPrompt(preparedPrompt.displayPrompt)

        val workflow = activeWorkflow
        if (workflowModeEnabled && workflow != null) {
            handleWorkflowInput(
                workflow = workflow,
                preparedPrompt = preparedPrompt,
            )
            return
        }

        if (workflowModeEnabled && workflow == null) {
            workflowModeEnabled = false
            workflowRuntimeState = null
            workflowUiStep = null
            persistMemorySnapshot()
            dialogBlocks += "system> workflow mode disabled: no active workflow found"
        }

        executeModelTurn(
            requestPrompt = preparedPrompt.requestPrompt,
            userPromptForWorkingMemory = preparedPrompt.displayPrompt,
            userPromptForProfileMemory = preparedPrompt.displayPrompt,
            effectiveSystemPrompt = systemPrompt,
        )
    }

    private suspend fun executeModelTurn(
        requestPrompt: String,
        userPromptForWorkingMemory: String,
        userPromptForProfileMemory: String?,
        effectiveSystemPrompt: String,
        renderAssistantResponse: Boolean = true,
        validateInvariantConstraints: Boolean = true,
    ): TurnExecutionResult? {
        io.updateFooterStatusLabel(workflowFooterLabel())
        io.showThinkingIndicator()
        val startedAt = TimeSource.Monotonic.markNow()
        io.updateThinkingIndicator(
            progressText = formatThinkingProgress(
                spinnerFrame = THINKING_SPINNER_FRAMES.first(),
                elapsedMillis = 0L,
            ),
        )

        var result: TurnExecutionResult? = null
        try {
            val toolContext = prepareMainTurnToolContext()
            toolContext.systemMessages.forEach { systemMessage ->
                dialogBlocks += systemMessage
            }
            coroutineScope {
                val progressJob = launch {
                    var frameIndex = 1
                    while (isActive) {
                        delay(THINKING_INDICATOR_UPDATE_INTERVAL_MS)
                        val spinnerFrame = THINKING_SPINNER_FRAMES[frameIndex % THINKING_SPINNER_FRAMES.size]
                        frameIndex += 1
                        val elapsedMillis = startedAt.elapsedNow().inWholeMilliseconds
                        io.updateThinkingIndicator(
                            progressText = formatThinkingProgress(
                                spinnerFrame = spinnerFrame,
                                elapsedMillis = elapsedMillis,
                            ),
                        )
                    }
                }

                try {
                    result = runCatching {
                        val response = executeAssistantTurnWithInvariantValidation(
                            requestPrompt = requestPrompt,
                            effectiveSystemPrompt = effectiveSystemPrompt,
                            validateInvariantConstraints = validateInvariantConstraints,
                            toolCapabilities = toolContext.capabilities,
                        )
                        val sideEffects = applyAcceptedTurnSideEffects(
                            requestPrompt = requestPrompt,
                            response = response,
                        )
                        updateWorkingMemoryAfterSuccessfulTurn(
                            userPrompt = userPromptForWorkingMemory,
                            assistantResponse = response.content,
                        )
                        userPromptForProfileMemory
                            ?.trim()
                            ?.takeIf { value -> value.isNotEmpty() }
                            ?.let { normalizedPrompt ->
                                updateProfileMemoryAfterSuccessfulTurn(
                                    userPrompt = normalizedPrompt,
                                )
                            }
                        memoryUsageSnapshot = buildUsageSnapshotAfterSuccessfulTurn(
                            responseContent = response.content,
                            usage = response.usage,
                            messages = activeContextMessages(
                                systemPromptOverride = effectiveSystemPrompt,
                            ),
                        )
                        val elapsedSeconds = startedAt.elapsedNow().inWholeMilliseconds / 1000.0
                        val finalizedTurnResult = TurnExecutionResult(
                            response = response,
                            compacted = sideEffects.compacted,
                            systemMessages = sideEffects.systemMessages,
                            elapsedSeconds = elapsedSeconds,
                        )
                        persistMemorySnapshot()
                        pendingFileReferences.clear()
                        if (renderAssistantResponse) {
                            dialogBlocks += formatAssistantResponse(response.content, response.usage, elapsedSeconds)
                        }
                        if (sideEffects.compacted) {
                            dialogBlocks += "system> session memory compacted"
                        }
                        sideEffects.systemMessages.forEach { systemMessage ->
                            dialogBlocks += systemMessage
                        }
                        finalizedTurnResult
                    }.onFailure { throwable ->
                        dialogBlocks += "error> ${throwable.message ?: "Unexpected error"}"
                    }.getOrNull()
                } finally {
                    progressJob.cancelAndJoin()
                }
            }
        } finally {
            io.hideThinkingIndicator()
        }

        return result
    }

    private suspend fun handleWorkflowInput(
        workflow: UserWorkflowDefinition,
        preparedPrompt: PreparedPrompt,
    ) {
        val normalizedState = normalizedWorkflowRuntimeState(workflowRuntimeState)
        val nextState = when (normalizedState.step) {
            WorkflowStep.USER_INPUT -> {
                WorkflowRuntimeState(
                    step = WorkflowStep.PLANNING_APPROVAL,
                    originalUserPrompt = preparedPrompt.requestPrompt,
                )
            }

            WorkflowStep.PLANNING_APPROVAL -> {
                normalizedState.copy(
                    planningFeedback = normalizedState.planningFeedback + preparedPrompt.requestPrompt,
                )
            }

            WorkflowStep.EXECUTION_APPROVAL -> {
                normalizedState.copy(
                    executionFeedback = normalizedState.executionFeedback + preparedPrompt.requestPrompt,
                )
            }
        }

        workflowRuntimeState = nextState
        workflowUiStep = when (nextState.step) {
            WorkflowStep.USER_INPUT -> WorkflowUiStep.USER_INPUT
            WorkflowStep.PLANNING_APPROVAL -> WorkflowUiStep.PLANNING
            WorkflowStep.EXECUTION_APPROVAL -> WorkflowUiStep.EXECUTION
        }
        persistMemorySnapshot()

        when (nextState.step) {
            WorkflowStep.PLANNING_APPROVAL -> {
                runPlanningFlow(
                    workflow = workflow,
                    initialState = nextState,
                    initialProfileInput = preparedPrompt.displayPrompt,
                )
            }

            WorkflowStep.EXECUTION_APPROVAL -> {
                runExecutionFlow(
                    workflow = workflow,
                    initialState = nextState,
                    initialProfileInput = preparedPrompt.displayPrompt,
                )
            }

            WorkflowStep.USER_INPUT -> Unit
        }
    }

    private suspend fun runPlanningFlow(
        workflow: UserWorkflowDefinition,
        initialState: WorkflowRuntimeState,
        initialProfileInput: String?,
    ) {
        workflowUiStep = WorkflowUiStep.PLANNING
        var state = initialState
        val pendingProfileInputs = mutableListOf<String>().apply {
            initialProfileInput?.trim()?.takeIf { value -> value.isNotEmpty() }?.let(::add)
        }

        while (true) {
            resetWorkflowStepSessionContext()
            val planningPrompt = buildPlanningWorkflowPrompt(state)
            val turnResult = executeModelTurn(
                requestPrompt = planningPrompt,
                userPromptForWorkingMemory = planningPrompt,
                userPromptForProfileMemory = null,
                effectiveSystemPrompt = composeWorkflowSystemPrompt(
                    workflow = workflow,
                    stepPrompt = workflow.planning,
                    responseContract = WORKFLOW_STEP_RESPONSE_CONTRACT_PROMPT,
                ),
                renderAssistantResponse = false,
            ) ?: return

            pendingProfileInputs.forEach { profileInput ->
                updateProfileMemoryAfterSuccessfulTurn(profileInput)
            }
            pendingProfileInputs.clear()

            val structuredResponse = parseWorkflowStepResponse(turnResult.response.content)
            renderWorkflowStepResponse(
                stepLabel = "Planning",
                response = structuredResponse,
                usage = turnResult.response.usage,
                elapsedSeconds = turnResult.elapsedSeconds,
            )
            if (structuredResponse.needsUserInput) {
                if (structuredResponse.questions.isEmpty()) {
                    dialogBlocks += "system> planning requested user input but no questions were provided"
                } else {
                    val questionAnswers = collectWorkflowQuestionAnswers(
                        questions = structuredResponse.questions,
                    ) ?: return
                    val feedbackEntries = questionAnswers.map { questionAnswer ->
                        "Question: ${questionAnswer.question}\nAnswer: ${questionAnswer.answer}"
                    }
                    state = state.copy(
                        planningFeedback = state.planningFeedback + feedbackEntries,
                    )
                    workflowRuntimeState = state
                    persistMemorySnapshot()
                    pendingProfileInputs += questionAnswers.map { questionAnswer -> questionAnswer.answer }
                    continue
                }
            }

            val planningOutput = structuredResponse.answer
                .trim()
                .takeIf { value -> value.isNotEmpty() }
                ?: turnResult.response.content.trim()

            state = state.copy(
                step = WorkflowStep.PLANNING_APPROVAL,
                latestPlanningOutput = planningOutput,
                approvedPlan = null,
                latestExecutionOutput = null,
                executionFeedback = emptyList(),
            )
            workflowRuntimeState = state
            persistMemorySnapshot()

            when (val decision = readWorkflowApprovalDecision(WORKFLOW_PLANNING_APPROVAL_PROMPT)) {
                WorkflowApprovalDecision.APPROVE -> {
                    val approvedPlan = state.latestPlanningOutput?.trim().orEmpty()
                    if (approvedPlan.isBlank()) {
                        dialogBlocks += "system> planning output is empty; add feedback to rerun planning"
                        return
                    }
                    state = state.copy(
                        step = WorkflowStep.EXECUTION_APPROVAL,
                        approvedPlan = approvedPlan,
                        executionFeedback = emptyList(),
                        latestExecutionOutput = null,
                    )
                    workflowRuntimeState = state
                    workflowUiStep = WorkflowUiStep.EXECUTION
                    persistMemorySnapshot()
                    runExecutionFlow(
                        workflow = workflow,
                        initialState = state,
                        initialProfileInput = null,
                    )
                    return
                }

                WorkflowApprovalDecision.CANCEL -> {
                    workflowUiStep = WorkflowUiStep.PLANNING
                    persistMemorySnapshot()
                    dialogBlocks += "system> planning paused; enter new input to rerun planning"
                    return
                }

                is WorkflowApprovalDecision.COMMENT -> {
                    dialogBlocks += formatUserPrompt(decision.text)
                    state = state.copy(
                        planningFeedback = state.planningFeedback + decision.text,
                    )
                    workflowRuntimeState = state
                    workflowUiStep = WorkflowUiStep.PLANNING
                    persistMemorySnapshot()
                    pendingProfileInputs += decision.text
                }
            }
        }
    }

    private suspend fun runExecutionFlow(
        workflow: UserWorkflowDefinition,
        initialState: WorkflowRuntimeState,
        initialProfileInput: String?,
    ) {
        workflowUiStep = WorkflowUiStep.EXECUTION
        var state = initialState
        val pendingProfileInputs = mutableListOf<String>().apply {
            initialProfileInput?.trim()?.takeIf { value -> value.isNotEmpty() }?.let(::add)
        }

        while (true) {
            resetWorkflowStepSessionContext()
            val executionPrompt = buildExecutionWorkflowPrompt(state)
            val executionResult = executeModelTurn(
                requestPrompt = executionPrompt,
                userPromptForWorkingMemory = executionPrompt,
                userPromptForProfileMemory = null,
                effectiveSystemPrompt = composeWorkflowSystemPrompt(
                    workflow = workflow,
                    stepPrompt = workflow.execution,
                    responseContract = WORKFLOW_STEP_RESPONSE_CONTRACT_PROMPT,
                ),
                renderAssistantResponse = false,
            ) ?: return

            pendingProfileInputs.forEach { profileInput ->
                updateProfileMemoryAfterSuccessfulTurn(profileInput)
            }
            pendingProfileInputs.clear()

            val structuredResponse = parseWorkflowStepResponse(executionResult.response.content)
            renderWorkflowStepResponse(
                stepLabel = "Execution",
                response = structuredResponse,
                usage = executionResult.response.usage,
                elapsedSeconds = executionResult.elapsedSeconds,
            )
            if (structuredResponse.needsUserInput) {
                if (structuredResponse.questions.isEmpty()) {
                    dialogBlocks += "system> execution requested user input but no questions were provided"
                } else {
                    val questionAnswers = collectWorkflowQuestionAnswers(
                        questions = structuredResponse.questions,
                    ) ?: return
                    val feedbackEntries = questionAnswers.map { questionAnswer ->
                        "Question: ${questionAnswer.question}\nAnswer: ${questionAnswer.answer}"
                    }
                    state = state.copy(
                        executionFeedback = state.executionFeedback + feedbackEntries,
                    )
                    workflowRuntimeState = state
                    persistMemorySnapshot()
                    pendingProfileInputs += questionAnswers.map { questionAnswer -> questionAnswer.answer }
                    continue
                }
            }

            val executionOutput = structuredResponse.answer
                .trim()
                .takeIf { value -> value.isNotEmpty() }
                ?: executionResult.response.content.trim()

            state = state.copy(
                step = WorkflowStep.EXECUTION_APPROVAL,
                latestExecutionOutput = executionOutput,
            )
            workflowRuntimeState = state
            persistMemorySnapshot()

            when (val decision = readWorkflowApprovalDecision(WORKFLOW_EXECUTION_APPROVAL_PROMPT)) {
                WorkflowApprovalDecision.APPROVE -> {
                    workflowUiStep = WorkflowUiStep.VALIDATION
                    val validationOutcome = runValidationStep(
                        workflow = workflow,
                        state = state,
                    ) ?: return
                    if (validationOutcome.status == WorkflowValidationStatus.PASS) {
                        completeWorkflowAfterValidation(
                            outcome = validationOutcome,
                            executionResult = state.latestExecutionOutput.orEmpty(),
                        )
                        return
                    }

                    state = state.copy(
                        executionFeedback = state.executionFeedback + validationOutcome.feedback,
                    )
                    workflowRuntimeState = state
                    workflowUiStep = WorkflowUiStep.EXECUTION
                    persistMemorySnapshot()
                }

                WorkflowApprovalDecision.CANCEL -> {
                    workflowUiStep = WorkflowUiStep.EXECUTION
                    persistMemorySnapshot()
                    dialogBlocks += "system> execution paused; enter new input to rerun execution"
                    return
                }

                is WorkflowApprovalDecision.COMMENT -> {
                    dialogBlocks += formatUserPrompt(decision.text)
                    state = state.copy(
                        step = WorkflowStep.PLANNING_APPROVAL,
                        planningFeedback = state.planningFeedback + decision.text,
                    )
                    workflowRuntimeState = state
                    workflowUiStep = WorkflowUiStep.PLANNING
                    persistMemorySnapshot()
                    runPlanningFlow(
                        workflow = workflow,
                        initialState = state,
                        initialProfileInput = decision.text,
                    )
                    return
                }
            }
        }
    }

    private suspend fun runValidationStep(
        workflow: UserWorkflowDefinition,
        state: WorkflowRuntimeState,
    ): WorkflowValidationOutcome? {
        workflowUiStep = WorkflowUiStep.VALIDATION
        val executionOutput = state.latestExecutionOutput?.trim().orEmpty()
        if (executionOutput.isEmpty()) {
            return WorkflowValidationOutcome(
                status = WorkflowValidationStatus.FAIL,
                summary = "Validation failed because execution output is empty.",
                details = null,
                feedback = "Validation failed because execution output is empty.",
            )
        }

        resetWorkflowStepSessionContext()
        val validationPrompt = buildValidationWorkflowPrompt(state)
        val validationResult = executeModelTurn(
            requestPrompt = validationPrompt,
            userPromptForWorkingMemory = validationPrompt,
            userPromptForProfileMemory = null,
            effectiveSystemPrompt = composeWorkflowSystemPrompt(
                workflow = workflow,
                stepPrompt = workflow.validation,
                responseContract = WORKFLOW_VALIDATION_RESPONSE_CONTRACT_PROMPT,
            ),
            renderAssistantResponse = false,
            validateInvariantConstraints = false,
        ) ?: return null

        val parsedOutcome = parseWorkflowValidationOutcome(validationResult.response.content)
        renderWorkflowValidationResponse(
            outcome = parsedOutcome,
            usage = validationResult.response.usage,
            elapsedSeconds = validationResult.elapsedSeconds,
        )
        return parsedOutcome
    }

    private fun completeWorkflowAfterValidation(
        outcome: WorkflowValidationOutcome,
        executionResult: String,
    ) {
        val summary = outcome.summary.trim().ifEmpty { "Validation passed." }
        val details = outcome.details?.trim()?.takeIf { value -> value.isNotEmpty() }
        val completionBlock = buildString {
            appendLine("workflow> completed")
            appendLine("summary> $summary")
            if (details != null) {
                appendLine("details> $details")
            }
            appendLine("execution result:")
            append(executionResult)
        }.trimEnd()
        dialogBlocks += completionBlock
        workflowModeEnabled = false
        workflowRuntimeState = null
        workflowUiStep = null
        persistMemorySnapshot()
    }

    private fun composeWorkflowSystemPrompt(
        workflow: UserWorkflowDefinition,
        stepPrompt: String,
        responseContract: String? = null,
    ): String {
        return listOfNotNull(
            systemPrompt.trim().takeIf { value -> value.isNotEmpty() },
            workflow.basePrompt?.trim()?.takeIf { value -> value.isNotEmpty() },
            stepPrompt.trim().takeIf { value -> value.isNotEmpty() },
            responseContract?.trim()?.takeIf { value -> value.isNotEmpty() },
        ).joinToString(separator = "\n\n")
    }

    private fun augmentSystemPromptWithInvariants(baseSystemPrompt: String): String {
        val invariantBlock = buildInvariantSystemPromptBlock() ?: return baseSystemPrompt
        return listOf(
            baseSystemPrompt.trim().takeIf { value -> value.isNotEmpty() },
            invariantBlock,
        ).joinToString(separator = "\n\n")
    }

    private fun buildInvariantSystemPromptBlock(): String? {
        val strictInvariants = normalizeStrictInvariantConstraints(invariantConstraints)
        if (strictInvariants.isEmpty()) {
            return null
        }

        return buildString {
            appendLine("Invariant constraints (strict requirements):")
            strictInvariants.forEachIndexed { index, invariant ->
                appendLine("${index + 1}. $invariant")
            }
            append("All listed invariants are mandatory and must be satisfied in every response.")
        }.trimEnd()
    }

    private fun normalizeStrictInvariantConstraints(values: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return values.mapNotNull { value ->
            val trimmed = value.trim()
            if (trimmed.isEmpty()) {
                null
            } else {
                val withoutPrefix = trimmed.replaceFirst(INVARIANT_STRICT_PREFIX_REGEX, "").trim()
                if (withoutPrefix.isEmpty()) {
                    null
                } else {
                    "[Strict] $withoutPrefix"
                }
            }
        }.filter { normalized ->
            seen.add(normalized.lowercase())
        }
    }

    private fun buildPlanningWorkflowPrompt(state: WorkflowRuntimeState): String {
        return buildString {
            appendLine("User request:")
            appendLine(state.originalUserPrompt)
            if (state.planningFeedback.isNotEmpty()) {
                appendLine()
                appendLine("User feedback to incorporate into planning:")
                state.planningFeedback.forEachIndexed { index, feedback ->
                    appendLine("${index + 1}. $feedback")
                }
            }
        }.trimEnd()
    }

    private fun buildExecutionWorkflowPrompt(state: WorkflowRuntimeState): String {
        val approvedPlan = state.approvedPlan?.trim().takeIf { value -> !value.isNullOrEmpty() }
            ?: state.latestPlanningOutput?.trim().takeIf { value -> !value.isNullOrEmpty() }
            ?: ""
        return buildString {
            appendLine("Approved execution plan:")
            appendLine(approvedPlan)
            if (state.executionFeedback.isNotEmpty()) {
                appendLine()
                appendLine("User or validator feedback to incorporate into execution:")
                state.executionFeedback.forEachIndexed { index, feedback ->
                    appendLine("${index + 1}. $feedback")
                }
            }
        }.trimEnd()
    }

    private fun buildValidationWorkflowPrompt(state: WorkflowRuntimeState): String {
        return buildString {
            appendLine("Execution result to validate:")
            append(state.latestExecutionOutput.orEmpty())
        }.trimEnd()
    }

    private fun collectWorkflowQuestionAnswers(
        questions: List<WorkflowQuestion>,
    ): List<WorkflowQuestionAnswer>? {
        val normalizedQuestions = questions.mapNotNull { value ->
            normalizeWorkflowQuestion(value)
        }
        if (normalizedQuestions.isEmpty()) {
            return emptyList()
        }

        val answers = mutableListOf<WorkflowQuestionAnswer>()
        normalizedQuestions.forEachIndexed { index, question ->
            dialogBlocks += formatWorkflowQuestion(
                question = question,
                index = index,
                total = normalizedQuestions.size,
            )
            while (true) {
                renderScreen()
                io.showCursor()
                val rawAnswer = try {
                    io.readLineInFooter(
                        prompt = "answer ${index + 1}/${normalizedQuestions.size}> ",
                        divider = inputDivider,
                        footerLabel = workflowFooterLabel(),
                    )
                } finally {
                    io.hideCursor()
                }

                if (rawAnswer == null) {
                    return null
                }

                val answer = rawAnswer.trim()
                if (answer.isEmpty()) {
                    dialogBlocks += "system> answer must not be empty"
                    continue
                }

                dialogBlocks += formatUserPrompt(answer)
                answers += WorkflowQuestionAnswer(
                    question = question.text,
                    answer = answer,
                )
                break
            }
        }

        return answers
    }

    private fun formatWorkflowQuestion(
        question: WorkflowQuestion,
        index: Int,
        total: Int,
    ): String {
        return buildString {
            appendLine("workflow> question ${index + 1}/$total")
            appendLine("question> ${question.text}")
            if (question.options.isNotEmpty()) {
                appendLine("options:")
                question.options.forEach { option ->
                    appendLine("- $option")
                }
            }
        }.trimEnd()
    }

    private fun readWorkflowApprovalDecision(prompt: String): WorkflowApprovalDecision {
        dialogBlocks += prompt
        renderScreen()
        io.showCursor()
        val input = try {
            io.readLineInFooter(
                prompt = WORKFLOW_APPROVAL_INPUT_PROMPT,
                divider = inputDivider,
                footerLabel = workflowFooterLabel(),
            )
        } finally {
            io.hideCursor()
        }
        return parseWorkflowApprovalDecision(input)
    }

    private fun workflowFooterLabel(): String? {
        if (!workflowModeEnabled) {
            return null
        }
        val step = workflowUiStep ?: WorkflowUiStep.USER_INPUT
        return "Workflow: ${step.label}"
    }

    private fun parseWorkflowApprovalDecision(input: String?): WorkflowApprovalDecision {
        val normalized = input?.trim().orEmpty()
        return when (normalized) {
            "1" -> WorkflowApprovalDecision.APPROVE
            "2" -> WorkflowApprovalDecision.CANCEL
            else -> {
                if (normalized.isEmpty()) {
                    WorkflowApprovalDecision.CANCEL
                } else {
                    WorkflowApprovalDecision.COMMENT(normalized)
                }
            }
        }
    }

    private fun parseWorkflowValidationOutcome(rawContent: String): WorkflowValidationOutcome {
        val parsedObject = parseValidationJsonObject(rawContent)
        if (parsedObject == null) {
            return WorkflowValidationOutcome(
                status = WorkflowValidationStatus.FAIL,
                summary = "Validation output is not valid JSON.",
                details = rawContent.trim().takeIf { value -> value.isNotEmpty() },
                feedback = "Validation failed: output is not valid JSON.\n$rawContent",
            )
        }

        val status = parsedObject["status"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.uppercase()
        val summary = parsedObject["summary"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            .orEmpty()
        val details = parsedObject["details"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf { value -> value.isNotEmpty() }

        return when (status) {
            "PASS" -> WorkflowValidationOutcome(
                status = WorkflowValidationStatus.PASS,
                summary = summary.ifEmpty { "Validation passed." },
                details = details,
                feedback = "",
            )

            "FAIL" -> {
                val resolvedSummary = summary.ifEmpty { "Validation failed." }
                val feedback = buildString {
                    append("Validation failed: ")
                    append(resolvedSummary)
                    if (details != null) {
                        append("\nDetails: ")
                        append(details)
                    }
                }
                WorkflowValidationOutcome(
                    status = WorkflowValidationStatus.FAIL,
                    summary = resolvedSummary,
                    details = details,
                    feedback = feedback,
                )
            }

            else -> WorkflowValidationOutcome(
                status = WorkflowValidationStatus.FAIL,
                summary = "Validation output has invalid or missing status.",
                details = rawContent.trim().takeIf { value -> value.isNotEmpty() },
                feedback = "Validation failed: output has invalid or missing status.\n$rawContent",
            )
        }
    }

    private fun parseValidationJsonObject(rawContent: String): JsonObject? {
        val trimmedRaw = rawContent.trim()
        if (trimmedRaw.isEmpty()) {
            return null
        }

        val parseObject = { candidate: String ->
            runCatching {
                workflowValidationJson.parseToJsonElement(candidate).jsonObject
            }.getOrNull()
        }

        parseObject(trimmedRaw)?.let { return it }

        extractFencedCodeBlocks(trimmedRaw).forEach { fencedContent ->
            val trimmedContent = fencedContent.trim()
            parseObject(trimmedContent)?.let { return it }
            extractFirstJsonObjectCandidate(trimmedContent)?.let { jsonObjectCandidate ->
                parseObject(jsonObjectCandidate)?.let { return it }
            }
        }

        extractFirstJsonObjectCandidate(trimmedRaw)?.let { jsonObjectCandidate ->
            parseObject(jsonObjectCandidate)?.let { return it }
        }

        return null
    }

    private fun extractFencedCodeBlocks(text: String): List<String> {
        return FENCED_CODE_BLOCK_REGEX.findAll(text)
            .mapNotNull { match ->
                match.groups[1]?.value
            }
            .toList()
    }

    private fun extractFirstJsonObjectCandidate(text: String): String? {
        val startIndex = text.indexOf('{')
        if (startIndex == -1) {
            return null
        }

        var depth = 0
        var inString = false
        var escaped = false

        for (index in startIndex until text.length) {
            val current = text[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (current == '\\') {
                    escaped = true
                } else if (current == '"') {
                    inString = false
                }
                continue
            }

            when (current) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return text.substring(startIndex, index + 1)
                    }
                }
            }
        }

        return null
    }

    private fun parseWorkflowStepResponse(rawContent: String): WorkflowStepResponse {
        val trimmedRaw = rawContent.trim()
        val parsedObject = runCatching {
            workflowStepResponseJson.parseToJsonElement(rawContent).jsonObject
        }.getOrNull()
            ?: return WorkflowStepResponse(
                needsUserInput = false,
                questions = emptyList(),
                answer = trimmedRaw,
            )

        val needsUserInput = parseJsonBoolean(
            primaryValue = runCatching { parsedObject["needs_user_input"]?.jsonPrimitive?.contentOrNull }.getOrNull(),
            secondaryValue = runCatching { parsedObject["needsUserInput"]?.jsonPrimitive?.contentOrNull }.getOrNull(),
        )
        val questions = parseWorkflowQuestions(parsedObject)
        val answer = (parsedObject["answer"] ?: parsedObject["result"])
            ?.let { value ->
                runCatching { value.jsonPrimitive.contentOrNull }.getOrNull()
            }
            ?.trim()
            .orEmpty()
            .ifEmpty {
                if (!needsUserInput) {
                    trimmedRaw
                } else {
                    ""
                }
            }

        return WorkflowStepResponse(
            needsUserInput = needsUserInput,
            questions = questions,
            answer = answer,
        )
    }

    private fun parseWorkflowQuestions(parsedObject: JsonObject): List<WorkflowQuestion> {
        val questionsArray = runCatching {
            (parsedObject["questions"] ?: parsedObject["Questions"])?.jsonArray
        }.getOrNull() ?: return emptyList()

        return questionsArray.mapNotNull { element ->
            runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
                ?.trim()
                ?.takeIf { value -> value.isNotEmpty() }
                ?.let { questionText ->
                    return@mapNotNull WorkflowQuestion(
                        text = questionText,
                        options = emptyList(),
                    )
                }

            val questionObject = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val questionText = firstNonBlankJsonString(
                jsonObject = questionObject,
                keys = listOf("question", "prompt", "text", "title"),
            ) ?: return@mapNotNull null
            WorkflowQuestion(
                text = questionText,
                options = parseWorkflowQuestionOptions(questionObject),
            )
        }
    }

    private fun parseWorkflowQuestionOptions(questionObject: JsonObject): List<String> {
        val optionsArray = runCatching {
            (
                questionObject["options"]
                    ?: questionObject["choices"]
                    ?: questionObject["answer_options"]
                    ?: questionObject["answerOptions"]
                )?.jsonArray
        }.getOrNull() ?: return emptyList()

        return optionsArray.mapNotNull { optionElement ->
            runCatching { optionElement.jsonPrimitive.contentOrNull }.getOrNull()
                ?.trim()
                ?.takeIf { value -> value.isNotEmpty() }
                ?: run {
                    val optionObject = runCatching { optionElement.jsonObject }.getOrNull() ?: return@run null
                    firstNonBlankJsonString(
                        jsonObject = optionObject,
                        keys = listOf("label", "text", "value", "title", "option"),
                    )
                }
        }
    }

    private fun firstNonBlankJsonString(
        jsonObject: JsonObject,
        keys: List<String>,
    ): String? {
        keys.forEach { key ->
            val value = runCatching { jsonObject[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
                ?.trim()
                ?.takeIf { normalized -> normalized.isNotEmpty() }
            if (value != null) {
                return value
            }
        }
        return null
    }

    private fun normalizeWorkflowQuestion(question: WorkflowQuestion): WorkflowQuestion? {
        val normalizedQuestion = question.text.trim().takeIf { value -> value.isNotEmpty() } ?: return null
        val normalizedOptions = question.options.mapNotNull { option ->
            option.trim().takeIf { value -> value.isNotEmpty() }
        }
        return WorkflowQuestion(
            text = normalizedQuestion,
            options = normalizedOptions,
        )
    }

    private fun renderWorkflowStepResponse(
        stepLabel: String,
        response: WorkflowStepResponse,
        usage: TokenUsage?,
        elapsedSeconds: Double,
    ) {
        val text = buildWorkflowStepResponseText(stepLabel, response)
        if (text.isEmpty()) {
            return
        }
        dialogBlocks += formatAssistantResponse(text, usage, elapsedSeconds)
    }

    private fun buildWorkflowStepResponseText(
        stepLabel: String,
        response: WorkflowStepResponse,
    ): String {
        val answer = response.answer.trim()
        if (answer.isNotEmpty()) {
            return answer
        }

        if (!response.needsUserInput) {
            return ""
        }

        return "$stepLabel needs additional user input."
    }

    private fun renderWorkflowValidationResponse(
        outcome: WorkflowValidationOutcome,
        usage: TokenUsage?,
        elapsedSeconds: Double,
    ) {
        val text = buildString {
            appendLine("Validation status: ${outcome.status.name}")
            appendLine("Summary: ${outcome.summary}")
            outcome.details?.let { details ->
                appendLine("Details: $details")
            }
        }.trimEnd()
        dialogBlocks += formatAssistantResponse(text, usage, elapsedSeconds)
    }

    private fun parseJsonBoolean(
        primaryValue: String?,
        secondaryValue: String?,
    ): Boolean {
        val candidate = (primaryValue ?: secondaryValue)
            ?.trim()
            ?.lowercase()
            ?: return false
        return candidate == "true"
    }

    private fun normalizedWorkflowRuntimeState(state: WorkflowRuntimeState?): WorkflowRuntimeState {
        val runtimeState = state ?: WorkflowRuntimeState()
        val normalizedPrompt = runtimeState.originalUserPrompt.trim()
        if (runtimeState.step != WorkflowStep.USER_INPUT && normalizedPrompt.isEmpty()) {
            return WorkflowRuntimeState()
        }

        return runtimeState.copy(
            originalUserPrompt = normalizedPrompt,
            planningFeedback = runtimeState.planningFeedback.mapNotNull { value ->
                value.trim().takeIf { normalized -> normalized.isNotEmpty() }
            },
            executionFeedback = runtimeState.executionFeedback.mapNotNull { value ->
                value.trim().takeIf { normalized -> normalized.isNotEmpty() }
            },
            latestPlanningOutput = runtimeState.latestPlanningOutput?.trim()?.takeIf { value -> value.isNotEmpty() },
            approvedPlan = runtimeState.approvedPlan?.trim()?.takeIf { value -> value.isNotEmpty() },
            latestExecutionOutput = runtimeState.latestExecutionOutput?.trim()?.takeIf { value -> value.isNotEmpty() },
        )
    }

    private suspend fun executeLinearTurn(
        requestPrompt: String,
        effectiveSystemPrompt: String,
        toolCapabilities: LlmToolCapabilities,
    ): AgentResponse {
        val effectivePromptWithInvariants = augmentSystemPromptWithInvariants(effectiveSystemPrompt)
        val promptRequest = buildPromptUseCase.execute(
            request = BuildPromptRequest(
                systemPrompt = effectivePromptWithInvariants,
                session = sessionMemory.promptDataSnapshot(),
                userPrompt = requestPrompt,
                workingTaskState = workingMemoryState?.taskState,
                profileMemoryState = effectiveProfileMemoryState(),
                toolCapabilities = toolCapabilities,
            ),
        )
        val response = sendPromptUseCase.execute(
            prompt = promptRequest,
            model = currentModel,
        )
        return response
    }

    private suspend fun executeBranchingTurn(
        requestPrompt: String,
        effectiveSystemPrompt: String,
        toolCapabilities: LlmToolCapabilities,
    ): AgentResponse {
        val effectivePromptWithInvariants = augmentSystemPromptWithInvariants(effectiveSystemPrompt)
        val contextWindow = modelById[currentModel]?.contextWindowTokens
        val branchingPromptData = branchingSessionMemory.promptDataForRequest(
            maxEstimatedTokens = contextWindow,
            estimateTokens = { sessionPromptData ->
                val promptRequest = buildPromptUseCase.execute(
                    request = BuildPromptRequest(
                        systemPrompt = effectivePromptWithInvariants,
                        session = sessionPromptData,
                        userPrompt = requestPrompt,
                        workingTaskState = workingMemoryState?.taskState,
                        profileMemoryState = effectiveProfileMemoryState(),
                        toolCapabilities = toolCapabilities,
                    ),
                )
                estimateSessionTokensHeuristically(promptRequest.toConversation())
            },
        )
        val promptRequest = buildPromptUseCase.execute(
            request = BuildPromptRequest(
                systemPrompt = effectivePromptWithInvariants,
                session = branchingPromptData.session,
                userPrompt = requestPrompt,
                workingTaskState = workingMemoryState?.taskState,
                profileMemoryState = effectiveProfileMemoryState(),
                toolCapabilities = toolCapabilities,
            ),
        )
        val response = sendPromptUseCase.execute(
            prompt = promptRequest,
            model = currentModel,
        )
        return response
    }

    private suspend fun executeAssistantTurnWithInvariantValidation(
        requestPrompt: String,
        effectiveSystemPrompt: String,
        validateInvariantConstraints: Boolean,
        toolCapabilities: LlmToolCapabilities = LlmToolCapabilities(),
    ): AgentResponse {
        val maxAttempts = if (validateInvariantConstraints) {
            INVARIANT_VALIDATION_MAX_RETRIES + 1
        } else {
            1
        }
        var currentPrompt = requestPrompt
        val allFailures = mutableListOf<InvariantViolation>()

        repeat(maxAttempts) { attemptIndex ->
            val response = if (activeCompactionMode == SessionCompactionMode.BRANCHING) {
                executeBranchingTurn(
                    requestPrompt = currentPrompt,
                    effectiveSystemPrompt = effectiveSystemPrompt,
                    toolCapabilities = toolCapabilities,
                )
            } else {
                executeLinearTurn(
                    requestPrompt = currentPrompt,
                    effectiveSystemPrompt = effectiveSystemPrompt,
                    toolCapabilities = toolCapabilities,
                )
            }

            if (!validateInvariantConstraints) {
                return response
            }

            val validationResult = invariantConstraintValidationUseCase.validate(
                invariants = invariantConstraints,
                userPrompt = requestPrompt,
                assistantResponse = response.content,
                model = currentModel,
            )

            if (validationResult.status == InvariantValidationStatus.PASS) {
                return response
            }

            val failedConstraints = validationResult.failedConstraints
            allFailures += failedConstraints
            val hasUserPromptViolation = failedConstraints.any { failure ->
                failure.source == InvariantViolationSource.USER
            }
            if (hasUserPromptViolation) {
                throw IllegalStateException(
                    buildInvariantValidationFailureMessage(
                        failedConstraints = allFailures,
                        attemptsUsed = attemptIndex + 1,
                        stoppedOnUserViolation = true,
                    ),
                )
            }
            if (attemptIndex == maxAttempts - 1) {
                return@repeat
            }

            currentPrompt = buildInvariantRegenerationPrompt(
                originalRequestPrompt = requestPrompt,
                previousResponse = response.content,
                failedConstraints = failedConstraints,
            )
        }

        throw IllegalStateException(
            buildInvariantValidationFailureMessage(
                failedConstraints = allFailures,
                attemptsUsed = maxAttempts,
                stoppedOnUserViolation = false,
            ),
        )
    }

    private suspend fun applyAcceptedTurnSideEffects(
        requestPrompt: String,
        response: AgentResponse,
    ): TurnSideEffects {
        return if (activeCompactionMode == SessionCompactionMode.BRANCHING) {
            val systemMessages = handleBranchingPostResponse(
                prompt = requestPrompt,
                response = response.content,
            )
            TurnSideEffects(
                compacted = false,
                systemMessages = systemMessages,
            )
        } else {
            sessionMemory.recordSuccessfulTurn(requestPrompt, response.content)
            val compacted = activeCompactionCoordinator()
                ?.compactIfNeeded(
                    sessionMemory = sessionMemory,
                    model = currentModel,
                )
                ?: false
            TurnSideEffects(
                compacted = compacted,
                systemMessages = emptyList(),
            )
        }
    }

    private fun buildInvariantRegenerationPrompt(
        originalRequestPrompt: String,
        previousResponse: String,
        failedConstraints: List<InvariantViolation>,
    ): String {
        val effectiveFailures = failedConstraints.takeIf { values -> values.isNotEmpty() }
            ?: listOf(
                InvariantViolation(
                    constraint = "[Strict] Invariant constraint",
                    userMessage = "Response violated strict invariants. Regenerate while satisfying all strict constraints.",
                    source = InvariantViolationSource.LLM,
                ),
            )
        return buildString {
            appendLine("Original user prompt:")
            appendLine(originalRequestPrompt)
            appendLine()
            appendLine("Your previous response was rejected by strict invariant validation.")
            appendLine("Fix all failed constraints and regenerate a complete corrected response.")
            appendLine()
            appendLine("Failed constraints:")
            effectiveFailures.forEachIndexed { index, failure ->
                appendLine("${index + 1}. ${failure.constraint}")
                appendLine("   User message: ${failure.userMessage}")
            }
            appendLine()
            appendLine("Rejected previous response:")
            appendLine(previousResponse)
            appendLine()
            appendLine("Return only the corrected final response.")
        }.trimEnd()
    }

    private fun buildInvariantValidationFailureMessage(
        failedConstraints: List<InvariantViolation>,
        attemptsUsed: Int,
        stoppedOnUserViolation: Boolean,
    ): String {
        val effectiveFailures = failedConstraints
            .map { value ->
                value.copy(
                    constraint = value.constraint.trim(),
                    userMessage = value.userMessage.trim(),
                )
            }
            .filter { value -> value.constraint.isNotEmpty() || value.userMessage.isNotEmpty() }
            .distinctBy { value -> "${value.constraint.lowercase()}\n${value.userMessage.lowercase()}" }
            .ifEmpty {
                listOf(
                    InvariantViolation(
                        constraint = "[Strict] Invariant constraint",
                        userMessage = "Response violated strict invariants and could not be corrected automatically.",
                        source = InvariantViolationSource.LLM,
                    ),
                )
            }

        return buildString {
            if (stoppedOnUserViolation) {
                appendLine("Invariant validation failed due to user prompt constraint violation.")
            } else {
                appendLine("Invariant validation failed after $attemptsUsed attempts.")
            }
            appendLine("Failed constraints:")
            effectiveFailures.forEachIndexed { index, failure ->
                appendLine("${index + 1}. ${failure.constraint}")
                appendLine("   Source: ${failure.source.name.lowercase()}")
                append("   ")
                append(failure.userMessage)
                if (index != effectiveFailures.lastIndex) {
                    appendLine()
                }
            }
        }.trimEnd()
    }

    private suspend fun handleBranchingPostResponse(
        prompt: String,
        response: String,
    ): List<String> {
        val classification = branchClassificationUseCase.classify(
            existingTopics = branchingSessionMemory.topicCatalog(),
            userPrompt = prompt,
            assistantResponse = response,
            model = currentModel,
        )
        val activation = branchingSessionMemory.resolveAndActivate(
            topicName = classification.topicName,
            subtopicName = classification.subtopicName,
        )

        branchingSessionMemory.recordSuccessfulTurn(
            prompt = prompt,
            response = response,
        )

        val updatedSummary = runCatching {
            branchingTopicSummaryStrategy.compact(
                previousSummary = branchingSessionMemory.activeTopicSummary(),
                messagesToCompact = listOf(
                    ConversationMessage.user(prompt),
                    ConversationMessage.assistant(response),
                ),
                model = currentModel,
            ).trim()
        }.getOrNull()
        if (!updatedSummary.isNullOrBlank()) {
            branchingSessionMemory.updateActiveTopicSummary(updatedSummary)
        }

        val messages = mutableListOf<String>()
        if (classification.usedFallback) {
            messages += "system> branch classification failed twice; using strict specific fallback routing"
        }
        if (activation.isNewTopic) {
            messages += "system> new topic found: '${activation.topic}'"
        }
        if (activation.isNewSubtopic) {
            messages += "system> new subtopic found in '${activation.topic}': '${activation.subtopic}'"
        }
        if (!activation.isNewTopic && !activation.isNewSubtopic && activation.switchedToExistingBranch) {
            messages += "system> switched to topic '${activation.topic}' / subtopic '${activation.subtopic}'"
        }

        return messages
    }

    private fun formatThinkingProgress(spinnerFrame: Char, elapsedMillis: Long): String {
        val safeElapsedMillis = elapsedMillis.coerceAtLeast(0L)
        val elapsedTenths = safeElapsedMillis / THINKING_TENTH_DIVISOR_MS
        val seconds = elapsedTenths / THINKING_TENTHS_PER_SECOND
        val tenths = elapsedTenths % THINKING_TENTHS_PER_SECOND
        return "$spinnerFrame $seconds.$tenths" + "s"
    }

    private fun isFileReferenceCommand(input: String): Boolean = input.startsWith("@")

    private fun handleFileReferenceCommand(input: String) {
        val path = input.removePrefix("@").trim()
        if (path.isEmpty()) {
            dialogBlocks += "system> usage: @<path>"
            return
        }

        pendingFileReferences += path
        dialogBlocks += "ref> $path"
    }

    private fun buildPromptForRequest(prompt: String): PreparedPrompt? {
        val inlineReferences = parseInlineFileReferences(prompt)
        val displayPrompt = inlineReferences.cleanedPrompt.ifBlank { prompt }
        val allReferences = orderedDistinctReferencePaths(
            pendingFileReferences + inlineReferences.references,
        )
        if (allReferences.isEmpty()) {
            return PreparedPrompt(
                displayPrompt = displayPrompt,
                requestPrompt = displayPrompt,
                inlineReferences = inlineReferences.references,
            )
        }

        val resolvedFiles = mutableListOf<ResolvedFileReference>()
        val pendingReferences = pendingFileReferences.toSet()
        for (path in allReferences) {
            val content = runCatching {
                fileReferenceReader.read(path)
            }.getOrElse { throwable ->
                if (path in pendingReferences) {
                    pendingFileReferences.remove(path)
                }
                val errorMessage = throwable.message ?: "Unexpected error"
                dialogBlocks += "system> unable to read file '$path': $errorMessage"
                return null
            }
            resolvedFiles += ResolvedFileReference(path = path, content = content)
        }

        return PreparedPrompt(
            displayPrompt = displayPrompt,
            requestPrompt = buildPromptWithFileReferences(displayPrompt, resolvedFiles),
            inlineReferences = inlineReferences.references,
        )
    }

    private fun buildPromptWithFileReferences(
        prompt: String,
        files: List<ResolvedFileReference>,
    ): String {
        if (files.isEmpty()) {
            return prompt
        }

        return buildString {
            append(prompt)
            append("\n\nClient note: The CLI already read the following local files and included their exact text below.")
            append("\nUse this file content directly. Do not ask the user to paste these files.")
            files.forEach { file ->
                append("\n\n[FILE] ")
                append(file.path)
                append('\n')
                append(file.content)
                if (!file.content.endsWith('\n')) {
                    append('\n')
                }
                append("[/FILE]")
            }
        }
    }

    private fun parseInlineFileReferences(prompt: String): InlineFileReferenceParseResult {
        if (!prompt.contains('@')) {
            return InlineFileReferenceParseResult(
                cleanedPrompt = prompt,
                references = emptyList(),
            )
        }

        val references = mutableListOf<String>()
        val cleanedPrompt = StringBuilder(prompt.length)
        var index = 0
        while (index < prompt.length) {
            val current = prompt[index]
            if (current == '@' && isInlineReferenceStart(prompt, index)) {
                val parsed = parseInlineReference(prompt, index + 1)
                if (parsed != null) {
                    references += parsed.path
                    index = parsed.nextIndex
                    continue
                }
            }

            cleanedPrompt.append(current)
            index += 1
        }

        val distinctReferences = orderedDistinctReferencePaths(references)
        val normalizedPrompt = if (distinctReferences.isEmpty()) {
            prompt
        } else {
            normalizePromptAfterReferenceRemoval(cleanedPrompt.toString())
        }

        return InlineFileReferenceParseResult(
            cleanedPrompt = normalizedPrompt,
            references = distinctReferences,
        )
    }

    private fun isInlineReferenceStart(input: String, index: Int): Boolean {
        if (index == 0) {
            return true
        }

        val previous = input[index - 1]
        return previous.isWhitespace() || previous == '(' || previous == '[' || previous == '{' || previous == ':'
    }

    private fun parseInlineReference(
        input: String,
        startIndex: Int,
    ): ParsedInlineReference? {
        if (startIndex >= input.length) {
            return null
        }

        return when (input[startIndex]) {
            '"' -> parseQuotedInlineReference(input, startIndex, '"')
            '\'' -> parseQuotedInlineReference(input, startIndex, '\'')
            else -> parseUnquotedInlineReference(input, startIndex)
        }
    }

    private fun parseQuotedInlineReference(
        input: String,
        startIndex: Int,
        quote: Char,
    ): ParsedInlineReference? {
        var index = startIndex + 1
        while (index < input.length && input[index] != quote) {
            index += 1
        }
        if (index >= input.length) {
            return null
        }

        val path = input.substring(startIndex + 1, index).trim()
        if (!looksLikeFilePath(path)) {
            return null
        }

        return ParsedInlineReference(path = path, nextIndex = index + 1)
    }

    private fun parseUnquotedInlineReference(
        input: String,
        startIndex: Int,
    ): ParsedInlineReference? {
        val lineEnd = input.indexOf('\n', startIndex).let { position ->
            if (position == -1) input.length else position
        }
        val lineRemainder = input.substring(startIndex, lineEnd).trimEnd()
        val looksLikeAbsolutePath = lineRemainder.startsWith("/") || lineRemainder.startsWith("~/")
        if (looksLikeAbsolutePath && lineRemainder.contains(' ')) {
            val path = lineRemainder.trimTrailingReferenceDelimiters()
            if (looksLikeFilePath(path)) {
                return ParsedInlineReference(path = path, nextIndex = lineEnd)
            }
        }

        var index = startIndex
        while (index < input.length && !input[index].isWhitespace()) {
            index += 1
        }
        val path = input.substring(startIndex, index).trimTrailingReferenceDelimiters()
        if (!looksLikeFilePath(path)) {
            return null
        }

        return ParsedInlineReference(path = path, nextIndex = index)
    }

    private fun looksLikeFilePath(value: String): Boolean {
        if (value.isBlank()) {
            return false
        }
        if (
            value.startsWith("/") ||
            value.startsWith("~/") ||
            value.startsWith("./") ||
            value.startsWith("../") ||
            value.contains("/")
        ) {
            return true
        }

        val extension = value.substringAfterLast('.', missingDelimiterValue = "")
        return extension.isNotEmpty() && extension.lowercase() in KNOWN_FILE_EXTENSIONS
    }

    private fun normalizePromptAfterReferenceRemoval(prompt: String): String {
        return prompt.lines()
            .joinToString(separator = "\n") { line ->
                line.replace(Regex("\\s{2,}"), " ").trim()
            }
            .trim()
    }

    private fun orderedDistinctReferencePaths(paths: List<String>): List<String> {
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        paths.forEach { rawPath ->
            val normalized = rawPath.trim()
            if (normalized.isEmpty()) {
                return@forEach
            }
            if (seen.add(normalized)) {
                result += normalized
            }
        }
        return result
    }

    private fun String.trimTrailingReferenceDelimiters(): String {
        var endIndex = length
        while (endIndex > 0 && this[endIndex - 1] in TRAILING_REFERENCE_DELIMITERS) {
            endIndex -= 1
        }
        return substring(0, endIndex)
    }

    private suspend fun handleCommand(input: String): Boolean {
        return when {
            input == "/help" -> {
                dialogBlocks += helpText()
                true
            }

            input == "/models" -> {
                dialogBlocks += modelsText()
                true
            }

            input == "/memory" -> {
                dialogBlocks += memoryUsageText()
                true
            }

            input == "/compact" -> {
                handleCompactionModeCommand()
                true
            }

            input == "/profile" -> {
                handleProfileCommand()
                true
            }

            input == "/workflow" -> {
                handleWorkflowCommand()
                true
            }

            input == "/mcp" -> {
                handleMcpCommand()
                true
            }

            input.startsWith("/mcp ") -> {
                handleMcpToolCommand(input)
                true
            }

            input == "/invariant" -> {
                handleInvariantCommand()
                true
            }

            isModelCommand(input) -> {
                handleModelCommand(input)
                true
            }

            input == "/reset" -> {
                clearWorkingMemory()
                resetConversation()
                workflowRuntimeState = if (workflowModeEnabled) {
                    WorkflowRuntimeState(step = WorkflowStep.USER_INPUT)
                } else {
                    null
                }
                workflowUiStep = if (workflowModeEnabled) {
                    WorkflowUiStep.USER_INPUT
                } else {
                    null
                }
                clearPersistedMemorySnapshot()
                persistMemorySnapshot()
                dialogBlocks.clear()
                dialogBlocks += "system> conversation has been reset"
                true
            }

            input == "/exit" -> false

            else -> {
                dialogBlocks += "system> unknown command. Type /help for available commands."
                true
            }
        }
    }

    private fun resetConversation() {
        sessionMemory.reset()
        branchingSessionMemory.reset()
        memoryUsageSnapshot = estimateHeuristicUsage(activeContextMessages())
        pendingFileReferences.clear()
    }

    private fun clearWorkingMemory() {
        workingMemoryState = null
        runCatching {
            workingMemoryStore?.clear()
        }
    }

    private fun resetWorkflowStepSessionContext() {
        resetConversation()
    }

    private fun initializeUserDefinedProfile() {
        if (userDefinedProfileInitialized) {
            return
        }

        userDefinedProfileInitialized = true
        reloadUserDefinedProfile()
    }

    private fun initializeUserDefinedWorkflow() {
        if (userDefinedWorkflowInitialized) {
            return
        }

        userDefinedWorkflowInitialized = true
        reloadActiveWorkflow()
    }

    private fun initializeInvariantConstraints() {
        if (invariantConstraintsInitialized) {
            return
        }

        invariantConstraintsInitialized = true
        val loadedConstraints = runCatching {
            invariantConstraintStore?.load()
        }.getOrNull().orEmpty()
        invariantConstraints = loadedConstraints.toMutableList()
    }

    private suspend fun initializeMcpServers() {
        if (mcpStartupInitialized) {
            return
        }

        mcpStartupInitialized = true
        ensureMcpServersLoaded()
        if (mcpServers.isEmpty()) {
            return
        }

        val startupServers = mcpServers.filterNot(McpServerConfig::isPublic)
        if (startupServers.isEmpty()) {
            return
        }

        val runtimeStates = initializeEnabledServersSafely(startupServers)
        pendingMcpStartupMessages += runtimeStates.flatMap { state ->
            buildList {
                if (state.status == McpRuntimeStatus.FAILED) {
                    add("system> MCP server '${state.server.name}' initialization failed: ${state.failureMessage ?: "Unexpected error"}")
                }
                if (state.status == McpRuntimeStatus.READY && state.toolCatalogStatus == McpToolCatalogStatus.FAILED) {
                    add(
                        "system> MCP tools for '${state.server.name}' could not be loaded: ${
                            state.toolCatalogFailureMessage ?: "Unexpected error"
                        }",
                    )
                }
            }
        }
    }

    private fun ensureMcpServersLoaded() {
        if (mcpServersLoaded) {
            return
        }

        mcpServersLoaded = true
        mcpServers = runCatching {
            mcpServerStore?.load()
        }.getOrNull().orEmpty().toMutableList()
        mcpMenuSelection = mcpMenuSelection.coerceIn(0, mcpServers.lastIndex.coerceAtLeast(0))
    }

    private suspend fun prepareMainTurnToolContext(): PreparedMainTurnToolContext {
        ensureMcpServersLoaded()
        val builtInTools = builtInPrivateToolProvider.loadTools()

        val publicServers = mcpServers.mapNotNull { server ->
            if (!server.enabled || !server.isPublic) {
                return@mapNotNull null
            }

            val transport = server.transport as? McpTransportConfig.Http ?: return@mapNotNull null
            PublicMcpServerCapability(
                serverLabel = server.name,
                serverUrl = transport.url,
            )
        }

        val privateServers = mcpServers.filter { server ->
            server.enabled && !server.isPublic
        }
        if (privateServers.isEmpty()) {
            return PreparedMainTurnToolContext(
                capabilities = LlmToolCapabilities(
                    publicMcpServers = publicServers,
                    privateTools = builtInTools,
                ),
            )
        }

        val runtimeStates = initializeEnabledServersSafely(privateServers)
        val preparedPrivateTools = mcpPrivateToolProvider.build(runtimeStates)
        return PreparedMainTurnToolContext(
            capabilities = LlmToolCapabilities(
                publicMcpServers = publicServers,
                privateTools = preparedPrivateTools.privateTools + builtInTools,
            ),
            systemMessages = preparedPrivateTools.systemMessages,
        )
    }

    private suspend fun initializeEnabledServersSafely(servers: List<McpServerConfig>): List<McpServerRuntimeState> {
        if (servers.isEmpty()) {
            return emptyList()
        }

        return runCatching {
            mcpRuntimeService.initializeEnabledServers(servers)
        }.getOrElse { throwable ->
            servers.map { server ->
                McpServerRuntimeState(
                    server = server,
                    status = if (server.enabled) McpRuntimeStatus.FAILED else McpRuntimeStatus.DISABLED,
                    failureMessage = if (server.enabled) {
                        throwable.message?.trim().takeUnless { it.isNullOrEmpty() } ?: "Unexpected error"
                    } else {
                        null
                    },
                )
            }
        }
    }

    private fun consumePendingMcpStartupMessages(): List<String> {
        if (pendingMcpStartupMessages.isEmpty()) {
            return emptyList()
        }

        return pendingMcpStartupMessages.toList().also {
            pendingMcpStartupMessages.clear()
        }
    }

    private fun reloadUserDefinedProfile() {
        userDefinedProfilePreferences = runCatching {
            userDefinedProfileStore?.load()
        }.getOrNull()
        rebuildSystemPrompt()
        memoryUsageSnapshot = estimateHeuristicUsage(activeContextMessages())
    }

    private fun reloadActiveWorkflow() {
        activeWorkflow = runCatching {
            userDefinedWorkflowStore?.loadActiveWorkflow()
        }.getOrNull()
    }

    private fun rebuildSystemPrompt() {
        systemPrompt = systemPromptBuilder.build(
            basePrompt = baseSystemPrompt,
            userDefinedProfile = userDefinedProfilePreferences,
        )
    }

    private fun initializePersistentMemory() {
        if (!persistentMemoryEnabled || persistentMemoryInitialized) {
            return
        }

        persistentMemoryInitialized = true
        val persistedState = runCatching { sessionMemoryStore?.load() }.getOrNull() ?: return
        workflowModeEnabled = persistedState.workflowModeEnabled
        workflowRuntimeState = if (workflowModeEnabled) {
            normalizedWorkflowRuntimeState(persistedState.workflowRuntimeState)
        } else {
            null
        }
        workflowUiStep = if (workflowModeEnabled) {
            when (workflowRuntimeState?.step ?: WorkflowStep.USER_INPUT) {
                WorkflowStep.USER_INPUT -> WorkflowUiStep.USER_INPUT
                WorkflowStep.PLANNING_APPROVAL -> WorkflowUiStep.PLANNING
                WorkflowStep.EXECUTION_APPROVAL -> WorkflowUiStep.EXECUTION
            }
        } else {
            null
        }
        val restoredLinear = sessionMemory.restore(
            persistedMessages = persistedState.messages,
            persistedCompactedSummary = persistedState.compactedSummary,
        )
        val restoredBranching = branchingSessionMemory.restore(persistedState.branchingState)
        val hasInvalidPersistedState = !restoredLinear || (persistedState.branchingState != null && !restoredBranching)
        val persistedMode = SessionCompactionMode.fromIdOrNull(persistedState.activeCompactionModeId)
            ?.takeIf { mode -> mode in availableCompactionModes }
        activeCompactionMode = when {
            persistedMode == SessionCompactionMode.BRANCHING && restoredBranching -> SessionCompactionMode.BRANCHING
            persistedMode != null && persistedMode != SessionCompactionMode.BRANCHING && restoredLinear -> persistedMode
            else -> defaultCompactionMode
        }

        if (activeCompactionMode == SessionCompactionMode.BRANCHING) {
            sessionMemory.reset()
            if (!restoredBranching) {
                branchingSessionMemory.reset()
            }
        } else {
            branchingSessionMemory.reset()
            if (!restoredLinear) {
                sessionMemory.reset()
            }
        }
        if (hasInvalidPersistedState) {
            clearPersistedMemorySnapshot()
        }

        val activeContext = activeContextMessages()
        memoryUsageSnapshot = persistedState.usage?.takeIf { usage ->
            usage.estimatedTokens > 0 && usage.messageCount == activeContext.size
        } ?: estimateHeuristicUsage(activeContext)
    }

    private fun initializeWorkingMemory() {
        if (!workingMemoryEnabled || workingMemoryInitialized) {
            return
        }

        workingMemoryInitialized = true
        workingMemoryState = runCatching { workingMemoryStore?.load() }.getOrNull()
        if (workingMemoryState != null) {
            memoryUsageSnapshot = estimateHeuristicUsage(activeContextMessages())
        }
    }

    private fun initializeProfileMemory() {
        if (!profileMemoryEnabled || profileMemoryInitialized) {
            return
        }

        profileMemoryInitialized = true
        val store = profileMemoryStore ?: return
        val loadedState = runCatching { store.load() }.getOrNull()
        val environmentFacts = profileEnvironmentFactsProvider.read()
        profileMemoryState = if (loadedState == null) {
            ProfileMemoryState(
                preferences = ProfilePreferenceState(),
                environmentFacts = environmentFacts,
            )
        } else {
            loadedState.copy(environmentFacts = environmentFacts)
        }
        memoryUsageSnapshot = estimateHeuristicUsage(activeContextMessages())
    }

    private suspend fun updateWorkingMemoryAfterSuccessfulTurn(
        userPrompt: String,
        assistantResponse: String,
    ) {
        if (!workingMemoryEnabled) {
            return
        }

        val distillationUseCase = workingMemoryDistillationUseCase ?: return
        val nextTaskState = runCatching {
            distillationUseCase.distill(
                previousTaskState = workingMemoryState?.taskState,
                recentMessages = listOf(
                    ConversationMessage.user(userPrompt),
                    ConversationMessage.assistant(assistantResponse),
                ),
                model = currentModel,
            )
        }.getOrNull() ?: return

        val nextState = WorkingMemoryState(taskState = nextTaskState)
        workingMemoryState = nextState
        runCatching {
            workingMemoryStore?.save(nextState)
        }
    }

    private suspend fun updateProfileMemoryAfterSuccessfulTurn(
        userPrompt: String,
    ) {
        if (!profileMemoryEnabled) {
            return
        }

        val store = profileMemoryStore ?: return
        val distillationUseCase = profileMemoryDistillationUseCase ?: return
        val currentState = profileMemoryState ?: ProfileMemoryState(
            preferences = ProfilePreferenceState(),
            environmentFacts = profileEnvironmentFactsProvider.read(),
        )
        val nextPreferences = runCatching {
            distillationUseCase.distill(
                previousPreferenceState = currentState.preferences,
                recentMessages = listOf(
                    ConversationMessage.user(userPrompt),
                ),
                model = currentModel,
            )
        }.getOrNull() ?: return

        val nextState = currentState.copy(
            preferences = nextPreferences,
            environmentFacts = profileEnvironmentFactsProvider.read(),
        )
        profileMemoryState = nextState
        runCatching {
            store.save(nextState)
        }
    }

    private fun persistMemorySnapshot() {
        if (!persistentMemoryEnabled) {
            return
        }

        val state = SessionMemoryState(
            messages = sessionMemory.snapshot(),
            compactedSummary = sessionMemory.compactedSummarySnapshot(),
            usage = memoryUsageSnapshot,
            activeCompactionModeId = activeCompactionMode.id,
            branchingState = if (activeCompactionMode == SessionCompactionMode.BRANCHING) {
                branchingSessionMemory.snapshot()
            } else {
                null
            },
            workflowModeEnabled = workflowModeEnabled,
            workflowRuntimeState = if (workflowModeEnabled) {
                normalizedWorkflowRuntimeState(workflowRuntimeState)
            } else {
                null
            },
        )
        runCatching {
            sessionMemoryStore?.save(state)
        }
    }

    private fun clearPersistedMemorySnapshot() {
        if (!persistentMemoryEnabled) {
            return
        }

        runCatching {
            sessionMemoryStore?.clear()
        }
    }

    private fun renderScreen() {
        io.showCursor()
        io.clearScreen()
        io.writeLine()
        io.writeLine()
        io.writeLine(logoBanner())
        io.writeLine()
        io.writeLine("    type your prompt and press Enter")
        io.writeLine(
            "    commands: /help, /models, /model <id|number>, /memory, /compact, /profile, /workflow, /mcp, /invariant, /reset, /exit, @<path>",
        )
        io.writeLine()

        dialogBlocks.forEachIndexed { index, block ->
            io.writeLine(block)
            if (index != dialogBlocks.lastIndex) {
                io.writeLine()
            }
        }

        if (dialogBlocks.isNotEmpty()) {
            io.writeLine()
        }
    }

    private fun logoBanner(): String = """
        █████╗ ██╗     █████╗ ██████╗ ██╗   ██╗███████╗███╗   ██╗████████╗
       ██╔══██╗██║    ██╔══██╗██╔══██╗██║   ██║██╔════╝████╗  ██║╚══██╔══╝
       ███████║██║    ███████║██║  ██║██║   ██║█████╗  ██╔██╗ ██║   ██║
       ██╔══██║██║    ██╔══██║██║  ██║╚██╗ ██╔╝██╔══╝  ██║╚██╗██║   ██║
       ██║  ██║██║    ██║  ██║██████╔╝ ╚████╔╝ ███████╗██║ ╚████║   ██║
       ╚═╝  ╚═╝╚═╝    ╚═╝  ╚═╝╚═════╝   ╚═══╝  ╚══════╝╚═╝  ╚═══╝   ╚═╝
    """.trimIndent().lineSequence().joinToString(separator = "\n") { line -> "    $line" }

    private fun helpText(): String = """
        Available commands:
        /help                show this help message
        /models              list available built-in models
        /model <id|number>   switch active model (must be from /models)
        /memory              show session-memory context usage
        /compact             choose memory compaction strategy
        /profile             choose active user profile
        /workflow            enable workflow mode with workflow selection (toggle off when enabled)
        /mcp                 configure MCP servers
        /mcp <n> <tool> [json-object-args]
                             call an MCP tool on an enabled ready server
        /invariant           configure invariant constraints
        /reset               clear conversation and working memory; keep current system prompt
        /exit                close the application
        @<path>              attach file for the next prompt
    """.trimIndent()

    private fun handleMcpCommand() {
        val store = mcpServerStore
        if (store == null || mcpServers.isEmpty()) {
            dialogBlocks += "system> no valid MCP servers found"
            return
        }

        var currentSelection = mcpMenuSelection.coerceIn(0, mcpServers.lastIndex)
        var reuseMenuAnchor = false
        while (true) {
            val menuResult = io.openMcpMenu(
                options = mcpServers.map { server ->
                    McpMenuOption(
                        name = server.name,
                        enabled = server.enabled,
                        runtimeStatus = mcpRuntimeService.runtimeStateFor(server).status,
                    )
                },
                currentSelection = currentSelection,
                reuseAnchor = reuseMenuAnchor,
            ) ?: run {
                mcpMenuSelection = currentSelection
                return
            }

            currentSelection = menuResult.selectedIndex.coerceIn(0, mcpServers.lastIndex)
            reuseMenuAnchor = true
            val currentServer = mcpServers[currentSelection]

            if (menuResult.action == McpMenuAction.INFO) {
                dialogBlocks += formatMcpToolsDialog(currentServer)
                mcpMenuSelection = currentSelection
                return
            }

            mcpServers[currentSelection] = currentServer.copy(enabled = !currentServer.enabled)

            val persisted = runCatching {
                store.save(mcpServers.toList())
                true
            }.getOrDefault(false)
            if (!persisted) {
                mcpServers[currentSelection] = currentServer
                dialogBlocks += "system> failed to persist MCP server state"
            } else {
                mcpRuntimeService.clearFailureState(mcpServers[currentSelection])
            }

            mcpMenuSelection = currentSelection
        }
    }

    private suspend fun handleMcpToolCommand(input: String) {
        if (mcpServerStore == null || mcpServers.isEmpty()) {
            dialogBlocks += "system> no valid MCP servers found"
            return
        }

        val command = parseMcpToolCommand(input) ?: return
        val server = mcpServers.getOrNull(command.serverIndex - 1)
        if (server == null) {
            dialogBlocks += "system> invalid MCP server index '${command.serverIndexRaw}'"
            return
        }

        if (!server.enabled) {
            dialogBlocks += "system> MCP server '${server.name}' is disabled; enable the server first"
            return
        }

        val runtimeState = mcpRuntimeService.runtimeStateFor(server)
        if (runtimeState.status != McpRuntimeStatus.READY) {
            dialogBlocks += "system> MCP server '${server.name}' is not initialized"
            return
        }

        val toolCatalog = mcpRuntimeService.toolCatalogFor(server)
        when (toolCatalog.status) {
            McpToolCatalogStatus.FAILED -> {
                dialogBlocks += "system> MCP tools for '${server.name}' could not be loaded: ${toolCatalog.failureMessage ?: "Unexpected error"}"
                return
            }

            McpToolCatalogStatus.NOT_REQUESTED -> {
                dialogBlocks += "system> MCP tools for '${server.name}' are not available"
                return
            }

            McpToolCatalogStatus.LOADED -> Unit
        }

        if (toolCatalog.tools.none { tool -> tool.name == command.toolName }) {
            dialogBlocks += "system> MCP server '${server.name}' has no tool named '${command.toolName}'"
            return
        }

        runCatching {
            mcpRuntimeService.callTool(
                server = server,
                toolName = command.toolName,
                arguments = command.arguments,
            )
        }.onSuccess { result ->
            dialogBlocks += formatMcpToolCallResult(
                server = server,
                toolName = command.toolName,
                result = result,
            )
        }.onFailure { throwable ->
            dialogBlocks += "system> MCP tool '${command.toolName}' failed: ${throwable.message?.trim().takeUnless { it.isNullOrEmpty() } ?: "Unexpected error"}"
        }
    }

    private fun parseMcpToolCommand(input: String): ParsedMcpToolCommand? {
        val rawPayload = input.removePrefix("/mcp").trim()
        if (rawPayload.isEmpty()) {
            dialogBlocks += MCP_TOOL_COMMAND_USAGE
            return null
        }

        val tokens = rawPayload.split(Regex("\\s+"), limit = 3)
        if (tokens.size < 2) {
            dialogBlocks += MCP_TOOL_COMMAND_USAGE
            return null
        }

        val serverIndexRaw = tokens[0].trim()
        val serverIndex = serverIndexRaw.toIntOrNull()
        if (serverIndex == null || serverIndex < 1) {
            dialogBlocks += "system> invalid MCP server index '$serverIndexRaw'"
            return null
        }

        val toolName = tokens[1].trim()
        if (toolName.isEmpty()) {
            dialogBlocks += MCP_TOOL_COMMAND_USAGE
            return null
        }

        val arguments = tokens.getOrNull(2)
            ?.trim()
            ?.takeIf { value -> value.isNotEmpty() }
            ?.let { rawArguments ->
                parseMcpToolArguments(rawArguments) ?: return null
            }
            ?: EMPTY_JSON_OBJECT

        return ParsedMcpToolCommand(
            serverIndex = serverIndex,
            serverIndexRaw = serverIndexRaw,
            toolName = toolName,
            arguments = arguments,
        )
    }

    private fun parseMcpToolArguments(rawArguments: String): JsonObject? {
        val parsed = runCatching {
            mcpCommandJson.parseToJsonElement(rawArguments)
        }.getOrElse {
            dialogBlocks += "system> MCP tool arguments must be valid JSON"
            return null
        }

        return parsed as? JsonObject ?: run {
            dialogBlocks += "system> MCP tool arguments must be a JSON object"
            null
        }
    }

    private fun formatMcpToolCallResult(
        server: McpServerConfig,
        toolName: String,
        result: McpToolCallResult,
    ): String {
        val envelope = buildJsonObject {
            put("is_error", result.isError)
            put("content", result.content)
            result.structuredContent?.let { structuredContent ->
                put("structured_content", structuredContent)
            }
            result.meta?.let { meta ->
                put("_meta", meta)
            }
        }
        return buildString {
            appendLine("mcp> ${server.name}/$toolName")
            append(mcpCommandJson.encodeToString(JsonObject.serializer(), envelope))
        }
    }

    private fun formatMcpToolsDialog(server: McpServerConfig): String {
        val runtimeState = mcpRuntimeService.runtimeStateFor(server)
        if (runtimeState.status != McpRuntimeStatus.READY) {
            return "system> MCP server '${server.name}' is not initialized"
        }

        val toolCatalog = mcpRuntimeService.toolCatalogFor(server)
        return when (toolCatalog.status) {
            McpToolCatalogStatus.FAILED -> {
                "system> MCP tools for '${server.name}' could not be loaded: ${toolCatalog.failureMessage ?: "Unexpected error"}"
            }

            McpToolCatalogStatus.LOADED -> {
                if (toolCatalog.tools.isEmpty()) {
                    "system> MCP server '${server.name}' has no tools"
                } else {
                    buildString {
                        appendLine("mcp> tools for '${server.name}'")
                        toolCatalog.tools.forEachIndexed { index, tool ->
                            appendLine()
                            append(formatMcpToolEntry(index, tool))
                        }
                    }.trimEnd()
                }
            }

            McpToolCatalogStatus.NOT_REQUESTED -> {
                "system> MCP tools for '${server.name}' are not available"
            }
        }
    }

    private fun formatMcpToolEntry(index: Int, tool: McpToolDefinition): String {
        val displayName = tool.title?.trim().takeUnless { it.isNullOrEmpty() } ?: tool.name
        val description = tool.description?.trim().takeUnless { it.isNullOrEmpty() } ?: "No description provided."
        return buildString {
            append("${index + 1}. $displayName")
            if (displayName != tool.name) {
                append(" (${tool.name})")
            }
            appendLine()
            append(description)
        }
    }

    private fun handleWorkflowCommand() {
        if (workflowModeEnabled) {
            workflowModeEnabled = false
            workflowRuntimeState = null
            workflowUiStep = null
            persistMemorySnapshot()
            return
        }

        val store = userDefinedWorkflowStore
        if (store == null) {
            dialogBlocks += "system> workflow store is unavailable"
            return
        }

        val workflows = runCatching { store.listWorkflows() }.getOrNull().orEmpty()
        if (workflows.isEmpty()) {
            dialogBlocks += "system> no valid workflows found (expected: workflow-<name>.json)"
            return
        }

        val persistedActiveFileName = runCatching { store.activeWorkflowFileName() }.getOrNull()
        val effectiveCurrentFileName = persistedActiveFileName ?: activeWorkflow?.fileName
        val currentSelection = workflows.indexOfFirst { workflow -> workflow.fileName == effectiveCurrentFileName }
            .takeIf { index -> index >= 0 }
            ?: 0
        val selectedIndex = io.openWorkflowMenu(
            options = workflows.map(::formatWorkflowMenuOption),
            currentSelection = currentSelection,
        ) ?: return
        val selectedWorkflow = workflows.getOrNull(selectedIndex) ?: return

        val shouldPersistSelection = selectedWorkflow.fileName != persistedActiveFileName
        val selectedFileChanged = selectedWorkflow.fileName != effectiveCurrentFileName
        if (shouldPersistSelection) {
            val switched = runCatching {
                store.setActiveWorkflow(selectedWorkflow.fileName)
            }.getOrDefault(false)
            if (!switched) {
                dialogBlocks += "system> failed to switch workflow '${selectedWorkflow.displayName}'"
                return
            }
        }

        reloadActiveWorkflow()
        if (selectedFileChanged) {
            resetConversation()
        }
        workflowModeEnabled = true
        workflowRuntimeState = WorkflowRuntimeState(step = WorkflowStep.USER_INPUT)
        workflowUiStep = WorkflowUiStep.USER_INPUT
        persistMemorySnapshot()
    }

    private fun handleInvariantCommand() {
        val store = invariantConstraintStore
        if (store == null) {
            dialogBlocks += "system> invariant constraint store is unavailable"
            return
        }

        var currentSelection = invariantConstraints.size
        while (true) {
            val addNewItemIndex = invariantConstraints.size
            val menuOptions = invariantConstraints + INVARIANT_ADD_NEW_CONSTRAINT_LABEL
            val selection = io.openInvariantMenu(
                options = menuOptions,
                currentSelection = currentSelection.coerceIn(0, menuOptions.lastIndex),
            ) ?: return

            val selectedIndex = selection.selectedIndex.coerceIn(0, addNewItemIndex)
            currentSelection = selectedIndex

            when (selection.action) {
                InvariantMenuAction.DELETE -> {
                    if (selectedIndex == addNewItemIndex) {
                        dialogBlocks += "system> '$INVARIANT_ADD_NEW_CONSTRAINT_LABEL' cannot be removed"
                        continue
                    }

                    val removedConstraint = invariantConstraints.removeAt(selectedIndex)
                    val persisted = runCatching {
                        store.save(invariantConstraints.toList())
                        true
                    }.getOrDefault(false)
                    if (!persisted) {
                        invariantConstraints.add(selectedIndex, removedConstraint)
                        dialogBlocks += "system> failed to persist invariant constraints"
                        continue
                    }

                    dialogBlocks += "system> invariant constraint removed: \"$removedConstraint\""
                    currentSelection = selectedIndex.coerceAtMost(invariantConstraints.size)
                }

                InvariantMenuAction.CONFIRM -> {
                    if (selectedIndex != addNewItemIndex) {
                        continue
                    }

                    val input = readInvariantConstraintInput() ?: continue
                    val normalizedInput = input.trim()
                    if (normalizedInput.isEmpty()) {
                        dialogBlocks += "system> invariant constraint must not be blank"
                        continue
                    }

                    val normalizedKey = normalizedInput.lowercase()
                    val alreadyExists = invariantConstraints.any { existingConstraint ->
                        existingConstraint.trim().lowercase() == normalizedKey
                    }
                    if (alreadyExists) {
                        dialogBlocks += "system> invariant constraint already exists"
                        continue
                    }

                    invariantConstraints += normalizedInput
                    val persisted = runCatching {
                        store.save(invariantConstraints.toList())
                        true
                    }.getOrDefault(false)
                    if (!persisted) {
                        invariantConstraints.removeAt(invariantConstraints.lastIndex)
                        dialogBlocks += "system> failed to persist invariant constraints"
                        continue
                    }

                    dialogBlocks += "system> invariant constraint added"
                    currentSelection = invariantConstraints.lastIndex
                }
            }
        }
    }

    private fun readInvariantConstraintInput(): String? {
        renderScreen()
        io.showCursor()
        return try {
            io.readLineInFooter(
                prompt = "constraint> ",
                divider = inputDivider,
                footerLabel = workflowFooterLabel(),
            )
        } finally {
            io.hideCursor()
        }
    }

    private fun modelsText(): String = buildString {
        appendLine("Available models:")
        availableModelIds.forEachIndexed { index, modelId ->
            val marker = if (modelId == currentModel) " * " else "   "
            val model = modelById[modelId]
            if (model == null) {
                appendLine("$marker${index + 1}. $modelId")
                return@forEachIndexed
            }

            val pricing = model.pricing
            appendLine(
                "$marker${index + 1}. $modelId " +
                    "(ctx=${formatIntWithGrouping(model.contextWindowTokens)}; " +
                    "in=$${formatRate(pricing.inputUsdPer1M)}/1M; " +
                    "out=$${formatRate(pricing.outputUsdPer1M)}/1M)",
            )
        }
    }.trimEnd()

    private fun memoryUsageText(): String {
        val usedTokens = memoryUsageSnapshot.estimatedTokens.coerceAtLeast(0)
        val contextWindow = modelById[currentModel]?.contextWindowTokens
        if (contextWindow == null || contextWindow <= 0) {
            return """
                memory> Model: $currentModel
                memory> Used: ${formatIntWithGrouping(usedTokens)}/n/a (n/a) | Remaining: n/a
                memory> [${"-".repeat(MEMORY_BAR_WIDTH)}]
                memory> Estimate: ${memoryEstimateLabel(memoryUsageSnapshot.source)}
            """.trimIndent()
        }

        val remainingTokens = (contextWindow - usedTokens).coerceAtLeast(0)
        val percentUsed = usedTokens * 100.0 / contextWindow
        return """
            memory> Model: $currentModel
            memory> Used: ${formatIntWithGrouping(usedTokens)}/${formatIntWithGrouping(contextWindow)} (${formatPercentage(percentUsed)}) | Remaining: ${formatIntWithGrouping(remainingTokens)}
            memory> [${buildMemoryUsageBar(usedTokens, contextWindow)}]
            memory> Estimate: ${memoryEstimateLabel(memoryUsageSnapshot.source)}
        """.trimIndent()
    }

    private fun handleCompactionModeCommand() {
        if (availableCompactionModes.isEmpty()) {
            dialogBlocks += "system> no compaction strategies are available"
            return
        }

        val options = availableCompactionModes.map { mode -> mode.label }
        val currentSelection = availableCompactionModes.indexOf(activeCompactionMode)
            .takeIf { index -> index >= 0 }
            ?: 0
        val selectedIndex = io.openCompactionMenu(
            options = options,
            currentSelection = currentSelection,
        ) ?: return
        val selectedMode = availableCompactionModes.getOrNull(selectedIndex) ?: return

        if (selectedMode == activeCompactionMode) {
            dialogBlocks += "system> compaction strategy is already '${selectedMode.label}'"
            return
        }

        val crossingBranchingBoundary =
            (selectedMode == SessionCompactionMode.BRANCHING) != (activeCompactionMode == SessionCompactionMode.BRANCHING)
        activeCompactionMode = selectedMode
        if (crossingBranchingBoundary) {
            resetConversation()
        } else if (selectedMode == SessionCompactionMode.SLIDING_WINDOW || selectedMode == SessionCompactionMode.FACT_MAP) {
            sessionMemory.clearCompactedSummary()
            memoryUsageSnapshot = estimateHeuristicUsage(activeContextMessages())
        } else {
            memoryUsageSnapshot = estimateHeuristicUsage(activeContextMessages())
        }
        persistMemorySnapshot()
        dialogBlocks += "system> compaction strategy set to '${selectedMode.label}'"
    }

    private fun handleProfileCommand() {
        val store = userDefinedProfileStore
        if (store == null) {
            dialogBlocks += "system> profile store is unavailable"
            return
        }

        val profiles = runCatching { store.listProfiles() }.getOrNull().orEmpty()
        if (profiles.isEmpty()) {
            dialogBlocks += "system> no valid user profiles found (expected: user-profile-<name>.json)"
            return
        }

        val currentFileName = runCatching { store.activeProfileFileName() }.getOrNull()
        val currentSelection = profiles.indexOfFirst { profile -> profile.fileName == currentFileName }
            .takeIf { index -> index >= 0 }
            ?: 0
        val selectedIndex = io.openProfileMenu(
            options = profiles.map(::formatProfileMenuOption),
            currentSelection = currentSelection,
        ) ?: return
        val selectedProfile = profiles.getOrNull(selectedIndex) ?: return

        if (selectedProfile.fileName == currentFileName) {
            dialogBlocks += "system> profile '${selectedProfile.displayName}' is already active"
            return
        }

        val switched = runCatching {
            store.setActiveProfile(selectedProfile.fileName)
        }.getOrDefault(false)
        if (!switched) {
            dialogBlocks += "system> failed to switch profile '${selectedProfile.displayName}'"
            return
        }

        reloadUserDefinedProfile()
        resetConversation()
        persistMemorySnapshot()
        dialogBlocks += "system> active profile set to '${selectedProfile.displayName}'"
    }

    private fun formatProfileMenuOption(profile: UserProfileOption): String {
        return "${profile.displayName} [${profile.fileName}]"
    }

    private fun formatWorkflowMenuOption(workflow: UserWorkflowOption): String {
        return "${workflow.displayName} [${workflow.fileName}]"
    }

    private fun activeCompactionCoordinator(): SessionMemoryCompactionCoordinator? {
        return compactionCoordinators[activeCompactionMode]
    }

    private fun effectiveProfileMemoryState(): ProfileMemoryState? {
        val distilledState = profileMemoryState ?: return null
        val mergedPreferences = ProfilePreferenceStateMerger.merge(
            distilled = distilledState.preferences,
            userDefined = userDefinedProfilePreferences,
        )
        return distilledState.copy(preferences = mergedPreferences)
    }

    private fun activeContextMessages(systemPromptOverride: String = systemPrompt): List<ConversationMessage> {
        return if (activeCompactionMode == SessionCompactionMode.BRANCHING) {
            buildPromptUseCase.buildContext(
                systemPrompt = systemPromptOverride,
                session = branchingSessionMemory.activePromptDataSnapshot(),
                workingTaskState = workingMemoryState?.taskState,
                profileMemoryState = effectiveProfileMemoryState(),
            ).toConversation()
        } else {
            buildPromptUseCase.buildContext(
                systemPrompt = systemPromptOverride,
                session = sessionMemory.promptDataSnapshot(),
                workingTaskState = workingMemoryState?.taskState,
                profileMemoryState = effectiveProfileMemoryState(),
            ).toConversation()
        }
    }

    private fun memoryEstimateLabel(source: MemoryEstimateSource): String = when (source) {
        MemoryEstimateSource.HYBRID -> "hybrid (usage+assistant)"
        MemoryEstimateSource.HEURISTIC -> "heuristic (text-length)"
    }

    private fun buildMemoryUsageBar(usedTokens: Int, contextWindowTokens: Int): String {
        if (contextWindowTokens <= 0) {
            return "-".repeat(MEMORY_BAR_WIDTH)
        }
        val ratio = (usedTokens.toDouble() / contextWindowTokens).coerceIn(0.0, 1.0)
        val filled = (ratio * MEMORY_BAR_WIDTH).roundToInt().coerceIn(0, MEMORY_BAR_WIDTH)
        return "#".repeat(filled) + "-".repeat(MEMORY_BAR_WIDTH - filled)
    }

    private fun formatPercentage(percent: Double): String {
        val scaled = (percent * PERCENT_DECIMAL_SCALE).roundToLong().coerceAtLeast(0L)
        val integral = scaled / PERCENT_DECIMAL_SCALE
        val fraction = (scaled % PERCENT_DECIMAL_SCALE).toString().padStart(PERCENT_DECIMAL_DIGITS, '0')
        return "$integral.$fraction%"
    }

    private fun isModelCommand(input: String): Boolean {
        return input == "/model" || input.startsWith("/model ")
    }

    private fun handleModelCommand(input: String) {
        val parts = input.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size != 2 || parts[1].isBlank()) {
            dialogBlocks += "system> usage: /model <id|number>. Current model: $currentModel"
            return
        }

        val requestedModelArg = parts[1].trim()
        val requestedModel = resolveRequestedModel(requestedModelArg)
        if (requestedModel == null) {
            dialogBlocks += "system> unknown model '$requestedModelArg'. Run /models to view available models."
            return
        }

        if (requestedModel == currentModel) {
            dialogBlocks += "system> model '$requestedModel' is already active"
            return
        }

        currentModel = requestedModel
        dialogBlocks += "system> model switched to '$requestedModel'"
    }

    private fun resolveRequestedModel(argument: String): String? {
        val index = argument.toIntOrNull()
        if (index != null) {
            if (index !in 1..availableModelIds.size) {
                return null
            }
            return availableModelIds[index - 1]
        }
        return availableModelIds.firstOrNull { it == argument }
    }

    private fun formatUserPrompt(text: String): String {
        val marker = "> "
        val indent = " ".repeat(marker.length)
        val lines = text.lines()

        if (lines.isEmpty()) {
            return marker.trimEnd()
        }

        return buildString {
            append(marker)
            append(lines.first())
            lines.drop(1).forEach { line ->
                append('\n')
                append(indent)
                append(line)
            }
        }
    }

    private fun buildUsageSnapshotAfterSuccessfulTurn(
        responseContent: String,
        usage: TokenUsage?,
        messages: List<ConversationMessage>,
    ): MemoryUsageSnapshot {
        if (usage == null) {
            return estimateHeuristicUsage(messages)
        }

        val usageDerivedEstimate = (usage.inputTokens + estimateMessageTokens(responseContent)).coerceAtLeast(1)
        val heuristicEstimate = estimateSessionTokensHeuristically(messages).coerceAtLeast(1)
        val estimatedTokens = maxOf(usageDerivedEstimate, heuristicEstimate)
        return MemoryUsageSnapshot(
            estimatedTokens = estimatedTokens,
            source = MemoryEstimateSource.HYBRID,
            messageCount = messages.size,
        )
    }

    private fun estimateHeuristicUsage(messages: List<ConversationMessage>): MemoryUsageSnapshot {
        val estimatedTokens = estimateSessionTokensHeuristically(messages).coerceAtLeast(1)
        return MemoryUsageSnapshot(
            estimatedTokens = estimatedTokens,
            source = MemoryEstimateSource.HEURISTIC,
            messageCount = messages.size,
        )
    }

    private fun estimateSessionTokensHeuristically(messages: List<ConversationMessage>): Int {
        return REQUEST_OVERHEAD_TOKENS + messages.sumOf { message ->
            estimateMessageTokens(message.content)
        }
    }

    private fun estimateMessageTokens(content: String): Int {
        return MESSAGE_OVERHEAD_TOKENS + estimateTextTokens(content)
    }

    private fun estimateTextTokens(text: String): Int {
        if (text.isEmpty()) {
            return 0
        }
        return ceil(text.length / CHARS_PER_TOKEN).toInt()
    }

    private fun formatAssistantResponse(text: String, usage: TokenUsage?, elapsedSeconds: Double): String {
        val marker = "⏺ "
        val indent = " ".repeat(marker.length)
        val lines = text.lines()

        val content = buildString {
            append(marker)
            append(lines.firstOrNull().orEmpty())
            lines.drop(1).forEach { line ->
                append('\n')
                append(indent)
                append(line)
            }
        }

        return buildString {
            append(content)
            append('\n')
            append('\n')
            append(indent)
            append(formatTokenUsage(usage))
            append('\n')
            append(indent)
            append(formatResponsePrice(usage))
            append('\n')
            append(indent)
            append(formatResponseTime(elapsedSeconds))
        }
    }

    private fun formatTokenUsage(usage: TokenUsage?): String {
        return if (usage == null) {
            "tokens> Total: n/a | Input: n/a | Output: n/a"
        } else {
            "tokens> Total: ${usage.totalTokens} | Input: ${usage.inputTokens} | Output: ${usage.outputTokens}"
        }
    }

    private fun formatResponsePrice(usage: TokenUsage?): String {
        val modelRate = modelById[currentModel]?.pricing
        if (usage == null) {
            return "price> n/a (token usage unavailable)"
        }
        if (modelRate == null) {
            return "price> n/a (pricing not configured for '$currentModel')"
        }

        val inputCost = usage.inputTokens * modelRate.inputUsdPer1M / TOKENS_PER_MILLION
        val outputCost = usage.outputTokens * modelRate.outputUsdPer1M / TOKENS_PER_MILLION
        val totalCost = inputCost + outputCost
        return "price> Total: $${formatUsd(totalCost)}"
    }

    private fun formatUsd(amount: Double): String {
        val scaled = (amount * PRICE_DECIMAL_SCALE).roundToLong()
        val sign = if (scaled < 0) "-" else ""
        val absoluteScaled = abs(scaled)
        val dollars = absoluteScaled / PRICE_DECIMAL_SCALE
        val fraction = (absoluteScaled % PRICE_DECIMAL_SCALE).toString().padStart(PRICE_DECIMAL_DIGITS, '0')
        return "$sign$dollars.$fraction"
    }

    private fun formatRate(value: Double): String {
        val scaled = (value * RATE_DECIMAL_SCALE).roundToLong()
        val sign = if (scaled < 0) "-" else ""
        val absoluteScaled = abs(scaled)
        val integral = absoluteScaled / RATE_DECIMAL_SCALE
        val fraction = (absoluteScaled % RATE_DECIMAL_SCALE).toString().padStart(RATE_DECIMAL_DIGITS, '0')
        return "$sign$integral.$fraction"
    }

    private fun formatIntWithGrouping(value: Int): String {
        val digits = value.toString()
        val grouped = StringBuilder(digits.length + digits.length / 3)
        digits.reversed().forEachIndexed { index, char ->
            if (index > 0 && index % 3 == 0) {
                grouped.append(',')
            }
            grouped.append(char)
        }
        return grouped.reverse().toString()
    }

    private fun formatResponseTime(elapsedSeconds: Double): String {
        val scaled = (elapsedSeconds * TIME_DECIMAL_SCALE).roundToLong().coerceAtLeast(0L)
        val seconds = scaled / TIME_DECIMAL_SCALE
        val fraction = (scaled % TIME_DECIMAL_SCALE).toString().padStart(TIME_DECIMAL_DIGITS, '0')
        return "time> $seconds.$fraction s"
    }

    private data class ResolvedFileReference(
        val path: String,
        val content: String,
    )

    private data class PreparedPrompt(
        val displayPrompt: String,
        val requestPrompt: String,
        val inlineReferences: List<String>,
    )

    private data class InlineFileReferenceParseResult(
        val cleanedPrompt: String,
        val references: List<String>,
    )

    private data class ParsedInlineReference(
        val path: String,
        val nextIndex: Int,
    )

    private data class TurnExecutionResult(
        val response: AgentResponse,
        val compacted: Boolean,
        val systemMessages: List<String>,
        val elapsedSeconds: Double = 0.0,
    )

    private data class TurnSideEffects(
        val compacted: Boolean,
        val systemMessages: List<String>,
    )

    private data class WorkflowQuestion(
        val text: String,
        val options: List<String>,
    )

    private data class WorkflowQuestionAnswer(
        val question: String,
        val answer: String,
    )

    private data class WorkflowStepResponse(
        val needsUserInput: Boolean,
        val questions: List<WorkflowQuestion>,
        val answer: String,
    )

    private enum class WorkflowValidationStatus {
        PASS,
        FAIL,
    }

    private data class WorkflowValidationOutcome(
        val status: WorkflowValidationStatus,
        val summary: String,
        val details: String?,
        val feedback: String,
    )

    private sealed interface WorkflowApprovalDecision {
        data object APPROVE : WorkflowApprovalDecision
        data object CANCEL : WorkflowApprovalDecision
        data class COMMENT(val text: String) : WorkflowApprovalDecision
    }

    private enum class WorkflowUiStep(val label: String) {
        USER_INPUT(label = "user input"),
        PLANNING(label = "planning"),
        EXECUTION(label = "execution"),
        VALIDATION(label = "validation"),
    }

    companion object {
        private const val WORKFLOW_APPROVAL_INPUT_PROMPT = "choice/comment> "
        private const val INVARIANT_ADD_NEW_CONSTRAINT_LABEL = "Add new constraint"
        private const val INVARIANT_VALIDATION_MAX_RETRIES = 2
        private val WORKFLOW_PLANNING_APPROVAL_PROMPT = """
            Planning result approval:
            1. Approve
            2. Cancel
            Comment (type feedback)
        """.trimIndent()
        private val WORKFLOW_EXECUTION_APPROVAL_PROMPT = """
            Execution result approval:
            1. Approve
            2. Cancel
            Comment (type feedback to re-plan)
        """.trimIndent()
        private const val TOKENS_PER_MILLION = 1_000_000.0
        private const val PRICE_DECIMAL_SCALE = 1_000_000L
        private const val PRICE_DECIMAL_DIGITS = 6
        private const val RATE_DECIMAL_SCALE = 100L
        private const val RATE_DECIMAL_DIGITS = 2
        private const val TIME_DECIMAL_SCALE = 100L
        private const val TIME_DECIMAL_DIGITS = 2
        private const val PERCENT_DECIMAL_SCALE = 10L
        private const val PERCENT_DECIMAL_DIGITS = 1
        private const val MEMORY_BAR_WIDTH = 20
        private const val REQUEST_OVERHEAD_TOKENS = 3
        private const val MESSAGE_OVERHEAD_TOKENS = 4
        private const val CHARS_PER_TOKEN = 4.0
        private const val THINKING_INDICATOR_UPDATE_INTERVAL_MS = 120L
        private const val THINKING_TENTHS_PER_SECOND = 10L
        private const val THINKING_TENTH_DIVISOR_MS = 100L
        private val THINKING_SPINNER_FRAMES = charArrayOf('|', '/', '-', '\\')
        private val INVARIANT_STRICT_PREFIX_REGEX = Regex("^\\[\\s*strict\\s*\\]\\s*", RegexOption.IGNORE_CASE)
        private val FENCED_CODE_BLOCK_REGEX = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        private val TRAILING_REFERENCE_DELIMITERS = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')
        private val KNOWN_FILE_EXTENSIONS = setOf(
            "kt",
            "kts",
            "md",
            "txt",
            "json",
            "yaml",
            "yml",
            "xml",
            "gradle",
            "properties",
            "java",
            "swift",
            "py",
            "js",
            "ts",
            "tsx",
            "jsx",
            "c",
            "cpp",
            "h",
            "hpp",
            "go",
            "rs",
            "sh",
        )
        private val workflowValidationJson: Json = Json {
            ignoreUnknownKeys = true
        }
        private val workflowStepResponseJson: Json = Json {
            ignoreUnknownKeys = true
        }
        private val mcpCommandJson: Json = Json {
            prettyPrint = true
        }
        private val EMPTY_JSON_OBJECT = buildJsonObject {}
        private const val MCP_TOOL_COMMAND_USAGE = "system> usage: /mcp <server-index> <tool-name> [json-object-args]"
        private val WORKFLOW_STEP_RESPONSE_CONTRACT_PROMPT = """
            Response format contract:
            - Return only valid JSON object with keys:
              {"needs_user_input": boolean, "questions": string[] | object[], "answer": string}
            - If needs_user_input is true:
              - Provide one or more concrete user questions in "questions".
              - Questions may be strings or objects:
                {"question":"...", "options":["...","..."]} (options are optional)
              - Keep "answer" empty.
            - If needs_user_input is false:
              - Use empty "questions".
              - Put the full step result in "answer".
            - If multiple questions are needed, list them in order. The CLI will ask them one by one.
        """.trimIndent()
        private val WORKFLOW_VALIDATION_RESPONSE_CONTRACT_PROMPT = """
            Response format contract:
            - Return only a valid JSON object:
              {"status":"PASS|FAIL","summary":"...","details":"..."}
            - status must be exactly PASS or FAIL.
            - summary must be concise and actionable.
            - details may be empty string if there are no extra details.
            - Do not include markdown, explanations, or code fences.
        """.trimIndent()
    }

    private data class ParsedMcpToolCommand(
        val serverIndex: Int,
        val serverIndexRaw: String,
        val toolName: String,
        val arguments: JsonObject,
    )

    private data class PreparedMainTurnToolContext(
        val capabilities: LlmToolCapabilities = LlmToolCapabilities(),
        val systemMessages: List<String> = emptyList(),
    )
}
