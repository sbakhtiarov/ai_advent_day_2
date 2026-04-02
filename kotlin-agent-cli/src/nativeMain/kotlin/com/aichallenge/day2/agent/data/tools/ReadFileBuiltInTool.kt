package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.core.config.AppRuntimeEnvironment
import com.aichallenge.day2.agent.core.config.DefaultAppRuntimeEnvironment
import com.aichallenge.day2.agent.domain.model.PrivateToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val READ_FILE_TOOL_ID = "read_file"
private const val DEFAULT_READ_MAX_LINES = 200
private const val MAX_READ_MAX_LINES = 400

fun readFileToolRegistration(
    runtimeEnvironment: AppRuntimeEnvironment = DefaultAppRuntimeEnvironment(),
): BuiltInToolRegistration {
    return BuiltInToolRegistration(
        definition = BuiltInToolDefinition(
            toolId = READ_FILE_TOOL_ID,
            modelToolName = READ_FILE_TOOL_ID,
            description = "Read UTF-8 text file contents from the workspace with line-based pagination.",
            parametersSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "path",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "File path to read.")
                            },
                        )
                        put(
                            "start_line",
                            buildJsonObject {
                                put("type", "integer")
                                put("minimum", 1)
                                put("description", "1-based line number to start from. Defaults to 1.")
                            },
                        )
                        put(
                            "max_lines",
                            buildJsonObject {
                                put("type", "integer")
                                put("minimum", 1)
                                put("maximum", MAX_READ_MAX_LINES)
                                put("description", "Maximum number of lines to return. Defaults to $DEFAULT_READ_MAX_LINES.")
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
        executor = ReadFileBuiltInToolExecutor(runtimeEnvironment),
    )
}

class ReadFileBuiltInToolExecutor(
    runtimeEnvironment: AppRuntimeEnvironment,
) : BuiltInToolExecutor {
    private val fileSupport = WorkspaceFileSupport(runtimeEnvironment)

    override suspend fun execute(arguments: JsonObject): PrivateToolResult {
        val resolvedPath = fileSupport.resolvePath(
            path = arguments.requireNonBlankStringArgument("path"),
            toolName = READ_FILE_TOOL_ID,
        )
        fileSupport.ensureFile(resolvedPath)

        val startLine = (arguments.optionalIntArgument("start_line") ?: 1).also { value ->
            require(value >= 1) { "Argument 'start_line' must be at least 1." }
        }
        val maxLines = (arguments.optionalIntArgument("max_lines") ?: DEFAULT_READ_MAX_LINES).also { value ->
            require(value in 1..MAX_READ_MAX_LINES) {
                "Argument 'max_lines' must be between 1 and $MAX_READ_MAX_LINES."
            }
        }

        val content = fileSupport.readUtf8TextFile(resolvedPath.absolutePath)
        val slice = fileSupport.sliceLines(content, startLine = startLine, maxLines = maxLines)

        return PrivateToolResult(
            isError = false,
            content = textContent(
                buildString {
                    appendLine("File: ${resolvedPath.workspaceRelativePath}")
                    if (slice.lines.isEmpty()) {
                        append("(no lines in requested range)")
                    } else {
                        val firstLineNumber = slice.startLine.coerceAtMost(slice.totalLines)
                        slice.lines.forEachIndexed { index, line ->
                            append(firstLineNumber + index)
                            append("| ")
                            appendLine(line)
                        }
                    }
                }.trimEnd(),
            ),
            structuredContent = buildJsonObject {
                put("path", resolvedPath.workspaceRelativePath)
                put("absolute_path", resolvedPath.absolutePath)
                put("start_line", slice.startLine)
                put("max_lines", slice.maxLines)
                put("total_lines", slice.totalLines)
                slice.nextStartLine?.let { nextStartLine -> put("next_start_line", nextStartLine) }
                put(
                    "lines",
                    buildJsonArray {
                        val firstLineNumber = slice.startLine.coerceAtMost(slice.totalLines)
                        slice.lines.forEachIndexed { index, line ->
                            add(
                                buildJsonObject {
                                    put("line_number", firstLineNumber + index)
                                    put("text", line)
                                },
                            )
                        }
                    },
                )
            },
        )
    }
}
