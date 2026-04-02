package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.core.config.AppRuntimeEnvironment
import com.aichallenge.day2.agent.core.config.DefaultAppRuntimeEnvironment
import com.aichallenge.day2.agent.domain.model.PrivateToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val DELETE_FILE_TOOL_ID = "delete_file"

fun deleteFileToolRegistration(
    runtimeEnvironment: AppRuntimeEnvironment = DefaultAppRuntimeEnvironment(),
): BuiltInToolRegistration {
    return BuiltInToolRegistration(
        definition = BuiltInToolDefinition(
            toolId = DELETE_FILE_TOOL_ID,
            modelToolName = DELETE_FILE_TOOL_ID,
            description = "Delete a workspace file. Missing files are treated as a successful no-op; directories are rejected.",
            parametersSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "path",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "File path to delete.")
                            },
                        )
                    },
                )
                put(
                    "required",
                    buildJsonArray {
                        add(JsonPrimitive("path"))
                    },
                )
                put("additionalProperties", false)
            },
        ),
        executor = DeleteFileBuiltInToolExecutor(runtimeEnvironment),
    )
}

class DeleteFileBuiltInToolExecutor(
    runtimeEnvironment: AppRuntimeEnvironment,
) : BuiltInToolExecutor {
    private val fileSupport = WorkspaceFileSupport(runtimeEnvironment)

    override suspend fun execute(arguments: JsonObject): PrivateToolResult {
        val resolvedPath = fileSupport.resolvePath(
            path = arguments.requireNonBlankStringArgument("path"),
            toolName = DELETE_FILE_TOOL_ID,
        )
        if (fileSupport.directoryExists(resolvedPath.absolutePath)) {
            throw IllegalArgumentException("File '${resolvedPath.absolutePath}' is a directory.")
        }

        val deleted = fileSupport.deleteFile(resolvedPath.absolutePath)
        return PrivateToolResult(
            isError = false,
            content = textContent(
                if (deleted) {
                    "Deleted '${resolvedPath.absolutePath}'."
                } else {
                    "File '${resolvedPath.absolutePath}' did not exist."
                },
            ),
            structuredContent = buildJsonObject {
                put("path", resolvedPath.workspaceRelativePath)
                put("absolute_path", resolvedPath.absolutePath)
                put("deleted", deleted)
            },
        )
    }
}
