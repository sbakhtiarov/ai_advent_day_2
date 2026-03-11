package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.domain.model.PrivateToolBinding
import com.aichallenge.day2.agent.domain.model.PrivateToolResult
import com.aichallenge.day2.agent.domain.model.PrivateToolTarget
import kotlinx.serialization.json.JsonObject

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
        ): BuiltInToolRegistry {
            return BuiltInToolRegistry(
                registrations = listOf(
                    notifyUserToolRegistration(commandExecutor),
                ),
            )
        }
    }
}

class BuiltInPrivateToolProvider(
    private val registry: BuiltInToolRegistry,
) {
    fun loadTools(): List<PrivateToolBinding> = registry.listPrivateToolBindings()
}
