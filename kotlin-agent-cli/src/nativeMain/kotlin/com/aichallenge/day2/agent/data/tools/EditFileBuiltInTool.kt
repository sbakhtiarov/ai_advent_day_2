package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.core.config.AppRuntimeEnvironment
import com.aichallenge.day2.agent.core.config.DefaultAppRuntimeEnvironment
import com.aichallenge.day2.agent.domain.model.PrivateToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val EDIT_FILE_TOOL_ID = "edit_file"
private const val DEFAULT_EDIT_DIFF_LIMIT = 200
private const val MAX_EDIT_DIFF_LIMIT = 400

fun editFileToolRegistration(
    runtimeEnvironment: AppRuntimeEnvironment = DefaultAppRuntimeEnvironment(),
): BuiltInToolRegistration {
    return BuiltInToolRegistration(
        definition = BuiltInToolDefinition(
            toolId = EDIT_FILE_TOOL_ID,
            modelToolName = EDIT_FILE_TOOL_ID,
            description = "Replace all occurrences of a target string in a UTF-8 workspace file and return a paginated unified diff preview.",
            parametersSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "path",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "File path to edit.")
                            },
                        )
                        put(
                            "find_text",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Exact text to replace. All occurrences are replaced.")
                            },
                        )
                        put(
                            "replace_text",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Replacement text. Empty string is allowed.")
                            },
                        )
                        put(
                            "diff_offset",
                            buildJsonObject {
                                put("type", "integer")
                                put("minimum", 0)
                                put("description", "Pagination offset into the diff lines. Defaults to 0.")
                            },
                        )
                        put(
                            "diff_limit",
                            buildJsonObject {
                                put("type", "integer")
                                put("minimum", 1)
                                put("maximum", MAX_EDIT_DIFF_LIMIT)
                                put("description", "Maximum number of diff lines to return. Defaults to $DEFAULT_EDIT_DIFF_LIMIT.")
                            },
                        )
                    },
                )
                put(
                    "required",
                    buildJsonArray {
                        add(JsonPrimitive("path"))
                        add(JsonPrimitive("find_text"))
                        add(JsonPrimitive("replace_text"))
                    },
                )
                put("additionalProperties", false)
            },
        ),
        executor = EditFileBuiltInToolExecutor(runtimeEnvironment),
    )
}

class EditFileBuiltInToolExecutor(
    runtimeEnvironment: AppRuntimeEnvironment,
) : BuiltInToolExecutor {
    private val fileSupport = WorkspaceFileSupport(runtimeEnvironment)

    override suspend fun execute(arguments: JsonObject): PrivateToolResult {
        val resolvedPath = fileSupport.resolvePath(
            path = arguments.requireNonBlankStringArgument("path"),
            toolName = EDIT_FILE_TOOL_ID,
        )
        fileSupport.ensureFile(resolvedPath)

        val findText = arguments.requireStringArgument("find_text")
        if (findText.isEmpty()) {
            throw IllegalArgumentException("Argument 'find_text' must not be empty.")
        }
        val replaceText = arguments.requireStringArgument("replace_text")
        val diffOffset = (arguments.optionalIntArgument("diff_offset") ?: 0).also { value ->
            require(value >= 0) { "Argument 'diff_offset' must be at least 0." }
        }
        val diffLimit = (arguments.optionalIntArgument("diff_limit") ?: DEFAULT_EDIT_DIFF_LIMIT).also { value ->
            require(value in 1..MAX_EDIT_DIFF_LIMIT) {
                "Argument 'diff_limit' must be between 1 and $MAX_EDIT_DIFF_LIMIT."
            }
        }

        val originalContent = fileSupport.readUtf8TextFile(resolvedPath.absolutePath)
        val replacementCount = countOccurrences(originalContent, findText)
        if (replacementCount == 0) {
            throw IllegalArgumentException("Text to replace was not found in '${resolvedPath.absolutePath}'.")
        }

        val updatedContent = originalContent.replace(findText, replaceText)
        fileSupport.writeUtf8TextFile(resolvedPath.absolutePath, updatedContent)
        val diff = fileSupport.buildUnifiedDiff(
            beforeContent = originalContent,
            afterContent = updatedContent,
            pathHint = resolvedPath.workspaceRelativePath,
            offset = diffOffset,
            limit = diffLimit,
        )

        return PrivateToolResult(
            isError = false,
            content = textContent(
                buildString {
                    append("Edited ")
                    append(resolvedPath.workspaceRelativePath)
                    append(". Replaced ")
                    append(replacementCount)
                    append(" occurrence")
                    if (replacementCount != 1) {
                        append('s')
                    }
                    appendLine(".")
                    if (!diff.isDifferent) {
                        append("No diff to display.")
                    } else {
                        diff.pageLines.forEach { line ->
                            appendLine(line)
                        }
                    }
                }.trimEnd(),
            ),
            structuredContent = buildJsonObject {
                put("path", resolvedPath.workspaceRelativePath)
                put("absolute_path", resolvedPath.absolutePath)
                put("replacement_count", replacementCount)
                put("diff_offset", diff.offset)
                put("diff_limit", diff.limit)
                put("total_diff_lines", diff.allLines.size)
                diff.nextOffset?.let { nextOffset -> put("next_diff_offset", nextOffset) }
                put(
                    "diff_lines",
                    buildJsonArray {
                        diff.pageLines.forEach { line ->
                            add(JsonPrimitive(line))
                        }
                    },
                )
            },
        )
    }
}

private fun countOccurrences(content: String, token: String): Int {
    if (token.isEmpty()) {
        return 0
    }
    var count = 0
    var searchIndex = 0
    while (true) {
        val matchIndex = content.indexOf(token, startIndex = searchIndex)
        if (matchIndex < 0) {
            return count
        }
        count += 1
        searchIndex = matchIndex + token.length
    }
}
