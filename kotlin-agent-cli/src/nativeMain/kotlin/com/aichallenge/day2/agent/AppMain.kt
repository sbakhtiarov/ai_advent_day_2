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

    val launchMode = when (val parsed = parseLaunchMode(args)) {
        is LaunchModeParseResult.Error -> {
            println("error> ${parsed.message}")
            return 1
        }

        is LaunchModeParseResult.Success -> {
            if (parsed.mode is LaunchMode.ScheduledJob) {
                return runScheduledJobMode(parsed.mode.scheduleId)
            }
            parsed.mode
        }
    }

    val config = runCatching { AppConfig.fromEnvironment() }
        .getOrElse { error ->
            println("Configuration error: ${error.message}")
            printEnvironmentHelp()
            return 1
        }

    return runConfiguredApp(
        config = config,
        launchMode = launchMode,
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
                launchMode = LaunchMode.Prompt(job.prompt),
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
    launchMode: LaunchMode,
    onSinglePromptSuccess: ((AgentResponse) -> Unit)? = null,
): Int {
    val isInteractiveMode = launchMode == LaunchMode.Interactive
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
        when (launchMode) {
            is LaunchMode.Prompt -> controller.runSinglePrompt(
                prompt = launchMode.prompt,
                onSuccess = onSinglePromptSuccess,
            )

            is LaunchMode.ReviewPr -> controller.runSingleReviewPr(launchMode.prUrl)
            LaunchMode.Interactive -> {
                controller.runInteractive()
                0
            }

            is LaunchMode.ScheduledJob -> error("Scheduled job mode is handled before runConfiguredApp.")
        }
    } finally {
        container.close()
    }
}

internal fun parseLaunchMode(args: Array<String>): LaunchModeParseResult {
    val scheduledJobId = parseFlagValue(args, "--run-scheduled-job")
    if (scheduledJobId != null) {
        return if (scheduledJobId.isBlank()) {
            LaunchModeParseResult.Error("--run-scheduled-job requires a non-empty value")
        } else {
            LaunchModeParseResult.Success(LaunchMode.ScheduledJob(scheduledJobId))
        }
    }

    val prompt = parseFlagValue(args, "--prompt")
    val reviewPrUrl = parseFlagValue(args, "--review-pr")
    if (prompt != null && reviewPrUrl != null) {
        return LaunchModeParseResult.Error("--prompt and --review-pr cannot be used together")
    }
    if (prompt != null) {
        return if (prompt.isBlank()) {
            LaunchModeParseResult.Error("--prompt requires a non-empty value")
        } else {
            LaunchModeParseResult.Success(LaunchMode.Prompt(prompt))
        }
    }
    if (reviewPrUrl != null) {
        return if (reviewPrUrl.isBlank()) {
            LaunchModeParseResult.Error("--review-pr requires a non-empty value")
        } else {
            LaunchModeParseResult.Success(LaunchMode.ReviewPr(reviewPrUrl))
        }
    }

    return LaunchModeParseResult.Success(LaunchMode.Interactive)
}

private fun parseFlagValue(args: Array<String>, flag: String): String? {
    val index = args.indexOf(flag)
    if (index == -1) {
        return null
    }
    return args.slice(index + 1 until args.size)
        .takeWhile { candidate -> candidate !in RESERVED_ARGUMENT_FLAGS }
        .joinToString(separator = " ")
        .trim()
}

private fun printUsage() {
    println(buildUsageText())
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

internal fun buildUsageText(): String {
    return """
        agent-cli usage:
          ./agent-cli.kexe                                  # interactive mode
          ./agent-cli.kexe --prompt "..."                   # one-shot prompt mode
          ./agent-cli.kexe --review-pr <public-pr-url>      # one-shot PR review mode
        
        options:
          -h, --help                                        show this message
        """.trimIndent()
}

internal sealed interface LaunchMode {
    data object Interactive : LaunchMode

    data class Prompt(val prompt: String) : LaunchMode

    data class ReviewPr(val prUrl: String) : LaunchMode

    data class ScheduledJob(val scheduleId: String) : LaunchMode
}

internal sealed interface LaunchModeParseResult {
    data class Success(val mode: LaunchMode) : LaunchModeParseResult

    data class Error(val message: String) : LaunchModeParseResult
}

private val RESERVED_ARGUMENT_FLAGS = setOf(
    "-h",
    "--help",
    "--prompt",
    "--review-pr",
    "--run-scheduled-job",
)
