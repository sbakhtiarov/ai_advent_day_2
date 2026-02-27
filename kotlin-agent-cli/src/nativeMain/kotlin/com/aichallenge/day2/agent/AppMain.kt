package com.aichallenge.day2.agent

import com.aichallenge.day2.agent.core.config.AppConfig
import com.aichallenge.day2.agent.core.di.AppContainer
import com.aichallenge.day2.agent.data.local.JsonFileSessionMemoryStore
import com.aichallenge.day2.agent.domain.model.RollingWindowCompactionStartPolicy
import com.aichallenge.day2.agent.domain.usecase.RollingSummaryCompactionStrategy
import com.aichallenge.day2.agent.domain.usecase.SessionMemoryCompactionCoordinator
import com.aichallenge.day2.agent.presentation.cli.ConsoleChatController
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    runBlocking {
        val exitCode = runApp(args)
        exitProcess(exitCode)
    }
}

private suspend fun runApp(args: Array<String>): Int {
    if (args.any { it == "--help" || it == "-h" }) {
        printUsage()
        return 0
    }

    val config = runCatching { AppConfig.fromEnvironment() }
        .getOrElse { error ->
            println("Configuration error: ${error.message}")
            printEnvironmentHelp()
            return 1
        }

    val prompt = parsePromptArgument(args)
    if (prompt != null && prompt.isBlank()) {
        println("error> --prompt requires a non-empty value")
        return 1
    }

    val container = AppContainer(config)
    val sessionMemoryCompactionCoordinator = SessionMemoryCompactionCoordinator(
        startPolicy = RollingWindowCompactionStartPolicy(
            threshold = 12,
            compactCount = 10,
            keepCount = 2,
        ),
        strategy = RollingSummaryCompactionStrategy(
            sendPromptUseCase = container.sendPromptUseCase,
        ),
    )
    val isInteractiveMode = prompt == null
    val sessionMemoryStore = if (isInteractiveMode) {
        JsonFileSessionMemoryStore.fromDefaultLocation()
    } else {
        null
    }
    val controller = ConsoleChatController(
        sendPromptUseCase = container.sendPromptUseCase,
        initialSystemPrompt = config.systemPrompt,
        initialModel = config.model,
        models = config.models,
        sessionMemoryStore = sessionMemoryStore,
        persistentMemoryEnabled = isInteractiveMode,
        sessionMemoryCompactionCoordinator = sessionMemoryCompactionCoordinator,
    )

    return try {
        if (prompt != null) {
            controller.runSinglePrompt(prompt)
        } else {
            controller.runInteractive()
            0
        }
    } finally {
        container.close()
    }
}

private fun parsePromptArgument(args: Array<String>): String? {
    val index = args.indexOf("--prompt")
    if (index == -1) {
        return null
    }
    return args.drop(index + 1).joinToString(separator = " ").trim()
}

private fun printUsage() {
    println(
        """
        agent-cli usage:
          ./agent-cli.kexe                 # interactive mode
          ./agent-cli.kexe --prompt "..."  # one-shot mode
        
        options:
          -h, --help                       show this message
        """.trimIndent(),
    )
}

private fun printEnvironmentHelp() {
    println(
        """
        Required configuration (environment variable or local.properties):
          OPENAI_API_KEY       OpenAI API key
        
        Optional configuration (environment variable or local.properties):
          OPENAI_BASE_URL      default: https://api.openai.com/v1
          AGENT_SYSTEM_PROMPT  default: concise pragmatic assistant prompt
        """.trimIndent(),
    )
}
