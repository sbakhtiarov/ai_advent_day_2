@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.core.config.AppRuntimeEnvironment
import com.aichallenge.day2.agent.core.config.DefaultAppRuntimeEnvironment
import com.aichallenge.day2.agent.domain.model.PrivateToolBinding
import com.aichallenge.day2.agent.domain.model.PrivateToolResult
import com.aichallenge.day2.agent.domain.model.PrivateToolTarget
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json

data class BuiltInToolDefinition(
    val toolId: String,
    val modelToolName: String,
    val description: String,
    val parametersSchema: JsonObject,
)

fun interface BuiltInToolExecutor {
    suspend fun execute(arguments: JsonObject): PrivateToolResult
}

data class BuiltInToolRegistration(
    val definition: BuiltInToolDefinition,
    val executor: BuiltInToolExecutor,
)

class BuiltInToolRegistry(
    registrations: List<BuiltInToolRegistration>,
) {
    private val registrationsById = registrations.associateBy { registration -> registration.definition.toolId }
    private val registrationsByModelToolName = registrations.associateBy { registration -> registration.definition.modelToolName }

    init {
        require(registrationsById.size == registrations.size) {
            "Built-in tool ids must be unique."
        }
        require(registrationsByModelToolName.size == registrations.size) {
            "Built-in model tool names must be unique."
        }
    }

    fun listPrivateToolBindings(): List<PrivateToolBinding> {
        return registrationsById.values.map { registration ->
            PrivateToolBinding(
                modelToolName = registration.definition.modelToolName,
                target = PrivateToolTarget.BuiltIn(toolId = registration.definition.toolId),
                description = registration.definition.description,
                parametersSchema = registration.definition.parametersSchema,
            )
        }
    }

    suspend fun execute(toolId: String, arguments: JsonObject): PrivateToolResult {
        val registration = registrationsById[toolId]
            ?: error("Unknown built-in tool '$toolId'.")
        return registration.executor.execute(arguments)
    }

    companion object {
        fun createDefault(
            commandExecutor: CommandExecutor = PosixCommandExecutor(),
            runtimeEnvironment: AppRuntimeEnvironment = DefaultAppRuntimeEnvironment(),
        ): BuiltInToolRegistry {
            return createDefault(
                httpClient = HttpClient(Curl),
                json = Json {
                    ignoreUnknownKeys = true
                },
                commandExecutor = commandExecutor,
                runtimeEnvironment = runtimeEnvironment,
            )
        }

        fun createDefault(
            httpClient: HttpClient,
            json: Json,
            commandExecutor: CommandExecutor = PosixCommandExecutor(),
            runtimeEnvironment: AppRuntimeEnvironment = DefaultAppRuntimeEnvironment(),
        ): BuiltInToolRegistry {
            val notificationService = MacOsNotificationService(commandExecutor)
            val schedulerService = LaunchdSchedulerService.createDefault(
                commandExecutor = commandExecutor,
                notificationService = notificationService,
                runtimeEnvironment = runtimeEnvironment,
            )
            return BuiltInToolRegistry(
                registrations = listOf(
                    notifyUserToolRegistration(notificationService),
                    schedulerToolRegistration(schedulerService),
                    createFileToolRegistration(runtimeEnvironment),
                    listFilesToolRegistration(runtimeEnvironment),
                    readFileToolRegistration(runtimeEnvironment),
                    findFileByNameToolRegistration(runtimeEnvironment),
                    searchFileContentToolRegistration(runtimeEnvironment),
                    editFileToolRegistration(runtimeEnvironment),
                    deleteFileToolRegistration(runtimeEnvironment),
                    diffFilesToolRegistration(),
                    convertToPdfToolRegistration(
                        commandExecutor = commandExecutor,
                        runtimeEnvironment = runtimeEnvironment,
                    ),
                    fetchGithubPullRequestToolRegistration(
                        httpClient = httpClient,
                        json = json,
                    ),
                ),
            )
        }
    }
}

class BuiltInPrivateToolProvider(
    private val registry: BuiltInToolRegistry,
) {
    fun loadTools(): List<PrivateToolBinding> = registry.listPrivateToolBindings()

    suspend fun execute(toolId: String, arguments: JsonObject): PrivateToolResult {
        return registry.execute(toolId, arguments)
    }
}
