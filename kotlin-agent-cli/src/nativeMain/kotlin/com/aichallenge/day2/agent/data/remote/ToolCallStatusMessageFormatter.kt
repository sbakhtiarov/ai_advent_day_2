package com.aichallenge.day2.agent.data.remote

import com.aichallenge.day2.agent.domain.model.PrivateToolBinding
import com.aichallenge.day2.agent.domain.model.PrivateToolTarget
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive

internal object ToolCallStatusMessageFormatter {
    private const val MAX_VALUE_LENGTH = 60

    private val secretFieldNames = setOf(
        "api_key",
        "apikey",
        "authorization",
        "password",
        "secret",
        "token",
    )

    private val contentHeavyFieldNames = setOf(
        "after_content",
        "before_content",
        "content",
        "description",
        "diff",
        "find_text",
        "message",
        "prompt",
        "replace_text",
    )

    fun format(
        binding: PrivateToolBinding,
        arguments: JsonObject,
    ): String {
        return when (val target = binding.target) {
            is PrivateToolTarget.BuiltIn -> formatBuiltIn(target.toolId, arguments)
            is PrivateToolTarget.Mcp -> formatMcp(
                serverName = target.server.name,
                toolName = target.sourceToolName,
                arguments = arguments,
            )
        }
    }

    private fun formatBuiltIn(
        toolId: String,
        arguments: JsonObject,
    ): String {
        return when (toolId) {
            "list_files" -> {
                val path = safeString(arguments, "path") ?: "."
                "Listing files in '$path'..."
            }

            "find_file_by_name" -> {
                val query = safeString(arguments, "query")
                val path = safeString(arguments, "path") ?: "."
                if (query != null) {
                    "Searching filenames for '$query' in '$path'..."
                } else {
                    "Searching filenames in '$path'..."
                }
            }

            "search_file_content" -> {
                val query = safeString(arguments, "query")
                val path = safeString(arguments, "path") ?: "."
                if (query != null) {
                    "Searching file contents for '$query' in '$path'..."
                } else {
                    "Searching file contents in '$path'..."
                }
            }

            "read_file" -> safeString(arguments, "path")
                ?.let { path -> "Reading file '$path'..." }
                ?: "Reading file..."

            "create_file" -> safeString(arguments, "path")
                ?.let { path -> "Writing file '$path'..." }
                ?: "Writing file..."

            "edit_file" -> safeString(arguments, "path")
                ?.let { path -> "Editing file '$path'..." }
                ?: "Editing file..."

            "delete_file" -> safeString(arguments, "path")
                ?.let { path -> "Deleting file '$path'..." }
                ?: "Deleting file..."

            "diff_files" -> safeString(arguments, "path_hint")
                ?.let { path -> "Generating diff for '$path'..." }
                ?: "Generating diff..."

            "scheduler" -> formatScheduler(arguments)

            "notify_user" -> {
                val title = safeString(arguments, "title")
                if (title != null) {
                    "Sending notification '$title'..."
                } else {
                    "Sending notification..."
                }
            }

            "convert_to_pdf" -> {
                val inputFile = safeString(arguments, "input_file")
                val outputFile = safeString(arguments, "output_file")
                when {
                    inputFile != null && outputFile != null -> "Converting '$inputFile' to PDF '$outputFile'..."
                    inputFile != null -> "Converting '$inputFile' to PDF..."
                    else -> "Converting file to PDF..."
                }
            }

            "fetch_github_pull_request" -> {
                val prLabel = safePullRequestReference(arguments)
                if (prLabel != null) {
                    "Fetching GitHub pull request '$prLabel'..."
                } else {
                    "Fetching GitHub pull request..."
                }
            }

            else -> "Calling built-in tool '$toolId'..."
        }
    }

    private fun formatScheduler(arguments: JsonObject): String {
        return when (safeString(arguments, "action")?.lowercase()) {
            "current_time" -> "Checking local time..."
            "list" -> "Listing scheduled jobs..."
            "cancel" -> {
                val scheduleId = safeString(arguments, "schedule_id")
                if (scheduleId != null) {
                    "Canceling schedule '$scheduleId'..."
                } else {
                    "Canceling schedule..."
                }
            }

            "create",
            "delay",
            -> {
                val label = safeString(arguments, "label")
                if (label != null) {
                    "Scheduling prompt '$label'..."
                } else {
                    "Scheduling prompt..."
                }
            }

            else -> "Checking scheduler..."
        }
    }

    private fun formatMcp(
        serverName: String,
        toolName: String,
        arguments: JsonObject,
    ): String {
        val toolRef = "$serverName/$toolName"
        val verb = inferVerb(toolName) ?: return "Calling MCP tool '$toolRef'..."
        val details = buildMcpDetails(arguments)
        return buildString {
            append(verb)
            append(" via MCP tool '")
            append(toolRef)
            append('\'')
            details?.let { append(it) }
            append("...")
        }
    }

    private fun inferVerb(toolName: String): String? {
        val normalized = toolName.lowercase()
        return when {
            normalized.startsWith("search") || normalized.contains("_search") -> "Searching"
            normalized.startsWith("list") || normalized.contains("_list") -> "Listing"
            normalized.startsWith("read") || normalized.contains("_read") || normalized.startsWith("get") || normalized.contains("_get") -> "Reading"
            normalized.startsWith("fetch") || normalized.contains("_fetch") -> "Fetching"
            normalized.startsWith("create") || normalized.contains("_create") -> "Creating"
            normalized.startsWith("update") || normalized.contains("_update") -> "Updating"
            normalized.startsWith("edit") || normalized.contains("_edit") -> "Editing"
            normalized.startsWith("delete") || normalized.contains("_delete") ||
                normalized.startsWith("remove") || normalized.contains("_remove") -> "Deleting"
            normalized.startsWith("cancel") || normalized.contains("_cancel") -> "Canceling"
            normalized.startsWith("convert") || normalized.contains("_convert") -> "Converting"
            normalized.startsWith("send") || normalized.contains("_send") ||
                normalized.startsWith("notify") || normalized.contains("_notify") -> "Sending"
            else -> null
        }
    }

    private fun buildMcpDetails(arguments: JsonObject): String? {
        val detailKeys = listOf("query", "q", "path", "title", "name", "label", "id", "schedule_id", "pr_url")
        val details = detailKeys.mapNotNull { key ->
            safeFieldPhrase(key, arguments[key])
        }.distinct().take(2)
        return details.takeIf { it.isNotEmpty() }?.joinToString(separator = "", prefix = " ")
    }

    private fun safeFieldPhrase(
        name: String,
        element: JsonElement?,
    ): String? {
        val value = safeDisplayValue(name, element) ?: return null
        return when (name) {
            "query", "q" -> "for '$value'"
            "path" -> "in '$value'"
            "title", "name", "label" -> "named '$value'"
            "id", "schedule_id" -> "with id '$value'"
            "pr_url" -> "for '$value'"
            else -> null
        }
    }

    private fun safeString(
        arguments: JsonObject,
        name: String,
    ): String? {
        return safeDisplayValue(name, arguments[name])
    }

    private fun safeDisplayValue(
        name: String,
        element: JsonElement?,
    ): String? {
        if (element == null) {
            return null
        }
        val normalizedName = name.lowercase()
        if (normalizedName in secretFieldNames || normalizedName in contentHeavyFieldNames) {
            return null
        }
        val primitive = element as? JsonPrimitive ?: return null
        val rawValue = when {
            primitive.isString -> primitive.contentOrNull
            primitive.booleanOrNull != null -> primitive.booleanOrNull.toString()
            primitive.intOrNull != null -> primitive.intOrNull.toString()
            primitive.longOrNull != null -> primitive.longOrNull.toString()
            else -> primitive.contentOrNull
        } ?: return null
        val collapsed = rawValue
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: return null
        return truncate(collapsed)
    }

    private fun safePullRequestReference(arguments: JsonObject): String? {
        val rawUrl = safeString(arguments, "pr_url") ?: return null
        val match = GITHUB_PULL_REQUEST_REGEX.matchEntire(rawUrl)
        return if (match != null) {
            val (owner, repo, number) = match.destructured
            "$owner/$repo#$number"
        } else {
            rawUrl
        }
    }

    private fun truncate(value: String): String {
        return if (value.length <= MAX_VALUE_LENGTH) {
            value
        } else {
            value.take(MAX_VALUE_LENGTH - 3) + "..."
        }
    }

    private val GITHUB_PULL_REQUEST_REGEX =
        Regex("""https://github\.com/([^/]+)/([^/]+)/pull/([0-9]+)/*""", RegexOption.IGNORE_CASE)
}
