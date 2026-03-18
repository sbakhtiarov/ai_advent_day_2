package com.aichallenge.day2.agent.data.local

import com.aichallenge.day2.agent.domain.model.RagSourceConfig
import com.aichallenge.day2.agent.domain.model.RagSourceType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
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

class JsonFileRagSourceStoreTest {
    @Test
    fun loadParsesExplicitPostgresSourcesInFileOrder() {
        val filePath = uniqueRagConfigFilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(
            filePath,
            """
                {
                  "sources": [
                    {
                      "name": "Local RFC RAG",
                      "type": "postgres",
                      "database_url": "postgresql://raguser:ragpass@localhost:5432/ragdb",
                      "enabled": true
                    },
                    {
                      "name": "Backup RAG",
                      "type": "postgres",
                      "database_url": "postgresql://raguser:ragpass@localhost:5433/ragdb",
                      "enabled": false
                    }
                  ]
                }
            """.trimIndent(),
        )

        val store = JsonFileRagSourceStore(filePath)

        assertEquals(
            listOf(
                postgresSource(
                    name = "Local RFC RAG",
                    databaseUrl = "postgresql://raguser:ragpass@localhost:5432/ragdb",
                    enabled = true,
                ),
                postgresSource(
                    name = "Backup RAG",
                    databaseUrl = "postgresql://raguser:ragpass@localhost:5433/ragdb",
                    enabled = false,
                ),
            ),
            store.load(),
        )
    }

    @Test
    fun loadSkipsInvalidEntries() {
        val filePath = uniqueRagConfigFilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(
            filePath,
            """
                {
                  "sources": [
                    { "name": "Valid", "type": "postgres", "database_url": "postgresql://raguser:ragpass@localhost:5432/ragdb", "enabled": true },
                    { "name": "Blank URL", "type": "postgres", "database_url": "   ", "enabled": true },
                    { "name": "Unknown", "type": "sqlite", "database_url": "file:test.db", "enabled": true },
                    { "name": "   ", "type": "postgres", "database_url": "postgresql://raguser:ragpass@localhost:5432/ragdb", "enabled": true }
                  ]
                }
            """.trimIndent(),
        )

        val store = JsonFileRagSourceStore(filePath)

        assertEquals(
            listOf(
                postgresSource(
                    name = "Valid",
                    databaseUrl = "postgresql://raguser:ragpass@localhost:5432/ragdb",
                    enabled = true,
                ),
            ),
            store.load(),
        )
    }

    @Test
    fun loadAcceptsTrailingCommaInSourcesArray() {
        val filePath = uniqueRagConfigFilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(
            filePath,
            """
                {
                  "sources": [
                    {
                      "name": "Local RFC RAG",
                      "type": "postgres",
                      "database_url": "postgresql://raguser:ragpass@localhost:5432/ragdb",
                      "enabled": true
                    },
                  ]
                }
            """.trimIndent(),
        )

        val store = JsonFileRagSourceStore(filePath)

        assertEquals(
            listOf(
                postgresSource(
                    name = "Local RFC RAG",
                    databaseUrl = "postgresql://raguser:ragpass@localhost:5432/ragdb",
                    enabled = true,
                ),
            ),
            store.load(),
        )
    }

    @Test
    fun loadReturnsEmptyForMalformedJsonAndMissingFile() {
        val malformedFilePath = uniqueRagConfigFilePath()
        ensureDirectoryExists(parentDirectory(malformedFilePath))
        writeTextFile(malformedFilePath, "{ malformed json")

        assertEquals(emptyList(), JsonFileRagSourceStore(malformedFilePath).load())
        assertEquals(emptyList(), JsonFileRagSourceStore(uniqueRagConfigFilePath()).load())
    }

    @Test
    fun saveWritesExplicitTypeAndPreservesOrder() {
        val filePath = uniqueRagConfigFilePath()
        val store = JsonFileRagSourceStore(filePath)

        store.save(
            listOf(
                postgresSource(
                    name = "Local RFC RAG",
                    databaseUrl = "postgresql://raguser:ragpass@localhost:5432/ragdb",
                    enabled = false,
                ),
                postgresSource(
                    name = "Backup RAG",
                    databaseUrl = "postgresql://raguser:ragpass@localhost:5433/ragdb",
                    enabled = true,
                ),
            ),
        )

        val savedText = readTextFile(filePath)
        assertTrue(savedText.contains("\"type\": \"postgres\""))
        assertTrue(savedText.contains("\"database_url\": \"postgresql://raguser:ragpass@localhost:5432/ragdb\""))
        assertEquals(
            listOf(
                postgresSource(
                    name = "Local RFC RAG",
                    databaseUrl = "postgresql://raguser:ragpass@localhost:5432/ragdb",
                    enabled = false,
                ),
                postgresSource(
                    name = "Backup RAG",
                    databaseUrl = "postgresql://raguser:ragpass@localhost:5433/ragdb",
                    enabled = true,
                ),
            ),
            store.load(),
        )
    }
}

private fun postgresSource(name: String, databaseUrl: String, enabled: Boolean): RagSourceConfig {
    return RagSourceConfig(
        name = name,
        enabled = enabled,
        type = RagSourceType.POSTGRES,
        databaseUrl = databaseUrl,
    )
}

private fun uniqueRagConfigFilePath(): String {
    val seed = Random.nextInt().toUInt().toString(16)
    return "/tmp/kotlin-agent-cli-tests/$seed/rag-sources.json"
}

@OptIn(ExperimentalForeignApi::class)
private fun readTextFile(path: String): String {
    val file = fopen(path, "r") ?: error("Unable to open '$path'")
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

@OptIn(ExperimentalForeignApi::class)
private fun writeTextFile(path: String, text: String) {
    val file = fopen(path, "w") ?: error("Unable to open '$path'")
    try {
        if (fputs(text, file) < 0) {
            error("Unable to write '$path'")
        }
    } finally {
        fclose(file)
    }
}

private fun parentDirectory(path: String): String {
    val normalized = path.trimEnd('/')
    val separatorIndex = normalized.lastIndexOf('/')
    require(separatorIndex > 0)
    return normalized.substring(0, separatorIndex)
}

@OptIn(ExperimentalForeignApi::class)
private fun ensureDirectoryExists(path: String) {
    if (path.isBlank() || path == "/") return

    val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
    if (parent.isNotBlank() && parent != path) {
        ensureDirectoryExists(parent)
    }

    val createResult = mkdir(path, 493.convert<mode_t>())
    if (createResult == 0 || errno == EEXIST) {
        return
    }

    error("Unable to create directory '$path'")
}
