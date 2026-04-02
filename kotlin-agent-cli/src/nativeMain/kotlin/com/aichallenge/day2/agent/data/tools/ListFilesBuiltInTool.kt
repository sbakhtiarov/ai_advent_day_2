package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.core.config.AppRuntimeEnvironment
import com.aichallenge.day2.agent.core.config.DefaultAppRuntimeEnvironment
import com.aichallenge.day2.agent.domain.model.PrivateToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val LIST_FILES_TOOL_ID = "list_files"
private const val DEFAULT_LIST_FILES_LIMIT = 50
private const val MAX_LIST_FILES_LIMIT = 100

fun listFilesToolRegistration(
    runtimeEnvironment: AppRuntimeEnvironment = DefaultAppRuntimeEnvironment(),
): BuiltInToolRegistration {
    return BuiltInToolRegistration(
        definition = BuiltInToolDefinition(
            toolId = LIST_FILES_TOOL_ID,
            modelToolName = LIST_FILES_TOOL_ID,
            description = "List the immediate children of a workspace directory. Use this tool to inspect folders before reading or editing files.",
            parametersSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "path",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Directory path to list. Omit or use '.' to list the workspace root.")
                            },
                        )
                        put(
                            "offset",
                            buildJsonObject {
                                put("type", "integer")
                                put("minimum", 0)
                                put("description", "Pagination offset. Defaults to 0.")
                            },
                        )
                        put(
                            "limit",
                            buildJsonObject {
                                put("type", "integer")
                                put("minimum", 1)
                                put("maximum", MAX_LIST_FILES_LIMIT)
                                put("description", "Maximum number of entries to return. Defaults to $DEFAULT_LIST_FILES_LIMIT.")
                            },
                        )
                    },
                )
                put("additionalProperties", false)
            },
        ),
        executor = ListFilesBuiltInToolExecutor(runtimeEnvironment),
    )
}

class ListFilesBuiltInToolExecutor(
    runtimeEnvironment: AppRuntimeEnvironment,
) : BuiltInToolExecutor {
    private val fileSupport = WorkspaceFileSupport(runtimeEnvironment)

    override suspend fun execute(arguments: JsonObject): PrivateToolResult {
        val resolvedPath = fileSupport.resolveOptionalPath(
            path = arguments.optionalStringArgument("path"),
            toolName = LIST_FILES_TOOL_ID,
        )
        fileSupport.ensureDirectory(resolvedPath)

        val offset = arguments.optionalIntArgument("offset")?.also { value ->
            require(value >= 0) { "Argument 'offset' must be at least 0." }
        } ?: 0
        val limit = (arguments.optionalIntArgument("limit") ?: DEFAULT_LIST_FILES_LIMIT).also { value ->
            require(value in 1..MAX_LIST_FILES_LIMIT) {
                "Argument 'limit' must be between 1 and $MAX_LIST_FILES_LIMIT."
            }
        }

        val entries = fileSupport.listDirectory(resolvedPath.absolutePath).map { entry ->
            WorkspaceDirectoryEntry(
                name = entry.name,
                path = fileSupport.toWorkspaceRelativePath(resolvedPath.workspaceRoot, entry.path),
                isDirectory = entry.isDirectory,
            )
        }
        val page = fileSupport.paginate(entries, offset = offset, limit = limit)

        return PrivateToolResult(
            isError = false,
            content = textContent(
                buildString {
                    appendLine("Directory: ${resolvedPath.workspaceRelativePath}")
                    if (page.items.isEmpty()) {
                        append("(empty)")
                    } else {
                        page.items.forEach { entry ->
                            append(if (entry.isDirectory) "[dir] " else "[file] ")
                            append(entry.path)
                            appendLine()
                        }
                    }
                }.trimEnd(),
            ),
            structuredContent = buildJsonObject {
                put("path", resolvedPath.workspaceRelativePath)
                put("absolute_path", resolvedPath.absolutePath)
                put("offset", page.offset)
                put("limit", page.limit)
                put("total_count", page.totalCount)
                page.nextOffset?.let { nextOffset -> put("next_offset", nextOffset) }
                put(
                    "entries",
                    buildJsonArray {
                        page.items.forEach { entry ->
                            add(
                                buildJsonObject {
                                    put("name", entry.name)
                                    put("path", entry.path)
                                    put("is_directory", entry.isDirectory)
                                },
                            )
                        }
                    },
                )
            },
        )
    }
}
