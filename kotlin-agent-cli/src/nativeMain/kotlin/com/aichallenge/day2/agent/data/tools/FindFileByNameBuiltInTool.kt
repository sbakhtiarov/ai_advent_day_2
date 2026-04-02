package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.core.config.AppRuntimeEnvironment
import com.aichallenge.day2.agent.core.config.DefaultAppRuntimeEnvironment
import com.aichallenge.day2.agent.domain.model.PrivateToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val FIND_FILE_BY_NAME_TOOL_ID = "find_file_by_name"
private const val DEFAULT_FIND_FILE_LIMIT = 50
private const val MAX_FIND_FILE_LIMIT = 100

fun findFileByNameToolRegistration(
    runtimeEnvironment: AppRuntimeEnvironment = DefaultAppRuntimeEnvironment(),
): BuiltInToolRegistration {
    return BuiltInToolRegistration(
        definition = BuiltInToolDefinition(
            toolId = FIND_FILE_BY_NAME_TOOL_ID,
            modelToolName = FIND_FILE_BY_NAME_TOOL_ID,
            description = "Recursively search workspace files by basename substring.",
            parametersSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "query",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Filename substring to search for.")
                            },
                        )
                        put(
                            "path",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Directory root for the recursive search. Omit or use '.' to search from the workspace root.")
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
                                put("maximum", MAX_FIND_FILE_LIMIT)
                                put("description", "Maximum number of matches to return. Defaults to $DEFAULT_FIND_FILE_LIMIT.")
                            },
                        )
                        put(
                            "case_sensitive",
                            buildJsonObject {
                                put("type", "boolean")
                                put("description", "When true, match filename case exactly. Defaults to false.")
                            },
                        )
                    },
                )
                put(
                    "required",
                    buildJsonArray {
                        add(JsonPrimitive("query"))
                    },
                )
                put("additionalProperties", false)
            },
        ),
        executor = FindFileByNameBuiltInToolExecutor(runtimeEnvironment),
    )
}

class FindFileByNameBuiltInToolExecutor(
    runtimeEnvironment: AppRuntimeEnvironment,
) : BuiltInToolExecutor {
    private val fileSupport = WorkspaceFileSupport(runtimeEnvironment)

    override suspend fun execute(arguments: JsonObject): PrivateToolResult {
        val query = arguments.requireNonBlankStringArgument("query")
        val resolvedPath = fileSupport.resolveOptionalPath(
            path = arguments.optionalStringArgument("path"),
            toolName = FIND_FILE_BY_NAME_TOOL_ID,
        )
        fileSupport.ensureDirectory(resolvedPath)

        val offset = (arguments.optionalIntArgument("offset") ?: 0).also { value ->
            require(value >= 0) { "Argument 'offset' must be at least 0." }
        }
        val limit = (arguments.optionalIntArgument("limit") ?: DEFAULT_FIND_FILE_LIMIT).also { value ->
            require(value in 1..MAX_FIND_FILE_LIMIT) {
                "Argument 'limit' must be between 1 and $MAX_FIND_FILE_LIMIT."
            }
        }
        val caseSensitive = arguments.optionalBooleanArgument("case_sensitive") ?: false

        val normalizedQuery = if (caseSensitive) query else query.lowercase()
        val matches = fileSupport.walkFiles(resolvedPath.absolutePath).map { absolutePath ->
            fileSupport.toWorkspaceRelativePath(resolvedPath.workspaceRoot, absolutePath)
        }.filter { relativePath ->
            val fileName = relativePath.substringAfterLast('/')
            val haystack = if (caseSensitive) fileName else fileName.lowercase()
            haystack.contains(normalizedQuery)
        }

        val page = fileSupport.paginate(matches, offset = offset, limit = limit)
        return PrivateToolResult(
            isError = false,
            content = textContent(
                buildString {
                    appendLine("Filename matches for \"$query\":")
                    if (page.items.isEmpty()) {
                        append("(no matches)")
                    } else {
                        page.items.forEach { match ->
                            appendLine(match)
                        }
                    }
                }.trimEnd(),
            ),
            structuredContent = buildJsonObject {
                put("query", query)
                put("path", resolvedPath.workspaceRelativePath)
                put("offset", page.offset)
                put("limit", page.limit)
                put("total_count", page.totalCount)
                page.nextOffset?.let { nextOffset -> put("next_offset", nextOffset) }
                put(
                    "matches",
                    buildJsonArray {
                        page.items.forEach { match ->
                            add(JsonPrimitive(match))
                        }
                    },
                )
            },
        )
    }
}
