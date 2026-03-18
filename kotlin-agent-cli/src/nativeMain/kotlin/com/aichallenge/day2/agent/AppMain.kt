@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.aichallenge.day2.agent

import com.aichallenge.day2.agent.core.config.AppConfig
import com.aichallenge.day2.agent.core.config.ProfileEnvironmentFactsProvider
import com.aichallenge.day2.agent.core.di.AppContainer
import com.aichallenge.day2.agent.data.local.JsonFileProfileMemoryStore
import com.aichallenge.day2.agent.data.local.JsonFileSessionMemoryStore
import com.aichallenge.day2.agent.data.local.JsonFileInvariantConstraintStore
import com.aichallenge.day2.agent.data.local.JsonFileMcpServerStore
import com.aichallenge.day2.agent.data.local.JsonFileRagSourceStore
import com.aichallenge.day2.agent.data.local.JsonFileUserDefinedProfileStore
import com.aichallenge.day2.agent.data.local.JsonFileUserDefinedWorkflowStore
import com.aichallenge.day2.agent.data.local.JsonFileWorkingMemoryStore
import com.aichallenge.day2.agent.data.tools.BuiltInPrivateToolProvider
import com.aichallenge.day2.agent.data.tools.LaunchdSchedulerService
import com.aichallenge.day2.agent.data.tools.ScheduledJobRunnerResult
import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.RollingWindowCompactionStartPolicy
import com.aichallenge.day2.agent.domain.model.SessionCompactionMode
import com.aichallenge.day2.agent.domain.model.SlidingWindowCompactionStartPolicy
import com.aichallenge.day2.agent.domain.usecase.FactMapCompactionStrategy
import com.aichallenge.day2.agent.domain.usecase.ProfileMemoryDistillationUseCase
import com.aichallenge.day2.agent.domain.usecase.RollingSummaryCompactionStrategy
import com.aichallenge.day2.agent.domain.usecase.SessionMemoryCompactionCoordinator
import com.aichallenge.day2.agent.domain.usecase.SlidingWindowCompactionStrategy
import com.aichallenge.day2.agent.domain.usecase.WorkingMemoryDistillationUseCase
import com.aichallenge.day2.agent.presentation.cli.ConsoleChatController
import com.aichallenge.day2.agent.presentation.cli.SystemPromptBuilder
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

    val scheduledJobId = parseScheduledJobArgument(args)
    if (scheduledJobId != null) {
        return runScheduledJobMode(scheduledJobId)
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

    return runConfiguredApp(
        config = config,
        prompt = prompt,
    )
}

private suspend fun runScheduledJobMode(scheduleId: String): Int {
    val schedulerService = LaunchdSchedulerService.createDefault()
    return runCatching {
        schedulerService.runScheduledJob(scheduleId) { job ->
            var responseText: String? = null
            val config = runCatching { AppConfig.fromEnvironment() }
                .getOrElse { error ->
                    println("Configuration error: ${error.message}")
                    printEnvironmentHelp()
                    return@runScheduledJob ScheduledJobRunnerResult(exitCode = 1)
                }
            val exitCode = runConfiguredApp(
                config = config,
                prompt = job.prompt,
                onSinglePromptSuccess = { response -> responseText = response.content },
            )
            ScheduledJobRunnerResult(
                exitCode = exitCode,
                assistantResponse = responseText,
            )
        }.exitCode
    }.getOrElse { throwable ->
        println("error> ${throwable.message ?: "Unexpected error"}")
        1
    }
}

private suspend fun runConfiguredApp(
    config: AppConfig,
    prompt: String?,
    onSinglePromptSuccess: ((AgentResponse) -> Unit)? = null,
): Int {
    val container = AppContainer(config)
    val sessionMemoryCompactionCoordinators = mapOf(
        SessionCompactionMode.ROLLING_SUMMARY to SessionMemoryCompactionCoordinator(
            startPolicy = RollingWindowCompactionStartPolicy(
                threshold = 20,
                compactCount = 18,
                keepCount = 2,
            ),
            strategy = RollingSummaryCompactionStrategy(
                sendPromptUseCase = container.sendPromptUseCase,
            ),
        ),
        SessionCompactionMode.SLIDING_WINDOW to SessionMemoryCompactionCoordinator(
            startPolicy = SlidingWindowCompactionStartPolicy(
                maxMessages = 10,
            ),
            strategy = SlidingWindowCompactionStrategy(),
        ),
        SessionCompactionMode.FACT_MAP to SessionMemoryCompactionCoordinator(
            startPolicy = SlidingWindowCompactionStartPolicy(
                maxMessages = 10,
            ),
            strategy = FactMapCompactionStrategy(
                sendPromptUseCase = container.sendPromptUseCase,
            ),
        ),
        SessionCompactionMode.BRANCHING to SessionMemoryCompactionCoordinator.disabled(),
    )
    val isInteractiveMode = prompt == null
    val sessionMemoryStore = if (isInteractiveMode) {
        JsonFileSessionMemoryStore.fromDefaultLocation()
    } else {
        null
    }
    val workingMemoryStore = if (isInteractiveMode) {
        JsonFileWorkingMemoryStore.fromDefaultLocation()
    } else {
        null
    }
    val profileMemoryStore = if (isInteractiveMode) {
        JsonFileProfileMemoryStore.fromDefaultLocation()
    } else {
        null
    }
    val userDefinedProfileStore = JsonFileUserDefinedProfileStore.fromDefaultLocation()
    val userDefinedWorkflowStore = JsonFileUserDefinedWorkflowStore.fromDefaultLocation()
    val invariantConstraintStore = JsonFileInvariantConstraintStore.fromDefaultLocation()
    val mcpServerStore = JsonFileMcpServerStore.fromDefaultLocation()
    val ragSourceStore = JsonFileRagSourceStore.fromDefaultLocation()
    val workingMemoryDistillationUseCase = if (isInteractiveMode) {
        WorkingMemoryDistillationUseCase(container.sendPromptUseCase)
    } else {
        null
    }
    val profileMemoryDistillationUseCase = if (isInteractiveMode) {
        ProfileMemoryDistillationUseCase(container.sendPromptUseCase)
    } else {
        null
    }
    val controller = ConsoleChatController(
        sendPromptUseCase = container.sendPromptUseCase,
        buildPromptUseCase = container.buildPromptUseCase,
        systemPromptBuilder = SystemPromptBuilder(),
        initialSystemPrompt = config.systemPrompt,
        initialModel = config.model,
        models = config.models,
        sessionMemoryStore = sessionMemoryStore,
        workingMemoryStore = workingMemoryStore,
        profileMemoryStore = profileMemoryStore,
        userDefinedProfileStore = userDefinedProfileStore,
        userDefinedWorkflowStore = userDefinedWorkflowStore,
        invariantConstraintStore = invariantConstraintStore,
        mcpServerStore = mcpServerStore,
        ragSourceStore = ragSourceStore,
        mcpRuntimeService = container.mcpRuntimeService,
        builtInPrivateToolProvider = BuiltInPrivateToolProvider(container.builtInToolRegistry),
        workingMemoryDistillationUseCase = workingMemoryDistillationUseCase,
        profileMemoryDistillationUseCase = profileMemoryDistillationUseCase,
        profileEnvironmentFactsProvider = ProfileEnvironmentFactsProvider(),
        persistentMemoryEnabled = isInteractiveMode,
        workingMemoryEnabled = isInteractiveMode,
        profileMemoryEnabled = isInteractiveMode,
        compactionCoordinators = sessionMemoryCompactionCoordinators,
        defaultCompactionMode = SessionCompactionMode.ROLLING_SUMMARY,
    )

    return try {
        if (prompt != null) {
            controller.runSinglePrompt(
                prompt = prompt,
                onSuccess = onSinglePromptSuccess,
            )
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

private fun parseScheduledJobArgument(args: Array<String>): String? {
    val index = args.indexOf("--run-scheduled-job")
    if (index == -1) {
        return null
    }
    return args.getOrNull(index + 1)?.trim()?.takeIf { value -> value.isNotEmpty() }
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
          OPENAI_API_LOG_FILE  default: ~/.kotlin-agent-cli/openai-api-traffic.log (blank disables)
        """.trimIndent(),
    )
}
