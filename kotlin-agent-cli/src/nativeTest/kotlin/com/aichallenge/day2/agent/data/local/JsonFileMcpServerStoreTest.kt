package com.aichallenge.day2.agent.data.local

import com.aichallenge.day2.agent.domain.model.McpServerConfig
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.posix.EEXIST
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.mkdir
import platform.posix.mode_t

class JsonFileMcpServerStoreTest {
    @Test
    fun loadParsesValidServersInFileOrder() {
        val filePath = uniqueMcpConfigFilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(
            filePath,
            """
                {
                  "servers": [
                    { "name": "Linear", "url": "http://localhost:3000", "enabled": true },
                    { "name": "GitHub", "url": "http://localhost:3001", "enabled": false }
                  ]
                }
            """.trimIndent(),
        )

        val store = JsonFileMcpServerStore(filePath)

        assertEquals(
            listOf(
                McpServerConfig(name = "Linear", url = "http://localhost:3000", enabled = true),
                McpServerConfig(name = "GitHub", url = "http://localhost:3001", enabled = false),
            ),
            store.load(),
        )
    }

    @Test
    fun loadSkipsEntriesWithBlankNameOrUrlAndReturnsEmptyForMalformedJson() {
        val validFilePath = uniqueMcpConfigFilePath()
        ensureDirectoryExists(parentDirectory(validFilePath))
        writeTextFile(
            validFilePath,
            """
                {
                  "servers": [
                    { "name": "Linear", "url": "http://localhost:3000", "enabled": true },
                    { "name": "   ", "url": "http://localhost:3001", "enabled": true },
                    { "name": "GitHub", "url": "   ", "enabled": false }
                  ]
                }
            """.trimIndent(),
        )
        val malformedFilePath = uniqueMcpConfigFilePath()
        ensureDirectoryExists(parentDirectory(malformedFilePath))
        writeTextFile(
            malformedFilePath,
            "{ malformed json",
        )

        val validStore = JsonFileMcpServerStore(validFilePath)
        val malformedStore = JsonFileMcpServerStore(malformedFilePath)

        assertEquals(
            listOf(
                McpServerConfig(name = "Linear", url = "http://localhost:3000", enabled = true),
            ),
            validStore.load(),
        )
        assertEquals(emptyList(), malformedStore.load())
    }

    @Test
    fun loadReturnsEmptyWhenFileIsMissing() {
        val store = JsonFileMcpServerStore(uniqueMcpConfigFilePath())

        assertEquals(emptyList(), store.load())
    }

    @Test
    fun saveWritesToggledStateBackInSameOrder() {
        val filePath = uniqueMcpConfigFilePath()
        val store = JsonFileMcpServerStore(filePath)

        store.save(
            listOf(
                McpServerConfig(name = "Linear", url = "http://localhost:3000", enabled = false),
                McpServerConfig(name = "GitHub", url = "http://localhost:3001", enabled = true),
            ),
        )

        assertEquals(
            listOf(
                McpServerConfig(name = "Linear", url = "http://localhost:3000", enabled = false),
                McpServerConfig(name = "GitHub", url = "http://localhost:3001", enabled = true),
            ),
            store.load(),
        )
    }
}

private fun uniqueMcpConfigFilePath(): String {
    val seed = Random.nextLong().toString().replace('-', '0')
    return "/tmp/kotlin-agent-cli-tests/$seed/mcp-servers.json"
}

private fun parentDirectory(path: String): String {
    val normalized = path.trimEnd('/')
    val separatorIndex = normalized.lastIndexOf('/')
    return if (separatorIndex <= 0) "/" else normalized.substring(0, separatorIndex)
}

@OptIn(ExperimentalForeignApi::class)
private fun ensureDirectoryExists(path: String) {
    if (path.isBlank() || path == "/") return

    val parent = parentDirectory(path)
    if (parent != path) {
        ensureDirectoryExists(parent)
    }

    val result = mkdir(path, 493.convert<mode_t>())
    if (result == 0 || errno == EEXIST) return
    error("Failed to create test directory '$path'.")
}

@OptIn(ExperimentalForeignApi::class)
private fun writeTextFile(path: String, text: String) {
    val file = fopen(path, "w") ?: error("Unable to open test file '$path'.")
    try {
        if (fputs(text, file) < 0) {
            error("Unable to write test file '$path'.")
        }
    } finally {
        fclose(file)
    }
}
