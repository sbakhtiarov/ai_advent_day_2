package com.aichallenge.day2.agent.data.local

import com.aichallenge.day2.agent.domain.model.McpServerConfig
import com.aichallenge.day2.agent.domain.model.McpTransportConfig
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.EEXIST
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.mkdir
import platform.posix.mode_t

class JsonFileMcpServerStoreTest {
    @Test
    fun loadParsesExplicitHttpAndStdioServersInFileOrder() {
        val filePath = uniqueMcpConfigFilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(
            filePath,
            """
                {
                  "servers": [
                    {
                      "name": "Linear",
                      "type": "http",
                      "url": "http://localhost:3000",
                      "enabled": true,
                      "public": true
                    },
                    {
                      "name": "Local MCP",
                      "type": "stdio",
                      "command": "node",
                      "args": ["/tmp/server.js"],
                      "enabled": false,
                      "public": false
                    }
                  ]
                }
            """.trimIndent(),
        )

        val store = JsonFileMcpServerStore(filePath)

        assertEquals(
            listOf(
                httpServer(name = "Linear", url = "http://localhost:3000", enabled = true, isPublic = true),
                stdioServer(name = "Local MCP", command = "node", args = listOf("/tmp/server.js"), enabled = false),
            ),
            store.load(),
        )
    }

    @Test
    fun loadDefaultsPublicToFalseWhenFieldMissing() {
        val filePath = uniqueMcpConfigFilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(
            filePath,
            """
                {
                  "servers": [
                    { "name": "Weather", "type": "http", "url": "https://weather.chukai.io/mcp", "enabled": true }
                  ]
                }
            """.trimIndent(),
        )

        val store = JsonFileMcpServerStore(filePath)

        assertEquals(
            listOf(
                httpServer(name = "Weather", url = "https://weather.chukai.io/mcp", enabled = true, isPublic = false),
            ),
            store.load(),
        )
    }

    @Test
    fun loadSupportsLegacyHttpEntriesAndSkipsInvalidEntries() {
        val filePath = uniqueMcpConfigFilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(
            filePath,
            """
                {
                  "servers": [
                    { "name": "Legacy", "url": "http://localhost:3000", "enabled": true },
                    { "name": "Blank URL", "type": "http", "url": "   ", "enabled": true },
                    { "name": "Blank command", "type": "stdio", "command": "   ", "args": [], "enabled": true },
                    { "name": "Blank arg", "type": "stdio", "command": "node", "args": ["   "], "enabled": true },
                    { "name": "Unknown", "type": "socket", "enabled": true }
                  ]
                }
            """.trimIndent(),
        )

        val store = JsonFileMcpServerStore(filePath)

        assertEquals(
            listOf(
                httpServer(name = "Legacy", url = "http://localhost:3000", enabled = true),
            ),
            store.load(),
        )
    }

    @Test
    fun loadAcceptsTrailingCommaInServersArray() {
        val filePath = uniqueMcpConfigFilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(
            filePath,
            """
                {
                  "servers": [
                    { "name": "Weather", "type": "http", "url": "https://weather.chukai.io/mcp", "enabled": true },
                    {
                      "name": "Google Drive",
                      "type": "stdio",
                      "command": "node",
                      "args": ["/tmp/server.js"],
                      "enabled": true
                    },
                  ]
                }
            """.trimIndent(),
        )

        val store = JsonFileMcpServerStore(filePath)

        assertEquals(
            listOf(
                httpServer(name = "Weather", url = "https://weather.chukai.io/mcp", enabled = true),
                stdioServer(name = "Google Drive", command = "node", args = listOf("/tmp/server.js"), enabled = true),
            ),
            store.load(),
        )
    }

    @Test
    fun loadReturnsEmptyForMalformedJsonAndMissingFile() {
        val malformedFilePath = uniqueMcpConfigFilePath()
        ensureDirectoryExists(parentDirectory(malformedFilePath))
        writeTextFile(malformedFilePath, "{ malformed json")

        assertEquals(emptyList(), JsonFileMcpServerStore(malformedFilePath).load())
        assertEquals(emptyList(), JsonFileMcpServerStore(uniqueMcpConfigFilePath()).load())
    }

    @Test
    fun saveWritesExplicitTransportTypesAndPreservesOrder() {
        val filePath = uniqueMcpConfigFilePath()
        val store = JsonFileMcpServerStore(filePath)

        store.save(
            listOf(
                httpServer(name = "Linear", url = "http://localhost:3000", enabled = false),
                stdioServer(name = "Local MCP", command = "node", args = listOf("/tmp/server.js"), enabled = true, isPublic = true),
            ),
        )

        val savedText = readTextFile(filePath)
        assertTrue(savedText.contains("\"type\": \"http\""))
        assertTrue(savedText.contains("\"type\": \"stdio\""))
        assertTrue(savedText.contains("\"public\": false"))
        assertTrue(savedText.contains("\"public\": true"))
        assertEquals(
            listOf(
                httpServer(name = "Linear", url = "http://localhost:3000", enabled = false),
                stdioServer(name = "Local MCP", command = "node", args = listOf("/tmp/server.js"), enabled = true, isPublic = true),
            ),
            store.load(),
        )
    }

    @Test
    fun saveRewritesLegacyLoadedEntriesIntoExplicitTypeFormat() {
        val filePath = uniqueMcpConfigFilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(
            filePath,
            """
                {
                  "servers": [
                    { "name": "Legacy", "url": "http://localhost:3000", "enabled": true }
                  ]
                }
            """.trimIndent(),
        )

        val store = JsonFileMcpServerStore(filePath)
        val loadedServers = store.load()

        store.save(loadedServers)

        assertTrue(readTextFile(filePath).contains("\"type\": \"http\""))
        assertTrue(readTextFile(filePath).contains("\"public\": false"))
        assertEquals(
            listOf(httpServer(name = "Legacy", url = "http://localhost:3000", enabled = true, isPublic = false)),
            store.load(),
        )
    }
}

private fun httpServer(name: String, url: String, enabled: Boolean, isPublic: Boolean = false): McpServerConfig {
    return McpServerConfig(
        name = name,
        enabled = enabled,
        isPublic = isPublic,
        transport = McpTransportConfig.Http(url = url),
    )
}

private fun stdioServer(
    name: String,
    command: String,
    args: List<String>,
    enabled: Boolean,
    isPublic: Boolean = false,
): McpServerConfig {
    return McpServerConfig(
        name = name,
        enabled = enabled,
        isPublic = isPublic,
        transport = McpTransportConfig.Stdio(
            command = command,
            args = args,
        ),
    )
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

@OptIn(ExperimentalForeignApi::class)
private fun readTextFile(path: String): String {
    val file = fopen(path, "r") ?: error("Unable to open test file '$path'.")
    return try {
        buildString {
            memScoped {
                val buffer = allocArray<ByteVar>(4096)
                while (fgets(buffer, 4096, file) != null) {
                    append(buffer.toKString())
                }
            }
        }
    } finally {
        fclose(file)
    }
}
