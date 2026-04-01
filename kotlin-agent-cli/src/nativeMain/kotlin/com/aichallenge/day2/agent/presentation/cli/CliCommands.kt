package com.aichallenge.day2.agent.presentation.cli

data class CliCommandDescriptor(
    val name: String,
    val summaryUsage: String = name,
    val helpUsage: String = name,
    val helpSummary: String,
    val acceptsArguments: Boolean = false,
    val extraHelpLines: List<String> = emptyList(),
)

internal data class CliCommandCompletionResult(
    val replacementText: String,
    val matches: List<String>,
    val isAmbiguous: Boolean,
    val shouldApply: Boolean,
)

internal val CLI_COMMAND_DESCRIPTORS: List<CliCommandDescriptor> = listOf(
    CliCommandDescriptor(
        name = "/help",
        helpSummary = "show this help message",
    ),
    CliCommandDescriptor(
        name = "/project_help",
        helpSummary = "toggle Wire project-information mode",
    ),
    CliCommandDescriptor(
        name = "/review_pr",
        summaryUsage = "/review_pr <public-pr-url>",
        helpUsage = "/review_pr <public-pr-url>",
        helpSummary = "review a public GitHub pull request with Wire context",
        acceptsArguments = true,
    ),
    CliCommandDescriptor(
        name = "/api",
        helpSummary = "select the active API from api-settings.json",
    ),
    CliCommandDescriptor(
        name = "/models",
        helpSummary = "list available models for the active API",
    ),
    CliCommandDescriptor(
        name = "/model",
        summaryUsage = "/model <id|number>",
        helpUsage = "/model <id|number>",
        helpSummary = "switch active model (must be from /models)",
        acceptsArguments = true,
    ),
    CliCommandDescriptor(
        name = "/temperature",
        summaryUsage = "/temperature [0..2|default]",
        helpUsage = "/temperature [arg]",
        helpSummary = "show or set global temperature override (arg: 0..2 or default)",
        acceptsArguments = true,
    ),
    CliCommandDescriptor(
        name = "/memory",
        helpSummary = "show session-memory context usage",
    ),
    CliCommandDescriptor(
        name = "/compact",
        helpSummary = "choose memory compaction strategy",
    ),
    CliCommandDescriptor(
        name = "/profile",
        helpSummary = "choose active user profile",
    ),
    CliCommandDescriptor(
        name = "/workflow",
        helpSummary = "enable workflow mode with workflow selection (toggle off when enabled)",
    ),
    CliCommandDescriptor(
        name = "/mcp",
        helpSummary = "configure MCP servers",
        acceptsArguments = true,
        extraHelpLines = listOf(
            "/mcp <n> <tool> [json-object-args]",
            "                     call an MCP tool on an enabled ready server",
        ),
    ),
    CliCommandDescriptor(
        name = "/invariant",
        helpSummary = "configure invariant constraints",
    ),
    CliCommandDescriptor(
        name = "/reset",
        helpSummary = "clear conversation and working memory; keep current system prompt",
    ),
    CliCommandDescriptor(
        name = "/exit",
        helpSummary = "close the application",
    ),
)

internal fun buildCliCommandHeaderSummary(
    commands: List<CliCommandDescriptor> = CLI_COMMAND_DESCRIPTORS,
): String {
    return (commands.map(CliCommandDescriptor::summaryUsage) + "@<path>").joinToString(separator = ", ")
}

internal fun buildCliCommandHelpText(
    commands: List<CliCommandDescriptor> = CLI_COMMAND_DESCRIPTORS,
): String {
    return buildString {
        appendLine("Available commands:")
        commands.forEach { command ->
            append(command.helpUsage.padEnd(CLI_HELP_USAGE_COLUMN_WIDTH))
            appendLine(command.helpSummary)
            command.extraHelpLines.forEach { line ->
                appendLine(line)
            }
        }
        append("@<path>".padEnd(CLI_HELP_USAGE_COLUMN_WIDTH))
        append("attach file for the next prompt")
    }
}

internal fun resolveCliCommandCompletion(
    inputText: String,
    commands: List<CliCommandDescriptor> = CLI_COMMAND_DESCRIPTORS,
): CliCommandCompletionResult {
    if (inputText.isEmpty() || !inputText.startsWith("/") || inputText.any(Char::isWhitespace)) {
        return CliCommandCompletionResult(
            replacementText = inputText,
            matches = emptyList(),
            isAmbiguous = false,
            shouldApply = false,
        )
    }

    val matches = commands.filter { command ->
        command.name.startsWith(inputText)
    }
    if (matches.isEmpty()) {
        return CliCommandCompletionResult(
            replacementText = inputText,
            matches = emptyList(),
            isAmbiguous = false,
            shouldApply = false,
        )
    }

    val matchNames = matches.map(CliCommandDescriptor::name).sorted()

    if (matches.size == 1) {
        val match = matches.single()
        val replacement = buildString {
            append(match.name)
            if (match.acceptsArguments) {
                append(' ')
            }
        }
        return CliCommandCompletionResult(
            replacementText = replacement,
            matches = matchNames,
            isAmbiguous = false,
            shouldApply = replacement != inputText,
        )
    }

    val sharedPrefix = longestSharedPrefix(matches.map(CliCommandDescriptor::name))
    val replacement = if (sharedPrefix.length > inputText.length) sharedPrefix else inputText
    return CliCommandCompletionResult(
        replacementText = replacement,
        matches = matchNames,
        isAmbiguous = true,
        shouldApply = replacement != inputText,
    )
}

internal fun buildCliCommandMatchesHint(matches: List<String>): String? {
    val normalizedMatches = matches.distinct()
    if (normalizedMatches.isEmpty()) {
        return null
    }
    return normalizedMatches.joinToString(
        separator = ", ",
        prefix = "matches> ",
    )
}

private fun longestSharedPrefix(values: List<String>): String {
    if (values.isEmpty()) {
        return ""
    }
    val first = values.first()
    var prefixLength = first.length
    for (value in values.drop(1)) {
        prefixLength = prefixLength.coerceAtMost(value.length)
        var index = 0
        while (index < prefixLength && first[index] == value[index]) {
            index += 1
        }
        prefixLength = index
        if (prefixLength == 0) {
            break
        }
    }
    return first.substring(0, prefixLength)
}

private const val CLI_HELP_USAGE_COLUMN_WIDTH = 21
