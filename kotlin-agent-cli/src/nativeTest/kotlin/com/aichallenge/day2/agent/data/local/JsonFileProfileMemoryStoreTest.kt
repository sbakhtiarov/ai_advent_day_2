package com.aichallenge.day2.agent.data.local

import com.aichallenge.day2.agent.domain.model.ProfileEnvironmentFacts
import com.aichallenge.day2.agent.domain.model.ProfileMemoryState
import com.aichallenge.day2.agent.domain.model.ProfilePreferenceState
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

class JsonFileProfileMemoryStoreTest {
    @Test
    fun saveAndLoadRoundTripPreservesState() {
        val filePath = uniqueProfileMemoryPath()
        val store = JsonFileProfileMemoryStore(filePath)
        val state = ProfileMemoryState(
            preferences = ProfilePreferenceState(
                writingStyle = "concise bullets",
                toolingPreferences = listOf("use rg", "prefer TypeScript"),
                workflowDefaults = listOf("always run tests before finalizing"),
                stableConstraints = listOf("avoid destructive git commands"),
            ),
            environmentFacts = ProfileEnvironmentFacts(
                timezone = "Europe/Berlin",
                os = "MACOS",
                repoPath = "/tmp/repo",
            ),
        )

        store.save(state)

        assertEquals(state, store.load())
    }

    @Test
    fun loadReturnsNullWhenFileDoesNotExist() {
        val filePath = uniqueProfileMemoryPath()
        val store = JsonFileProfileMemoryStore(filePath)

        assertEquals(null, store.load())
    }

    @Test
    fun loadReturnsNullWhenJsonIsMalformed() {
        val filePath = uniqueProfileMemoryPath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(filePath, "{ malformed json")
        val store = JsonFileProfileMemoryStore(filePath)

        assertEquals(null, store.load())
    }

    @Test
    fun clearDeletesSavedSnapshotAndIsIdempotent() {
        val filePath = uniqueProfileMemoryPath()
        val store = JsonFileProfileMemoryStore(filePath)
        val state = ProfileMemoryState(
            preferences = ProfilePreferenceState(
                writingStyle = "concise",
            ),
            environmentFacts = ProfileEnvironmentFacts(
                timezone = "unknown",
                os = "MACOS",
                repoPath = "/tmp/repo",
            ),
        )
        store.save(state)

        store.clear()
        store.clear()

        assertEquals(null, store.load())
    }
}

private fun uniqueProfileMemoryPath(): String {
    val seed = Random.nextLong().toString().replace('-', '0')
    return "/tmp/kotlin-agent-cli-tests/$seed/profile-memory.json"
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
