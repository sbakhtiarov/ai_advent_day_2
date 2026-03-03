package com.aichallenge.day2.agent.data.local

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

class JsonFileUserDefinedProfileStoreTest {
    @Test
    fun loadParsesPartialJsonAndKeepsDefaultsForMissingKeys() {
        val filePath = uniqueUserDefinedProfilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(
            path = filePath,
            text = """
                {
                  "writingStyle": "concise bullets",
                  "toolingPreferences": ["use rg", "use rg"],
                  "name": "Alex",
                  "unknown": "ignored"
                }
            """.trimIndent(),
        )
        val store = JsonFileUserDefinedProfileStore(filePath)

        val loaded = store.load()

        assertEquals(
            ProfilePreferenceState(
                writingStyle = "concise bullets",
                toolingPreferences = listOf("use rg", "use rg"),
                workflowDefaults = emptyList(),
                stableConstraints = emptyList(),
                name = "Alex",
                work = "",
                profession = "",
                otherFacts = emptyList(),
            ),
            loaded,
        )
    }

    @Test
    fun loadReturnsNullWhenFileDoesNotExist() {
        val filePath = uniqueUserDefinedProfilePath()
        val store = JsonFileUserDefinedProfileStore(filePath)

        assertEquals(null, store.load())
    }

    @Test
    fun loadReturnsNullWhenJsonIsMalformed() {
        val filePath = uniqueUserDefinedProfilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(filePath, "{ malformed json")
        val store = JsonFileUserDefinedProfileStore(filePath)

        assertEquals(null, store.load())
    }
}

private fun uniqueUserDefinedProfilePath(): String {
    val seed = Random.nextLong().toString().replace('-', '0')
    return "/tmp/kotlin-agent-cli-tests/$seed/user-profile-default.json"
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
