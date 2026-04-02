package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.domain.model.PrivateToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val DIFF_FILES_TOOL_ID = "diff_files"
private const val DEFAULT_DIFF_LIMIT = 200
private const val MAX_DIFF_LIMIT = 400

fun diffFilesToolRegistration(): BuiltInToolRegistration {
    return BuiltInToolRegistration(
        definition = BuiltInToolDefinition(
            toolId = DIFF_FILES_TOOL_ID,
            modelToolName = DIFF_FILES_TOOL_ID,
            description = "Produce a paginated unified diff between two snapshots of one file.",
            parametersSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "before_content",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Original file content snapshot.")
                            },
                        )
                        put(
                            "after_content",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Updated file content snapshot.")
                            },
                        )
                        put(
                            "path_hint",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Optional path label to show in the diff header.")
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
                                put("maximum", MAX_DIFF_LIMIT)
                                put("description", "Maximum number of diff lines to return. Defaults to $DEFAULT_DIFF_LIMIT.")
                            },
                        )
                    },
                )
                put(
                    "required",
                    buildJsonArray {
                        add(JsonPrimitive("before_content"))
                        add(JsonPrimitive("after_content"))
                    },
                )
                put("additionalProperties", false)
            },
        ),
        executor = DiffFilesBuiltInToolExecutor(),
    )
}

class DiffFilesBuiltInToolExecutor : BuiltInToolExecutor {
    private val fileSupport = WorkspaceFileSupport()

    override suspend fun execute(arguments: JsonObject): PrivateToolResult {
        val beforeContent = arguments.requireStringArgument("before_content")
        val afterContent = arguments.requireStringArgument("after_content")
        val pathHint = arguments.optionalStringArgument("path_hint")
        val diffOffset = (arguments.optionalIntArgument("diff_offset") ?: 0).also { value ->
            require(value >= 0) { "Argument 'diff_offset' must be at least 0." }
        }
        val diffLimit = (arguments.optionalIntArgument("diff_limit") ?: DEFAULT_DIFF_LIMIT).also { value ->
            require(value in 1..MAX_DIFF_LIMIT) {
                "Argument 'diff_limit' must be between 1 and $MAX_DIFF_LIMIT."
            }
        }

        val diff = fileSupport.buildUnifiedDiff(
            beforeContent = beforeContent,
            afterContent = afterContent,
            pathHint = pathHint,
            offset = diffOffset,
            limit = diffLimit,
        )

        return PrivateToolResult(
            isError = false,
            content = textContent(
                if (!diff.isDifferent) {
                    "No differences."
                } else {
                    diff.pageLines.joinToString(separator = "\n")
                },
            ),
            structuredContent = buildJsonObject {
                put("path_hint", pathHint?.trim().orEmpty())
                put("is_different", diff.isDifferent)
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
