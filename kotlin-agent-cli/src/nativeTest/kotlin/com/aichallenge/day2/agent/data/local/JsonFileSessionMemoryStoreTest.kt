package com.aichallenge.day2.agent.data.local

import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.MemoryEstimateSource
import com.aichallenge.day2.agent.domain.model.MemoryUsageSnapshot
import com.aichallenge.day2.agent.domain.model.SessionMemoryState
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

class JsonFileSessionMemoryStoreTest {
    @Test
    fun saveAndLoadRoundTripPreservesState() {
        val filePath = uniqueSessionMemoryPath()
        val store = JsonFileSessionMemoryStore(filePath)
        val messages = listOf(
            ConversationMessage.system("system"),
            ConversationMessage.user("question"),
            ConversationMessage.assistant("answer"),
            ConversationMessage.user("question 2"),
            ConversationMessage.assistant("answer 2"),
        )
        val state = SessionMemoryState(
            messages = messages,
            usage = MemoryUsageSnapshot(
                estimatedTokens = 123,
                source = MemoryEstimateSource.HYBRID,
                messageCount = messages.size,
            ),
        )

        store.save(state)

        assertEquals(state, store.load())
    }

    @Test
    fun loadReturnsNullWhenFileDoesNotExist() {
        val filePath = uniqueSessionMemoryPath()
        val store = JsonFileSessionMemoryStore(filePath)

        assertEquals(null, store.load())
    }

    @Test
    fun loadReturnsNullWhenJsonIsMalformed() {
        val filePath = uniqueSessionMemoryPath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(filePath, "{ malformed json")
        val store = JsonFileSessionMemoryStore(filePath)

        assertEquals(null, store.load())
    }

    @Test
    fun clearDeletesSavedSnapshotAndIsIdempotent() {
        val filePath = uniqueSessionMemoryPath()
        val store = JsonFileSessionMemoryStore(filePath)
        val messages = listOf(
            ConversationMessage.system("system"),
            ConversationMessage.user("question"),
            ConversationMessage.assistant("answer"),
        )
        val state = SessionMemoryState(messages = messages, usage = null)
        store.save(state)

        store.clear()
        store.clear()

        assertEquals(null, store.load())
    }

    @Test
    fun loadLegacySnapshotWithoutMemoryUsage() {
        val filePath = uniqueSessionMemoryPath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(
            filePath,
            """
            {
              "version": 1,
              "messages": [
                {"role":"SYSTEM","content":"system"},
                {"role":"USER","content":"question"},
                {"role":"ASSISTANT","content":"answer"}
              ]
            }
            """.trimIndent(),
        )
        val store = JsonFileSessionMemoryStore(filePath)

        assertEquals(
            SessionMemoryState(
                messages = listOf(
                    ConversationMessage.system("system"),
                    ConversationMessage.user("question"),
                    ConversationMessage.assistant("answer"),
                ),
                usage = null,
            ),
            store.load(),
        )
    }
}

private fun uniqueSessionMemoryPath(): String {
    val seed = Random.nextLong().toString().replace('-', '0')
    return "/tmp/kotlin-agent-cli-tests/$seed/session-memory.json"
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
