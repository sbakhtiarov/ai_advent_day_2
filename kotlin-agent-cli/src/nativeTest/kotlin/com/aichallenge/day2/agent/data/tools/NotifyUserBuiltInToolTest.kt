package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.core.config.AppRuntimeInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NotifyUserBuiltInToolTest {
    @Test
    fun executeRequiresNonBlankMessage() = runBlocking {
        val executor = NotifyUserBuiltInToolExecutor(FakeCommandExecutor())

        val error = assertFailsWith<IllegalArgumentException> {
            executor.execute(
                buildJsonObject {
                    put("message", "   ")
                },
            )
        }

        assertContains(error.message.orEmpty(), "message")
    }

    @Test
    fun executeDefaultsTitleWhenMissingOrBlank() = runBlocking {
        val commandExecutor = FakeCommandExecutor()
        val executor = NotifyUserBuiltInToolExecutor(commandExecutor)

        val result = executor.execute(
            buildJsonObject {
                put("message", "Build finished")
                put("title", "   ")
            },
        )

        assertEquals(AppRuntimeInfo.APP_NAME, result.structuredContent?.get("title")?.toString()?.trim('"'))
        val args = commandExecutor.recordedArgs.single()
        assertEquals("Build finished", args[args.lastIndex - 1])
        assertEquals(AppRuntimeInfo.APP_NAME, args.last())
    }

    @Test
    fun executePassesMessageAndTitleAsRawArguments() = runBlocking {
        val commandExecutor = FakeCommandExecutor()
        val executor = NotifyUserBuiltInToolExecutor(commandExecutor)
        val message = "Line 1 \"quoted\"\nLine 2 \\slash"
        val title = "Heads \"up\""

        executor.execute(
            buildJsonObject {
                put("message", message)
                put("title", title)
            },
        )

        val args = commandExecutor.recordedArgs.single()
        assertEquals("/usr/bin/osascript", commandExecutor.recordedCommands.single())
        assertEquals(message, args[args.lastIndex - 1])
        assertEquals(title, args.last())
    }

    @Test
    fun executeSurfacesNonZeroExit() = runBlocking {
        val executor = NotifyUserBuiltInToolExecutor(
            FakeCommandExecutor(
                result = CommandExecutionResult(
                    exitCode = 1,
                    stdout = "",
                    stderr = "execution error",
                ),
            ),
        )

        val error = assertFailsWith<IllegalStateException> {
            executor.execute(
                buildJsonObject {
                    put("message", "Build finished")
                },
            )
        }

        assertContains(error.message.orEmpty(), "exit code 1")
        assertContains(error.message.orEmpty(), "execution error")
    }
}

private class FakeCommandExecutor(
    private val result: CommandExecutionResult = CommandExecutionResult(
        exitCode = 0,
        stdout = "Notification sent",
        stderr = "",
    ),
) : CommandExecutor {
    val recordedCommands = mutableListOf<String>()
    val recordedArgs = mutableListOf<List<String>>()

    override fun execute(command: String, args: List<String>): CommandExecutionResult {
        recordedCommands += command
        recordedArgs += args.toList()
        return result
    }
}
