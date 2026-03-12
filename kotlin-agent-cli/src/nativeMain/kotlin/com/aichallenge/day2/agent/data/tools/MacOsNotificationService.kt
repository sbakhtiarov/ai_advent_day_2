package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.core.config.AppRuntimeInfo

data class NotificationDelivery(
    val title: String,
    val message: String,
    val backend: String,
)

interface NotificationService {
    fun send(message: String, title: String = AppRuntimeInfo.APP_NAME): NotificationDelivery
}

private const val OSASCRIPT_COMMAND = "/usr/bin/osascript"

class MacOsNotificationService(
    private val commandExecutor: CommandExecutor,
) : NotificationService {
    override fun send(message: String, title: String): NotificationDelivery {
        val normalizedMessage = message.trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("message must be a non-blank string.")
        val normalizedTitle = title.trim().ifEmpty { AppRuntimeInfo.APP_NAME }
        val result = commandExecutor.execute(
            command = OSASCRIPT_COMMAND,
            args = listOf(
                "-e",
                "on run argv",
                "-e",
                "set notificationMessage to item 1 of argv",
                "-e",
                "set notificationTitle to item 2 of argv",
                "-e",
                "display notification notificationMessage with title notificationTitle",
                "-e",
                "return \"Notification sent\"",
                "-e",
                "end run",
                normalizedMessage,
                normalizedTitle,
            ),
        )
        if (result.exitCode != 0) {
            val details = result.stderr.trim().ifEmpty { result.stdout.trim() }
            val suffix = details.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
            throw IllegalStateException("Notification delivery failed with exit code ${result.exitCode}$suffix")
        }

        return NotificationDelivery(
            title = normalizedTitle,
            message = normalizedMessage,
            backend = "osascript",
        )
    }
}
