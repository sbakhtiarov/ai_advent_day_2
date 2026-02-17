package com.aichallenge.day2.agent.presentation.cli

import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.usecase.SendPromptUseCase

class ConsoleChatController(
    private val sendPromptUseCase: SendPromptUseCase,
    private var systemPrompt: String,
    private val io: CliIO = StdCliIO,
) {
    private val configTabs = listOf("Format", "Size", "Stop")
    private val configDescriptions = listOf(
        "Select output format",
        "Restrict output size",
        "Define stop sequence instructions",
    )
    private val history = mutableListOf(ConversationMessage.system(systemPrompt))
    private val dialogBlocks = mutableListOf<String>()
    private val inputDivider = "─".repeat(80)

    suspend fun runInteractive() {
        try {
            while (true) {
                renderScreen()

                val input = io.readLineInFooter(prompt = "> ", divider = inputDivider)?.trim() ?: break
                if (input.isEmpty()) {
                    continue
                }

                io.hideCursor()

                if (input.startsWith("/")) {
                    val shouldContinue = handleCommand(input)
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
            val response = sendPromptUseCase.execute(history, prompt)
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
            val response = sendPromptUseCase.execute(history, prompt)
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
                io.openConfigMenu(
                    tabs = configTabs,
                    descriptions = configDescriptions,
                )
                true
            }

            input == "/reset" -> {
                resetConversation()
                dialogBlocks.clear()
                dialogBlocks += "system> conversation has been reset"
                true
            }

            input.startsWith("/system ") -> {
                val newPrompt = input.removePrefix("/system ").trim()
                if (newPrompt.isBlank()) {
                    dialogBlocks += "system> usage: /system <new prompt>"
                } else {
                    systemPrompt = newPrompt
                    resetConversation()
                    dialogBlocks.clear()
                    dialogBlocks += "system> updated system prompt and reset history"
                }
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
        io.writeLine("    commands: /help, /config, /reset, /system <prompt>, /exit")
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
        █████╗  ██████╗ ███████╗███╗   ██╗████████╗       ██████╗██╗     ██╗
       ██╔══██╗██╔════╝ ██╔════╝████╗  ██║╚══██╔══╝      ██╔════╝██║     ██║
       ███████║██║  ███╗█████╗  ██╔██╗ ██║   ██║         ██║     ██║     ██║
       ██╔══██║██║   ██║██╔══╝  ██║╚██╗██║   ██║         ██║     ██║     ██║
       ██║  ██║╚██████╔╝███████╗██║ ╚████║   ██║         ╚██████╗███████╗██║
       ╚═╝  ╚═╝ ╚═════╝ ╚══════╝╚═╝  ╚═══╝   ╚═╝          ╚═════╝╚══════╝╚═╝
    """.trimIndent().lineSequence().joinToString(separator = "\n") { line -> "    $line" }

    private fun helpText(): String = """
        Available commands:
        /help                show this help message
        /config              open config menu (ESC to close)
        /reset               clear conversation and keep current system prompt
        /system <prompt>     replace system prompt and clear conversation
        /exit                close the application
    """.trimIndent()

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
}
