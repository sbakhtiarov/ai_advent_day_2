@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.aichallenge.day2.agent

import com.aichallenge.day2.agent.core.config.AppConfig
import com.aichallenge.day2.agent.core.config.ApiSettings
import com.aichallenge.day2.agent.core.config.DefaultAppRuntimeEnvironment
import com.aichallenge.day2.agent.core.config.MutableApiSettingsService
import com.aichallenge.day2.agent.core.config.ProfileEnvironmentFactsProvider
import com.aichallenge.day2.agent.core.di.AppContainer
import com.aichallenge.day2.agent.data.local.JsonFileApiSettingsStore
import com.aichallenge.day2.agent.data.local.JsonFileProfileMemoryStore
import com.aichallenge.day2.agent.data.local.JsonFileSessionMemoryStore
import com.aichallenge.day2.agent.data.local.JsonFileInvariantConstraintStore
import com.aichallenge.day2.agent.data.local.JsonFileMcpServerStore
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
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.getenv
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
    val isInteractiveMode = prompt == null
    val apiSettingsStore = JsonFileApiSettingsStore.fromDefaultLocation()
    val initialApiSettings = resolveInitialApiSettings(
        config = config,
        apiSettingsStore = apiSettingsStore,
    )
    if (!isInteractiveMode && initialApiSettings == null) {
        println("Configuration error: no API is configured.")
        printEnvironmentHelp()
        return 1
    }
    val apiSettingsService = MutableApiSettingsService(initialApiSettings)
    val startupWorkingDirectory = resolveStartupWorkingDirectory()
    val container = AppContainer(
        config = config,
        apiSettingsService = apiSettingsService,
        startupWorkingDirectory = startupWorkingDirectory,
    )
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
        apiSettingsService = apiSettingsService,
        apiSettingsStore = apiSettingsStore,
        availableModels = config.models,
        sessionMemoryStore = sessionMemoryStore,
        workingMemoryStore = workingMemoryStore,
        profileMemoryStore = profileMemoryStore,
        userDefinedProfileStore = userDefinedProfileStore,
        userDefinedWorkflowStore = userDefinedWorkflowStore,
        invariantConstraintStore = invariantConstraintStore,
        mcpServerStore = mcpServerStore,
        mcpRuntimeService = container.mcpRuntimeService,
        builtInPrivateToolProvider = BuiltInPrivateToolProvider(container.builtInToolRegistry),
        wireAppRagRetriever = container.wireAppRagRetriever,
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
        Interactive mode loads APIs from ~/.kotlin-agent-cli/api-settings.json.
        Use /api to select the active API from that file.

        Optional logging configuration (environment variable or local.properties):
          OPENAI_API_LOG_FILE  default: ~/.kotlin-agent-cli/openai-api-traffic.log (blank disables)
          WIRE_APP_RAG_BASE_URL default: http://localhost:8000
        """.trimIndent(),
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun resolveStartupWorkingDirectory(): String? {
    val shellPwd = getenv("PWD")
        ?.toKString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    if (shellPwd != null) {
        return shellPwd
    }

    return DefaultAppRuntimeEnvironment()
        .currentWorkingDirectory()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

private fun resolveInitialApiSettings(
    config: AppConfig,
    apiSettingsStore: JsonFileApiSettingsStore?,
): ApiSettings? {
    val persistedSettings = runCatching { apiSettingsStore?.load() }.getOrNull()
    return persistedSettings?.let { settings ->
        normalizeApiSettingsForCatalog(
            settings = settings,
            config = config,
        )
    }
}

@Suppress("UNUSED_PARAMETER")
internal fun normalizeApiSettingsForCatalog(
    settings: ApiSettings,
    config: AppConfig,
): ApiSettings? {
    return settings.normalizedOrNull()
}
