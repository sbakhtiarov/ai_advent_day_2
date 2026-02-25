package com.aichallenge.day2.agent.presentation.cli

import com.aichallenge.day2.agent.core.config.ModelProperties
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.MemoryEstimateSource
import com.aichallenge.day2.agent.domain.model.MemoryUsageSnapshot
import com.aichallenge.day2.agent.domain.model.SessionMemory
import com.aichallenge.day2.agent.domain.model.SessionMemoryState
import com.aichallenge.day2.agent.domain.model.TokenUsage
import com.aichallenge.day2.agent.domain.repository.SessionMemoryStore
import com.aichallenge.day2.agent.domain.usecase.SendPromptUseCase
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
    private var memoryUsageSnapshot = estimateHeuristicUsage(sessionMemory.snapshot())
    private val dialogBlocks = mutableListOf<String>()
    private val inputDivider = "─".repeat(80)
    private var persistentMemoryInitialized = false

    init {
        require(currentModel in availableModelIds) {
            "Initial model must be present in available models."
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
        dialogBlocks += formatUserPrompt(prompt)

        runCatching {
            val startedAt = TimeSource.Monotonic.markNow()
            val response = sendPromptUseCase.execute(
                conversation = sessionMemory.conversationFor(prompt),
                temperature = temperature,
                model = currentModel,
            )
            val elapsedSeconds = startedAt.elapsedNow().inWholeMilliseconds / 1000.0
            sessionMemory.recordSuccessfulTurn(prompt, response.content)
            memoryUsageSnapshot = buildUsageSnapshotAfterSuccessfulTurn(
                responseContent = response.content,
                usage = response.usage,
                messages = sessionMemory.snapshot(),
            )
            persistMemorySnapshot()
            dialogBlocks += formatAssistantResponse(response.content, response.usage, elapsedSeconds)
        }.onFailure { throwable ->
            dialogBlocks += "error> ${throwable.message ?: "Unexpected error"}"
        }
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
                persistMemorySnapshot()
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
        memoryUsageSnapshot = estimateHeuristicUsage(sessionMemory.snapshot())
    }

    private fun initializePersistentMemory() {
        if (!persistentMemoryEnabled || persistentMemoryInitialized) {
            return
        }

        persistentMemoryInitialized = true
        val persistedState = runCatching { sessionMemoryStore?.load() }.getOrNull() ?: return
        val restored = sessionMemory.restore(persistedState.messages)
        memoryUsageSnapshot = if (!restored) {
            estimateHeuristicUsage(sessionMemory.snapshot())
        } else {
            persistedState.usage?.takeIf { usage ->
                usage.estimatedTokens > 0 && usage.messageCount == persistedState.messages.size
            } ?: estimateHeuristicUsage(sessionMemory.snapshot())
        }
    }

    private fun persistMemorySnapshot() {
        if (!persistentMemoryEnabled) {
            return
        }

        val state = SessionMemoryState(
            messages = sessionMemory.snapshot(),
            usage = memoryUsageSnapshot,
        )
        runCatching {
            sessionMemoryStore?.save(state)
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
        io.writeLine("    commands: /help, /models, /model <id|number>, /memory, /config, /temp <0..2>, /reset, /exit")
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
        /config              open config menu (ESC to close)
        /temp <temperature>  set response temperature (0..2)
        /reset               clear conversation and keep current system prompt
        /exit                close the application
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

        val estimatedTokens = (usage.inputTokens + estimateMessageTokens(responseContent)).coerceAtLeast(1)
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
    }
}
