package com.aichallenge.day2.agent.presentation.cli

import com.aichallenge.day2.agent.core.config.ModelPricing
import com.aichallenge.day2.agent.domain.model.SessionMemory
import com.aichallenge.day2.agent.domain.model.TokenUsage
import com.aichallenge.day2.agent.domain.usecase.SendPromptUseCase
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.TimeSource

class ConsoleChatController(
    private val sendPromptUseCase: SendPromptUseCase,
    initialSystemPrompt: String,
    initialModel: String,
    availableModels: List<String>,
    private val modelPricing: Map<String, ModelPricing>,
    private val io: CliIO = StdCliIO,
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
    private val availableModelIds = availableModels.distinct().ifEmpty { listOf(initialModel) }
    private var currentModel = initialModel
    private var temperature: Double? = null
    private val sessionMemory = SessionMemory(systemPrompt)
    private val dialogBlocks = mutableListOf<String>()
    private val inputDivider = "─".repeat(80)

    init {
        require(currentModel in availableModelIds) {
            "Initial model must be present in available models."
        }
    }

    suspend fun runInteractive() {
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
                dialogBlocks += "system> configuration applied"
                true
            }

            input == "/reset" -> {
                resetConversation()
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
    }

    private fun renderScreen() {
        io.showCursor()
        io.clearScreen()
        io.writeLine()
        io.writeLine()
        io.writeLine(logoBanner())
        io.writeLine()
        io.writeLine("    type your prompt and press Enter")
        io.writeLine("    commands: /help, /models, /model <id|number>, /config, /temp <0..2>, /reset, /exit")
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
        /models              list configured models
        /model <id|number>   switch active model (must be from /models)
        /config              open config menu (ESC to close)
        /temp <temperature>  set response temperature (0..2)
        /reset               clear conversation and keep current system prompt
        /exit                close the application
    """.trimIndent()

    private fun modelsText(): String = buildString {
        appendLine("Available models:")
        availableModelIds.forEachIndexed { index, modelId ->
            val marker = if (modelId == currentModel) " * " else "   "
            appendLine("$marker${index + 1}. $modelId")
        }
    }.trimEnd()

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
            dialogBlocks += "system> unknown model '$requestedModelArg'. Run /models to view configured models."
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
        val modelRate = modelPricing[currentModel]
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
        private const val TIME_DECIMAL_SCALE = 100L
        private const val TIME_DECIMAL_DIGITS = 2
    }
}
