package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.core.config.AppRuntimeEnvironment
import com.aichallenge.day2.agent.core.config.DefaultAppRuntimeEnvironment
import com.aichallenge.day2.agent.domain.model.PrivateToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val SEARCH_FILE_CONTENT_TOOL_ID = "search_file_content"
private const val DEFAULT_CONTENT_SEARCH_LIMIT = 20
private const val MAX_CONTENT_SEARCH_LIMIT = 50
private const val DEFAULT_CONTENT_SEARCH_CONTEXT_LINES = 2
private const val MAX_CONTENT_SEARCH_CONTEXT_LINES = 5

private data class ContentSearchMatch(
    val path: String,
    val lineNumber: Int,
    val contextStartLine: Int,
    val contextEndLine: Int,
    val snippet: String,
)

fun searchFileContentToolRegistration(
    runtimeEnvironment: AppRuntimeEnvironment = DefaultAppRuntimeEnvironment(),
): BuiltInToolRegistration {
    return BuiltInToolRegistration(
        definition = BuiltInToolDefinition(
            toolId = SEARCH_FILE_CONTENT_TOOL_ID,
            modelToolName = SEARCH_FILE_CONTENT_TOOL_ID,
            description = "Recursively search UTF-8 text file contents for a plain-text substring.",
            parametersSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "query",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Plain-text substring to search for.")
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
                                put("maximum", MAX_CONTENT_SEARCH_LIMIT)
                                put("description", "Maximum number of matches to return. Defaults to $DEFAULT_CONTENT_SEARCH_LIMIT.")
                            },
                        )
                        put(
                            "case_sensitive",
                            buildJsonObject {
                                put("type", "boolean")
                                put("description", "When true, match content case exactly. Defaults to false.")
                            },
                        )
                        put(
                            "context_lines",
                            buildJsonObject {
                                put("type", "integer")
                                put("minimum", 0)
                                put("maximum", MAX_CONTENT_SEARCH_CONTEXT_LINES)
                                put("description", "Number of lines of context to include before and after each match. Defaults to $DEFAULT_CONTENT_SEARCH_CONTEXT_LINES.")
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
        executor = SearchFileContentBuiltInToolExecutor(runtimeEnvironment),
    )
}

class SearchFileContentBuiltInToolExecutor(
    runtimeEnvironment: AppRuntimeEnvironment,
) : BuiltInToolExecutor {
    private val fileSupport = WorkspaceFileSupport(runtimeEnvironment)

    override suspend fun execute(arguments: JsonObject): PrivateToolResult {
        val query = arguments.requireNonBlankStringArgument("query")
        val resolvedPath = fileSupport.resolveOptionalPath(
            path = arguments.optionalStringArgument("path"),
            toolName = SEARCH_FILE_CONTENT_TOOL_ID,
        )
        fileSupport.ensureDirectory(resolvedPath)

        val offset = (arguments.optionalIntArgument("offset") ?: 0).also { value ->
            require(value >= 0) { "Argument 'offset' must be at least 0." }
        }
        val limit = (arguments.optionalIntArgument("limit") ?: DEFAULT_CONTENT_SEARCH_LIMIT).also { value ->
            require(value in 1..MAX_CONTENT_SEARCH_LIMIT) {
                "Argument 'limit' must be between 1 and $MAX_CONTENT_SEARCH_LIMIT."
            }
        }
        val caseSensitive = arguments.optionalBooleanArgument("case_sensitive") ?: false
        val contextLines = (arguments.optionalIntArgument("context_lines") ?: DEFAULT_CONTENT_SEARCH_CONTEXT_LINES).also { value ->
            require(value in 0..MAX_CONTENT_SEARCH_CONTEXT_LINES) {
                "Argument 'context_lines' must be between 0 and $MAX_CONTENT_SEARCH_CONTEXT_LINES."
            }
        }

        val normalizedQuery = if (caseSensitive) query else query.lowercase()
        val matches = mutableListOf<ContentSearchMatch>()
        var skippedFiles = 0
        fileSupport.walkFiles(resolvedPath.absolutePath).forEach { absolutePath ->
            val relativePath = fileSupport.toWorkspaceRelativePath(resolvedPath.workspaceRoot, absolutePath)
            val content = runCatching { fileSupport.readUtf8TextFile(absolutePath) }
                .getOrElse {
                    skippedFiles += 1
                    return@forEach
                }
            val lines = if (content.isEmpty()) emptyList() else content.split('\n').map { line -> line.removeSuffix("\r") }
            lines.forEachIndexed { index, line ->
                val haystack = if (caseSensitive) line else line.lowercase()
                if (haystack.contains(normalizedQuery)) {
                    val contextStartIndex = (index - contextLines).coerceAtLeast(0)
                    val contextEndIndex = (index + contextLines).coerceAtMost(lines.lastIndex)
                    val snippet = buildString {
                        for (lineIndex in contextStartIndex..contextEndIndex) {
                            append(lineIndex + 1)
                            append("| ")
                            append(lines[lineIndex])
                            if (lineIndex != contextEndIndex) {
                                append('\n')
                            }
                        }
                    }
                    matches += ContentSearchMatch(
                        path = relativePath,
                        lineNumber = index + 1,
                        contextStartLine = contextStartIndex + 1,
                        contextEndLine = contextEndIndex + 1,
                        snippet = snippet,
                    )
                }
            }
        }

        val page = fileSupport.paginate(matches, offset = offset, limit = limit)
        return PrivateToolResult(
            isError = false,
            content = textContent(
                buildString {
                    appendLine("Content matches for \"$query\":")
                    if (page.items.isEmpty()) {
                        append("(no matches)")
                    } else {
                        page.items.forEachIndexed { index, match ->
                            if (index > 0) {
                                appendLine()
                            }
                            append(match.path)
                            append(':')
                            appendLine(match.lineNumber.toString())
                            append(match.snippet)
                            appendLine()
                        }
                    }
                    if (skippedFiles > 0) {
                        if (page.items.isNotEmpty()) {
                            appendLine()
                        }
                        append("Skipped $skippedFiles non-text file")
                        if (skippedFiles != 1) {
                            append('s')
                        }
                        append('.')
                    }
                }.trimEnd(),
            ),
            structuredContent = buildJsonObject {
                put("query", query)
                put("path", resolvedPath.workspaceRelativePath)
                put("offset", page.offset)
                put("limit", page.limit)
                put("total_count", page.totalCount)
                put("skipped_file_count", skippedFiles)
                page.nextOffset?.let { nextOffset -> put("next_offset", nextOffset) }
                put(
                    "matches",
                    buildJsonArray {
                        page.items.forEach { match ->
                            add(
                                buildJsonObject {
                                    put("path", match.path)
                                    put("line_number", match.lineNumber)
                                    put("context_start_line", match.contextStartLine)
                                    put("context_end_line", match.contextEndLine)
                                    put("snippet", match.snippet)
                                },
                            )
                        }
                    },
                )
            },
        )
    }
}
