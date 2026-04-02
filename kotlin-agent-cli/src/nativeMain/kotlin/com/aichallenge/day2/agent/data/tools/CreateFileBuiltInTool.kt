package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.core.config.AppRuntimeEnvironment
import com.aichallenge.day2.agent.core.config.DefaultAppRuntimeEnvironment
import com.aichallenge.day2.agent.domain.model.PrivateToolResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val CREATE_FILE_TOOL_ID = "create_file"

fun createFileToolRegistration(
    runtimeEnvironment: AppRuntimeEnvironment = DefaultAppRuntimeEnvironment(),
): BuiltInToolRegistration {
    return BuiltInToolRegistration(
        definition = BuiltInToolDefinition(
            toolId = CREATE_FILE_TOOL_ID,
            modelToolName = CREATE_FILE_TOOL_ID,
            description = "Create a text file inside the current workspace. Use this tool when the user asks to create or save file content to disk.",
            parametersSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "path",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Target file path. Relative paths are resolved from the current workspace root; absolute paths must still be inside the workspace.")
                            },
                        )
                        put(
                            "content",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "UTF-8 text content to write to the file. Empty string is allowed.")
                            },
                        )
                        put(
                            "overwrite",
                            buildJsonObject {
                                put("type", "boolean")
                                put("description", "When true, replace the file if it already exists. Defaults to false.")
                            },
                        )
                    },
                )
                put(
                    "required",
                    buildJsonArray {
                        add(JsonPrimitive("path"))
                        add(JsonPrimitive("content"))
                    },
                )
                put("additionalProperties", false)
            },
        ),
        executor = CreateFileBuiltInToolExecutor(runtimeEnvironment),
    )
}

class CreateFileBuiltInToolExecutor(
    runtimeEnvironment: AppRuntimeEnvironment,
) : BuiltInToolExecutor {
    private val fileSupport = WorkspaceFileSupport(runtimeEnvironment)

    override suspend fun execute(arguments: kotlinx.serialization.json.JsonObject): PrivateToolResult {
        val path = arguments.requireNonBlankStringArgument("path")
        val content = arguments.requireStringArgument("content")
        val overwrite = arguments.optionalBooleanArgument("overwrite") ?: false

        val resolvedPath = fileSupport.resolvePath(path = path, toolName = CREATE_FILE_TOOL_ID)
        val existed = fileSupport.pathExists(resolvedPath.absolutePath)
        if (fileSupport.directoryExists(resolvedPath.absolutePath)) {
            throw IllegalArgumentException("File '${resolvedPath.absolutePath}' is a directory.")
        }
        if (existed && !overwrite) {
            throw IllegalArgumentException(
                "File '${resolvedPath.absolutePath}' already exists. Set overwrite=true to replace it.",
            )
        }

        fileSupport.ensureParentDirectoryExists(resolvedPath.absolutePath)
        fileSupport.writeUtf8TextFile(resolvedPath.absolutePath, content)

        return PrivateToolResult(
            isError = false,
            content = textContent(
                if (existed) {
                    "Overwrote '${resolvedPath.absolutePath}'."
                } else {
                    "Created '${resolvedPath.absolutePath}'."
                },
            ),
            structuredContent = buildJsonObject {
                put("path", resolvedPath.workspaceRelativePath)
                put("absolute_path", resolvedPath.absolutePath)
                put("overwritten", existed)
                put("bytes_written", content.encodeToByteArray().size)
                put("workspace_root", resolvedPath.workspaceRoot)
            },
        )
    }
}
