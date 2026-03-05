package com.aichallenge.day2.agent.data.local

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

class JsonFileInvariantConstraintStoreTest {
    @Test
    fun saveAndLoadRoundTripNormalizesConstraints() {
        val filePath = uniqueInvariantConstraintFilePath()
        val store = JsonFileInvariantConstraintStore(filePath)

        store.save(
            listOf(
                "  Always run tests before finalizing  ",
                "",
                "always run TESTS before finalizing",
                "Keep git history clean",
                "  Keep git history clean  ",
                "Avoid force push",
            ),
        )

        val loaded = store.load()

        assertEquals(
            listOf(
                "Always run tests before finalizing",
                "Keep git history clean",
                "Avoid force push",
            ),
            loaded,
        )
    }

    @Test
    fun loadReturnsEmptyListWhenFileIsMissing() {
        val store = JsonFileInvariantConstraintStore(uniqueInvariantConstraintFilePath())

        assertEquals(emptyList(), store.load())
    }

    @Test
    fun loadReturnsEmptyListWhenFileIsMalformed() {
        val filePath = uniqueInvariantConstraintFilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(filePath, "{ malformed json")
        val store = JsonFileInvariantConstraintStore(filePath)

        assertEquals(emptyList(), store.load())
    }

    @Test
    fun loadReturnsEmptyListWhenVersionIsUnsupported() {
        val filePath = uniqueInvariantConstraintFilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(
            filePath,
            """
                {
                  "version": 999,
                  "constraints": ["A", "B"]
                }
            """.trimIndent(),
        )
        val store = JsonFileInvariantConstraintStore(filePath)

        assertEquals(emptyList(), store.load())
    }
}

private fun uniqueInvariantConstraintFilePath(): String {
    val seed = Random.nextLong().toString().replace('-', '0')
    return "/tmp/kotlin-agent-cli-tests/$seed/invariant-constraints.json"
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
