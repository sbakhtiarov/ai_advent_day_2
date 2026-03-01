package com.aichallenge.day2.agent.presentation.cli

import com.aichallenge.day2.agent.core.config.ModelProperties
import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.BranchingSessionMemory
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.MemoryEstimateSource
import com.aichallenge.day2.agent.domain.model.MemoryUsageSnapshot
import com.aichallenge.day2.agent.domain.model.SessionCompactionMode
import com.aichallenge.day2.agent.domain.model.SessionMemory
import com.aichallenge.day2.agent.domain.model.SessionMemoryState
import com.aichallenge.day2.agent.domain.model.TokenUsage
import com.aichallenge.day2.agent.domain.repository.SessionMemoryStore
import com.aichallenge.day2.agent.domain.usecase.BranchClassificationUseCase
import com.aichallenge.day2.agent.domain.usecase.SessionMemoryCompactionCoordinator
import com.aichallenge.day2.agent.domain.usecase.RollingSummaryCompactionStrategy
import com.aichallenge.day2.agent.domain.usecase.SendPromptUseCase
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.math.roundToInt
import kotlin.time.TimeSource

class ConsoleChatController(
    private val sendPromptUseCase: SendPromptUseCase,
    initialSystemPrompt: String,
    initialModel: String,
    models: List<ModelProperties>,
    private val io: CliIO = StdCliIO,
    private val sessionMemoryStore: SessionMemoryStore? = null,
    private val persistentMemoryEnabled: Boolean = true,
    private val fileReferenceReader: FileReferenceReader = PosixFileReferenceReader,
    private val compactionCoordinators: Map<SessionCompactionMode, SessionMemoryCompactionCoordinator> = mapOf(
        SessionCompactionMode.ROLLING_SUMMARY to SessionMemoryCompactionCoordinator.disabled(),
    ),
    private val defaultCompactionMode: SessionCompactionMode = SessionCompactionMode.ROLLING_SUMMARY,
) {
    private val configTabs = listOf("Format", "Size", "Stop")
    private val configDescriptions = listOf(
        "Select output format",
        "Set max output tokens",
        "Define stop sequence instructions",
    )
    private var baseSystemPrompt = initialSystemPrompt
    private var configSelection = ConfigMenuSelection.default()
    private var systemPrompt = buildSystemPrompt(baseSystemPrompt, configSelection)
    private val availableModelIds = models.map { it.id }.distinct().ifEmpty { listOf(initialModel) }
    private val modelById = models.associateBy { it.id }
    private var currentModel = initialModel
    private var temperature: Double? = null
    private val sessionMemory = SessionMemory(systemPrompt)
    private val branchingSessionMemory = BranchingSessionMemory()
    private val branchClassificationUseCase = BranchClassificationUseCase(sendPromptUseCase)
    private val branchingTopicSummaryStrategy = RollingSummaryCompactionStrategy(sendPromptUseCase)
    private var memoryUsageSnapshot = estimateHeuristicUsage(sessionMemory.contextSnapshot())
    private val dialogBlocks = mutableListOf<String>()
    private val pendingFileReferences = mutableListOf<String>()
    private val inputDivider = "─".repeat(80)
    private var persistentMemoryInitialized = false
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
        initializePersistentMemory()

        try {
            while (true) {
                renderScreen()

                val input = io.readLineInFooter(
                    prompt = "> ",
                    divider = inputDivider,
                    systemPromptText = systemPrompt,
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

        return runCatching {
            val startedAt = TimeSource.Monotonic.markNow()
            val response = sendPromptUseCase.execute(
                conversation = sessionMemory.conversationFor(prompt),
                temperature = temperature,
                model = currentModel,
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

        io.showThinkingIndicator()
        val startedAt = TimeSource.Monotonic.markNow()
        io.updateThinkingIndicator(
            progressText = formatThinkingProgress(
                spinnerFrame = THINKING_SPINNER_FRAMES.first(),
                elapsedMillis = 0L,
            ),
        )
        try {
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
                    runCatching {
                        val turnResult = if (activeCompactionMode == SessionCompactionMode.BRANCHING) {
                            executeBranchingTurn(preparedPrompt.requestPrompt)
                        } else {
                            executeLinearTurn(preparedPrompt.requestPrompt)
                        }
                        val elapsedSeconds = startedAt.elapsedNow().inWholeMilliseconds / 1000.0
                        persistMemorySnapshot()
                        pendingFileReferences.clear()
                        dialogBlocks += formatAssistantResponse(turnResult.response.content, turnResult.response.usage, elapsedSeconds)
                        if (turnResult.compacted) {
                            dialogBlocks += "system> session memory compacted"
                        }
                        turnResult.systemMessages.forEach { systemMessage ->
                            dialogBlocks += systemMessage
                        }
                    }.onFailure { throwable ->
                        dialogBlocks += "error> ${throwable.message ?: "Unexpected error"}"
                    }
                } finally {
                    progressJob.cancelAndJoin()
                }
            }
        } finally {
            io.hideThinkingIndicator()
        }
    }

    private suspend fun executeLinearTurn(requestPrompt: String): TurnExecutionResult {
        val response = sendPromptUseCase.execute(
            conversation = sessionMemory.conversationFor(requestPrompt),
            temperature = temperature,
            model = currentModel,
        )
        sessionMemory.recordSuccessfulTurn(requestPrompt, response.content)
        val compacted = activeCompactionCoordinator()
            ?.compactIfNeeded(
                sessionMemory = sessionMemory,
                model = currentModel,
            )
            ?: false
        memoryUsageSnapshot = buildUsageSnapshotAfterSuccessfulTurn(
            responseContent = response.content,
            usage = response.usage,
            messages = sessionMemory.contextSnapshot(),
        )

        return TurnExecutionResult(
            response = response,
            compacted = compacted,
            systemMessages = emptyList(),
        )
    }

    private suspend fun executeBranchingTurn(requestPrompt: String): TurnExecutionResult {
        val contextWindow = modelById[currentModel]?.contextWindowTokens
        val branchingConversation = branchingSessionMemory.conversationFor(
            prompt = requestPrompt,
            systemPrompt = systemPrompt,
            maxEstimatedTokens = contextWindow,
            estimateTokens = { messages -> estimateSessionTokensHeuristically(messages) },
        )
        val response = sendPromptUseCase.execute(
            conversation = branchingConversation.conversation,
            temperature = temperature,
            model = currentModel,
        )
        val systemMessages = handleBranchingPostResponse(
            prompt = requestPrompt,
            response = response.content,
        )
        memoryUsageSnapshot = buildUsageSnapshotAfterSuccessfulTurn(
            responseContent = response.content,
            usage = response.usage,
            messages = branchingSessionMemory.activeContextSnapshot(systemPrompt),
        )

        return TurnExecutionResult(
            response = response,
            compacted = false,
            systemMessages = systemMessages,
        )
    }

    private suspend fun handleBranchingPostResponse(
        prompt: String,
        response: String,
    ): List<String> {
        val fallbackBranch = branchingSessionMemory.activeBranch()
        val classification = branchClassificationUseCase.classify(
            existingTopics = branchingSessionMemory.topicCatalog(),
            userPrompt = prompt,
            assistantResponse = response,
            fallbackTopic = fallbackBranch.topic,
            fallbackSubtopic = fallbackBranch.subtopic,
            model = currentModel,
        )
        val activation = branchingSessionMemory.resolveAndActivate(
            topicName = classification.topic,
            subtopicName = classification.subtopic,
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
            messages += "system> branch classification failed twice; using current topic/subtopic"
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

    private fun handleCommand(input: String): Boolean {
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

            isModelCommand(input) -> {
                handleModelCommand(input)
                true
            }

            input == "/config" -> {
                configSelection = io.openConfigMenu(
                    tabs = configTabs,
                    descriptions = configDescriptions,
                    currentSelection = configSelection,
                )
                systemPrompt = buildSystemPrompt(baseSystemPrompt, configSelection)
                resetConversation()
                persistMemorySnapshot()
                dialogBlocks += "system> configuration applied"
                true
            }

            input == "/reset" -> {
                resetConversation()
                clearPersistedMemorySnapshot()
                dialogBlocks.clear()
                dialogBlocks += "system> conversation has been reset"
                true
            }

            input.startsWith("/temp") -> {
                handleTemperatureCommand(input)
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
        sessionMemory.reset(systemPrompt)
        branchingSessionMemory.reset()
        memoryUsageSnapshot = estimateHeuristicUsage(activeContextMessages())
        pendingFileReferences.clear()
    }

    private fun initializePersistentMemory() {
        if (!persistentMemoryEnabled || persistentMemoryInitialized) {
            return
        }

        persistentMemoryInitialized = true
        val persistedState = runCatching { sessionMemoryStore?.load() }.getOrNull() ?: return
        val restoredLinear = sessionMemory.restore(
            persistedMessages = persistedState.messages,
            persistedCompactedSummary = persistedState.compactedSummary,
        )
        val restoredBranching = branchingSessionMemory.restore(persistedState.branchingState)
        val persistedMode = SessionCompactionMode.fromIdOrNull(persistedState.activeCompactionModeId)
            ?.takeIf { mode -> mode in availableCompactionModes }
        activeCompactionMode = when {
            persistedMode == SessionCompactionMode.BRANCHING && restoredBranching -> SessionCompactionMode.BRANCHING
            persistedMode != null && persistedMode != SessionCompactionMode.BRANCHING && restoredLinear -> persistedMode
            else -> defaultCompactionMode
        }

        if (activeCompactionMode == SessionCompactionMode.BRANCHING) {
            sessionMemory.reset(systemPrompt)
            if (!restoredBranching) {
                branchingSessionMemory.reset()
            }
        } else {
            branchingSessionMemory.reset()
            if (!restoredLinear) {
                sessionMemory.reset(systemPrompt)
            }
        }

        val activeContext = activeContextMessages()
        memoryUsageSnapshot = persistedState.usage?.takeIf { usage ->
            usage.estimatedTokens > 0 && usage.messageCount == activeContext.size
        } ?: estimateHeuristicUsage(activeContext)
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
        io.writeLine("    commands: /help, /models, /model <id|number>, /memory, /compact, /config, /temp <0..2>, /reset, /exit, @<path>")
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
        /config              open config menu (ESC to close)
        /temp <temperature>  set response temperature (0..2)
        /reset               clear conversation and keep current system prompt
        /exit                close the application
        @<path>              attach file for the next prompt
    """.trimIndent()

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

    private fun activeCompactionCoordinator(): SessionMemoryCompactionCoordinator? {
        return compactionCoordinators[activeCompactionMode]
    }

    private fun activeContextMessages(): List<ConversationMessage> {
        return if (activeCompactionMode == SessionCompactionMode.BRANCHING) {
            branchingSessionMemory.activeContextSnapshot(systemPrompt)
        } else {
            sessionMemory.contextSnapshot()
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

    private fun handleTemperatureCommand(input: String) {
        val parts = input.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size != 2 || parts[1].isBlank()) {
            dialogBlocks += "system> usage: /temp <temperature>, where temperature is between 0 and 2"
            return
        }

        val parsedTemperature = parts[1].toDoubleOrNull()
        if (parsedTemperature == null) {
            dialogBlocks += "system> invalid temperature. Please enter a numeric value between 0 and 2"
            return
        }

        if (parsedTemperature !in 0.0..2.0) {
            dialogBlocks += "system> invalid temperature. Allowed range is 0..2"
            return
        }

        temperature = parsedTemperature
        dialogBlocks += "system> temperature set to ${formatTemperature(parsedTemperature)}"
    }

    private fun formatTemperature(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            value.toString()
        }
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

    private fun buildSystemPrompt(
        basePrompt: String,
        selection: ConfigMenuSelection,
    ): String {
        val stopInstruction = selection.stopSequence.takeIf { it.isNotBlank() }?.let { stopText ->
            """When user sends "$stopText" stop generating questions and provide short summary"""
        } ?: "No explicit stop sequence behavior."

        return """
            $basePrompt
            
            Output rules:
            - Format: ${selection.format.readableName()}
            - Max output tokens: ${selection.maxOutputTokens?.toString() ?: "(none)"}
            - Stop sequence: ${selection.stopSequence.ifBlank { "(none)" }}
            - Stop behavior: $stopInstruction
            - Follow output rules exactly.
        """.trimIndent()
    }

    private fun OutputFormatOption.readableName(): String = when (this) {
        OutputFormatOption.PLAIN_TEXT -> "Plain text"
        OutputFormatOption.MARKDOWN -> "Markdown"
        OutputFormatOption.JSON -> "JSON"
        OutputFormatOption.TABLE -> "Table"
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
    )

    companion object {
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
    }
}
