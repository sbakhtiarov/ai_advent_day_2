package com.aichallenge.day2.agent.data.mcp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import platform.posix.mkdir
import platform.posix.free
import platform.posix.realpath
import platform.posix.rmdir
import platform.posix.usleep

class PosixMcpProcessLauncherTest {
    @Test
    fun launchWiresStdoutAndStderrAndClosesProcess() {
        val process = PosixMcpProcessLauncher().launch(
            command = "/bin/sh",
            args = listOf("-c", "printf 'stderr-line\\n' >&2; cat"),
        )

        try {
            process.stdin.write("ping\n".encodeToByteArray())
            process.stdin.flush()

            assertEquals("ping\n", readUntilContains(process.stdout, "ping\n"))
            assertContains(readUntilContains(process.stderr, "stderr-line\n"), "stderr-line")
        } finally {
            process.close()
        }
    }

    @Test
    fun launchIncludesHelpfulMessageWhenCommandCannotBeExecuted() {
        val failure = runCatching {
            PosixMcpProcessLauncher().launch(
                command = "missing-command-for-mcp-test",
                args = emptyList(),
            )
        }.exceptionOrNull()

        assertContains(failure?.message.orEmpty(), "Unable to launch MCP server 'missing-command-for-mcp-test'")
    }

    @Test
    fun launchRunsChildInConfiguredWorkingDirectory() {
        val tempDirectory = uniqueTempDirectory()
        check(mkdir(tempDirectory, 511u) == 0) {
            "Failed to create temporary directory: $tempDirectory"
        }
        val expectedDirectory = canonicalizePath(tempDirectory)

        val process = PosixMcpProcessLauncher(workingDirectory = tempDirectory).launch(
            command = "/bin/pwd",
            args = emptyList(),
        )

        try {
            assertEquals("$expectedDirectory\n", readUntilContains(process.stdout, "$expectedDirectory\n"))
        } finally {
            process.close()
            rmdir(tempDirectory)
        }
    }
}

private fun readUntilContains(source: kotlinx.io.Source, expected: String): String {
    val buffer = ByteArray(256)
    val text = StringBuilder()
    repeat(20) {
        val readCount = source.readAtMostTo(buffer)
        if (readCount > 0) {
            text.append(buffer.decodeToString(endIndex = readCount))
            if (text.contains(expected)) {
                return text.toString()
            }
        }
        usleep(50_000u)
    }
    return text.toString()
}

private fun uniqueTempDirectory(): String {
    return "/tmp/kotlin-agent-cli-mcp-launcher-${kotlin.random.Random.nextInt(1_000_000)}"
}

@OptIn(ExperimentalForeignApi::class)
private fun canonicalizePath(path: String): String {
    val resolved = realpath(path, null) ?: return path
    return try {
        resolved.toKString()
    } finally {
        free(resolved)
    }
}
