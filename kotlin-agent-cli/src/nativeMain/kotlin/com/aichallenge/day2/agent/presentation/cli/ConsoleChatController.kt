package com.aichallenge.day2.agent.presentation.cli

import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.usecase.SendPromptUseCase

class ConsoleChatController(
    private val sendPromptUseCase: SendPromptUseCase,
    initialSystemPrompt: String,
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
    private var temperature: Double? = null
    private val history = mutableListOf(ConversationMessage.system(systemPrompt))
    private val dialogBlocks = mutableListOf<String>()
    private val inputDivider = "─".repeat(80)

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
            val response = sendPromptUseCase.execute(history, prompt, temperature)
            io.writeLine(formatAssistantResponse(response.content))
            0
        }.getOrElse { throwable ->
            io.writeLine("error> ${throwable.message ?: "Unexpected error"}")
            1
        }
    }

    private suspend fun sendAndStore(prompt: String) {
        dialogBlocks += formatUserPrompt(prompt)

        runCatching {
            val response = sendPromptUseCase.execute(history, prompt, temperature)
            history += ConversationMessage.user(prompt)
            history += ConversationMessage.assistant(response.content)
            dialogBlocks += formatAssistantResponse(response.content)
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
        history.clear()
        history += ConversationMessage.system(systemPrompt)
    }

    private fun renderScreen() {
        io.showCursor()
        io.clearScreen()
        io.writeLine()
        io.writeLine()
        io.writeLine(logoBanner())
        io.writeLine()
        io.writeLine("    type your prompt and press Enter")
        io.writeLine("    commands: /help, /config, /temp <0..2>, /reset, /exit")
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
        /config              open config menu (ESC to close)
        /temp <temperature>  set response temperature (0..2)
        /reset               clear conversation and keep current system prompt
        /exit                close the application
    """.trimIndent()

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

    private fun formatAssistantResponse(text: String): String {
        val marker = "⏺ "
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
}
