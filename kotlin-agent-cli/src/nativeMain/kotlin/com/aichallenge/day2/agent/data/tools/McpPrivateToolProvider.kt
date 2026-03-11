package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.domain.model.McpRuntimeStatus
import com.aichallenge.day2.agent.domain.model.McpServerRuntimeState
import com.aichallenge.day2.agent.domain.model.McpToolCatalogStatus
import com.aichallenge.day2.agent.domain.model.McpToolDefinition
import com.aichallenge.day2.agent.domain.model.PrivateToolBinding
import com.aichallenge.day2.agent.domain.model.PrivateToolTarget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class PrivateToolProvision(
    val privateTools: List<PrivateToolBinding> = emptyList(),
    val systemMessages: List<String> = emptyList(),
)

class McpPrivateToolProvider(
    private val json: Json = Json {
        ignoreUnknownKeys = true
    },
) {
    fun build(runtimeStates: List<McpServerRuntimeState>): PrivateToolProvision {
        val privateTools = mutableListOf<PrivateToolBinding>()
        val usedToolNames = linkedSetOf<String>()
        val skippedReasonsByServer = linkedMapOf<String, MutableList<String>>()

        runtimeStates.forEach { state ->
            when (state.status) {
                McpRuntimeStatus.READY -> {
                    when (state.toolCatalogStatus) {
                        McpToolCatalogStatus.LOADED -> {
                            var invalidSchemaCount = 0
                            state.tools.forEach { tool ->
                                val parametersSchema = parseParametersSchema(tool.inputSchemaJson)
                                if (parametersSchema == null) {
                                    invalidSchemaCount += 1
                                    return@forEach
                                }

                                val normalizedParametersSchema = normalizeParametersSchema(
                                    tool = tool,
                                    parametersSchema = parametersSchema,
                                )
                                privateTools += PrivateToolBinding(
                                    modelToolName = allocatePrivateToolName(
                                        serverName = state.server.name,
                                        toolName = tool.name,
                                        usedNames = usedToolNames,
                                    ),
                                    target = PrivateToolTarget.Mcp(
                                        server = state.server,
                                        sourceToolName = tool.name,
                                    ),
                                    description = buildPrivateToolDescription(
                                        serverName = state.server.name,
                                        tool = tool,
                                        parametersSchema = normalizedParametersSchema,
                                    ),
                                    parametersSchema = normalizedParametersSchema,
                                )
                            }

                            if (invalidSchemaCount > 0) {
                                appendSkipReason(
                                    skippedReasonsByServer = skippedReasonsByServer,
                                    serverName = state.server.name,
                                    reason = "$invalidSchemaCount tool input schema${if (invalidSchemaCount == 1) "" else "s"} could not be parsed",
                                )
                            }
                        }

                        McpToolCatalogStatus.FAILED -> {
                            appendSkipReason(
                                skippedReasonsByServer = skippedReasonsByServer,
                                serverName = state.server.name,
                                reason = "tool loading failed: ${state.toolCatalogFailureMessage ?: "Unexpected error"}",
                            )
                        }

                        else -> {
                            appendSkipReason(
                                skippedReasonsByServer = skippedReasonsByServer,
                                serverName = state.server.name,
                                reason = "tools are not available",
                            )
                        }
                    }
                }

                McpRuntimeStatus.FAILED -> {
                    appendSkipReason(
                        skippedReasonsByServer = skippedReasonsByServer,
                        serverName = state.server.name,
                        reason = "initialization failed: ${state.failureMessage ?: "Unexpected error"}",
                    )
                }

                else -> {
                    appendSkipReason(
                        skippedReasonsByServer = skippedReasonsByServer,
                        serverName = state.server.name,
                        reason = "server is not ready",
                    )
                }
            }
        }

        return PrivateToolProvision(
            privateTools = privateTools,
            systemMessages = skippedReasonsByServer.map { (serverName, reasons) ->
                "system> MCP server '$serverName' was skipped for LLM tool exposure: ${reasons.joinToString(separator = "; ")}"
            },
        )
    }

    private fun parseParametersSchema(rawSchema: String): JsonObject? {
        return runCatching {
            json.parseToJsonElement(rawSchema).jsonObject
        }.getOrNull()
    }

    private fun buildPrivateToolDescription(
        serverName: String,
        tool: McpToolDefinition,
        parametersSchema: JsonObject,
    ): String {
        val baseDescription = tool.description?.trim().takeUnless { it.isNullOrEmpty() }
        val parameterGuidance = buildPrivateToolParameterGuidance(
            tool = tool,
            parametersSchema = parametersSchema,
        )
        val sourceDescription = "Private MCP tool from server '$serverName'. Original MCP tool name: '${tool.name}'."
        return listOfNotNull(
            baseDescription,
            parameterGuidance,
            sourceDescription,
        ).joinToString(separator = "\n\n")
    }

    private fun normalizeParametersSchema(
        tool: McpToolDefinition,
        parametersSchema: JsonObject,
    ): JsonObject {
        return when (tool.name) {
            "drive_list_files" -> appendJsonSchemaPropertyDescription(
                schema = parametersSchema,
                propertyName = "q",
                appendedDescription = "Optional. Omit this field to list recent non-trashed Drive files with the default query `trashed = false`. If you provide `q`, use raw Google Drive `q` syntax, not natural-language search text. Example: `trashed = false and name contains 'report'`.",
            )

            else -> parametersSchema
        }
    }

    private fun buildPrivateToolParameterGuidance(
        tool: McpToolDefinition,
        parametersSchema: JsonObject,
    ): String? {
        val properties = runCatching { parametersSchema["properties"]?.jsonObject }.getOrNull()
            ?: return driveSpecificPrivateToolGuidance(tool)
        val required = runCatching {
            parametersSchema["required"]?.jsonArray?.mapNotNull { element ->
                element.jsonPrimitive.contentOrNull?.trim()?.takeIf { value -> value.isNotEmpty() }
            }?.toSet()
        }.getOrNull().orEmpty()

        val propertyLines = properties.entries.mapNotNull { (name, definitionElement) ->
            val definition = runCatching { definitionElement.jsonObject }.getOrNull() ?: return@mapNotNull null
            val type = definition["type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifEmpty { "value" }
            val description = definition["description"]?.jsonPrimitive?.contentOrNull?.trim()
            val enumValues = runCatching {
                definition["enum"]?.jsonArray?.mapNotNull { element ->
                    element.jsonPrimitive.contentOrNull?.trim()
                }?.filter { value -> value.isNotEmpty() }
            }.getOrNull().orEmpty()
            val defaultValue = definition["default"]?.toString()?.trim()?.takeIf { value -> value.isNotEmpty() }
            buildString {
                append("- `")
                append(name)
                append("`: ")
                append(type)
                if (name in required) {
                    append(" (required)")
                } else {
                    append(" (optional)")
                }
                description?.takeIf { it.isNotEmpty() }?.let { normalizedDescription ->
                    append(" - ")
                    append(normalizedDescription)
                }
                if (enumValues.isNotEmpty()) {
                    append(" Allowed values: ")
                    append(enumValues.joinToString(separator = ", "))
                    append('.')
                }
                if (defaultValue != null) {
                    append(" Default: ")
                    append(defaultValue)
                    append('.')
                }
            }.trim()
        }

        val guidanceSections = buildList {
            if (propertyLines.isNotEmpty()) {
                add(
                    buildString {
                        appendLine("Arguments:")
                        propertyLines.forEach { line ->
                            appendLine(line)
                        }
                    }.trimEnd(),
                )
            }
            driveSpecificPrivateToolGuidance(tool)?.let(::add)
        }

        return guidanceSections.takeIf { it.isNotEmpty() }?.joinToString(separator = "\n\n")
    }

    private fun driveSpecificPrivateToolGuidance(tool: McpToolDefinition): String? {
        if (tool.name != "drive_list_files") {
            return null
        }

        return "You can call this tool with `{}` and no arguments to list recent non-trashed Drive files. Only set `q` when you can write raw Google Drive `q` syntax. Never put natural-language search text in `q`. Example: `trashed = false and name contains 'report'`."
    }

    private fun appendJsonSchemaPropertyDescription(
        schema: JsonObject,
        propertyName: String,
        appendedDescription: String,
    ): JsonObject {
        val properties = runCatching { schema["properties"]?.jsonObject }.getOrNull() ?: return schema
        val propertyDefinition = runCatching { properties[propertyName]?.jsonObject }.getOrNull() ?: return schema
        val normalizedDescription = appendedDescription.trim()
        if (normalizedDescription.isEmpty()) {
            return schema
        }

        val currentDescription = propertyDefinition["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val mergedDescription = when {
            currentDescription.isEmpty() -> normalizedDescription
            currentDescription.contains(normalizedDescription) -> currentDescription
            else -> "$currentDescription $normalizedDescription"
        }

        val updatedPropertyDefinition = buildJsonObject {
            propertyDefinition.forEach { (key, value) ->
                if (key != "description") {
                    put(key, value)
                }
            }
            put("description", mergedDescription)
        }
        val updatedProperties = buildJsonObject {
            properties.forEach { (key, value) ->
                put(
                    key,
                    if (key == propertyName) {
                        updatedPropertyDefinition
                    } else {
                        value
                    },
                )
            }
        }

        return buildJsonObject {
            schema.forEach { (key, value) ->
                put(
                    key,
                    if (key == "properties") {
                        updatedProperties
                    } else {
                        value
                    },
                )
            }
        }
    }

    private fun allocatePrivateToolName(
        serverName: String,
        toolName: String,
        usedNames: MutableSet<String>,
    ): String {
        val baseName = buildString {
            append(sanitizePrivateToolNamePart(serverName, "server"))
            append("__")
            append(sanitizePrivateToolNamePart(toolName, "tool"))
        }
        var candidate = truncatePrivateToolName(baseName)
        if (usedNames.add(candidate)) {
            return candidate
        }

        val hash = stablePrivateToolHash(baseName)
        var collisionIndex = 2
        while (true) {
            val suffix = "_${hash}_$collisionIndex"
            candidate = truncatePrivateToolName(baseName, suffix)
            if (usedNames.add(candidate)) {
                return candidate
            }
            collisionIndex += 1
        }
    }

    private fun sanitizePrivateToolNamePart(value: String, fallback: String): String {
        val sanitized = buildString {
            value.lowercase().forEach { character ->
                append(if (character.isLetterOrDigit()) character else '_')
            }
        }.replace(MULTIPLE_UNDERSCORES_REGEX, "_")
            .trim('_')

        return sanitized.ifEmpty { fallback }
    }

    private fun truncatePrivateToolName(baseName: String, suffix: String = ""): String {
        val allowedBaseLength = (MCP_FUNCTION_TOOL_NAME_MAX_LENGTH - suffix.length).coerceAtLeast(1)
        return baseName.take(allowedBaseLength) + suffix
    }

    private fun stablePrivateToolHash(value: String): String = value.hashCode().toUInt().toString(radix = 16)

    private fun appendSkipReason(
        skippedReasonsByServer: MutableMap<String, MutableList<String>>,
        serverName: String,
        reason: String,
    ) {
        val normalizedReason = reason.trim()
        if (normalizedReason.isEmpty()) {
            return
        }
        skippedReasonsByServer.getOrPut(serverName) { mutableListOf() } += normalizedReason
    }

    companion object {
        private val MULTIPLE_UNDERSCORES_REGEX = Regex("_+")
        private const val MCP_FUNCTION_TOOL_NAME_MAX_LENGTH = 64
    }
}
