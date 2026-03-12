package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.core.config.AppRuntimeInfo
import com.aichallenge.day2.agent.domain.model.PrivateToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

private const val NOTIFY_USER_TOOL_ID = "notify_user"

fun notifyUserToolRegistration(
    notificationService: NotificationService,
): BuiltInToolRegistration {
    return BuiltInToolRegistration(
        definition = BuiltInToolDefinition(
            toolId = NOTIFY_USER_TOOL_ID,
            modelToolName = NOTIFY_USER_TOOL_ID,
            description = "Send a local macOS notification to the user. Use this when the user explicitly asks to be notified or when you need to raise a local notification for them.",
            parametersSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "message",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Notification body text to show the user.")
                            },
                        )
                        put(
                            "title",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Optional notification title. Defaults to kotlin-agent-cli when omitted.")
                            },
                        )
                    },
                )
                put("required", buildJsonArray { add(JsonPrimitive("message")) })
                put("additionalProperties", false)
            },
        ),
        executor = NotifyUserBuiltInToolExecutor(notificationService),
    )
}

class NotifyUserBuiltInToolExecutor(
    private val notificationService: NotificationService,
) : BuiltInToolExecutor {
    override suspend fun execute(arguments: JsonObject): PrivateToolResult {
        val message = requireStringArgument(arguments, "message")
        val title = optionalStringArgument(arguments, "title") ?: AppRuntimeInfo.APP_NAME
        val delivery = notificationService.send(message = message, title = title)

        return PrivateToolResult(
            isError = false,
            content = textContent("Notification sent"),
            structuredContent = buildJsonObject {
                put("title", delivery.title)
                put("message", delivery.message)
                put("backend", delivery.backend)
            },
        )
    }

    private fun requireStringArgument(arguments: JsonObject, name: String): String {
        val value = optionalStringArgument(arguments, name)
            ?: throw IllegalArgumentException("Argument '$name' must be a non-blank string.")
        return value
    }

    private fun optionalStringArgument(arguments: JsonObject, name: String): String? {
        val value = arguments[name]?.jsonPrimitive?.contentOrNull
            ?: return null
        return value.trim().takeIf { normalized -> normalized.isNotEmpty() }
    }

    private fun textContent(text: String): JsonArray {
        return buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                },
            )
        }
    }
}
