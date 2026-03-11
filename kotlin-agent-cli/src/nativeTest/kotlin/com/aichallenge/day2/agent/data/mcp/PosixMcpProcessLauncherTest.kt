package com.aichallenge.day2.agent.data.mcp

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
