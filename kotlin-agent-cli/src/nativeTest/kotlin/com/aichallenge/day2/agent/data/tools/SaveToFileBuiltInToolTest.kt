package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.core.config.AppRuntimeEnvironment
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.posix.EEXIST
import platform.posix.F_OK
import platform.posix.access
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.mkdir
import platform.posix.mode_t
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SaveToFileBuiltInToolTest {
    @Test
    fun executeCreatesNewFileWithProvidedContent() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot()
        ensureDirectoryExists(workspaceRoot)
        val executor = SaveToFileBuiltInToolExecutor(
            runtimeEnvironment = TestRuntimeEnvironment(workspaceRoot),
        )
        val content = "line one\nline two"

        val result = executor.execute(
            buildJsonObject {
                put("file_name", "notes/todo.txt")
                put("content", content)
            },
        )

        val absolutePath = "$workspaceRoot/notes/todo.txt"
        assertEquals(content, readTextFile(absolutePath))
        assertEquals("notes/todo.txt", result.structuredContent?.get("file_name")?.jsonPrimitive?.content)
        assertEquals(absolutePath, result.structuredContent?.get("absolute_path")?.jsonPrimitive?.content)
        assertEquals("false", result.structuredContent?.get("overwritten")?.jsonPrimitive?.content)
        assertEquals(content.encodeToByteArray().size.toString(), result.structuredContent?.get("bytes_written")?.jsonPrimitive?.content)
        assertEquals(workspaceRoot, result.structuredContent?.get("workspace_root")?.jsonPrimitive?.content)
    }

    @Test
    fun executeAllowsEmptyContentAndCreatesEmptyFile() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot()
        ensureDirectoryExists(workspaceRoot)
        val executor = SaveToFileBuiltInToolExecutor(
            runtimeEnvironment = TestRuntimeEnvironment(workspaceRoot),
        )

        val result = executor.execute(
            buildJsonObject {
                put("file_name", "empty.txt")
                put("content", "")
            },
        )

        val absolutePath = "$workspaceRoot/empty.txt"
        assertEquals("", readTextFile(absolutePath))
        assertEquals("0", result.structuredContent?.get("bytes_written")?.jsonPrimitive?.content)
    }

    @Test
    fun executeCreatesMissingParentDirectories() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot()
        ensureDirectoryExists(workspaceRoot)
        val executor = SaveToFileBuiltInToolExecutor(
            runtimeEnvironment = TestRuntimeEnvironment(workspaceRoot),
        )

        executor.execute(
            buildJsonObject {
                put("file_name", "deep/nested/path/output.txt")
                put("content", "done")
            },
        )

        assertTrue(fileExists("$workspaceRoot/deep/nested/path/output.txt"))
    }

    @Test
    fun executeFailsWhenFileExistsAndOverwriteIsFalse() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot()
        ensureDirectoryExists(workspaceRoot)
        writeTextFile("$workspaceRoot/existing.txt", "old")
        val executor = SaveToFileBuiltInToolExecutor(
            runtimeEnvironment = TestRuntimeEnvironment(workspaceRoot),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            executor.execute(
                buildJsonObject {
                    put("file_name", "existing.txt")
                    put("content", "new")
                },
            )
        }

        assertContains(error.message.orEmpty(), "already exists")
        assertEquals("old", readTextFile("$workspaceRoot/existing.txt"))
    }

    @Test
    fun executeOverwritesWhenOverwriteTrue() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot()
        ensureDirectoryExists(workspaceRoot)
        writeTextFile("$workspaceRoot/existing.txt", "old")
        val executor = SaveToFileBuiltInToolExecutor(
            runtimeEnvironment = TestRuntimeEnvironment(workspaceRoot),
        )

        val result = executor.execute(
            buildJsonObject {
                put("file_name", "existing.txt")
                put("content", "new")
                put("overwrite", true)
            },
        )

        assertEquals("new", readTextFile("$workspaceRoot/existing.txt"))
        assertEquals("true", result.structuredContent?.get("overwritten")?.jsonPrimitive?.content)
    }

    @Test
    fun executeRejectsBlankFileName() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot()
        ensureDirectoryExists(workspaceRoot)
        val executor = SaveToFileBuiltInToolExecutor(
            runtimeEnvironment = TestRuntimeEnvironment(workspaceRoot),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            executor.execute(
                buildJsonObject {
                    put("file_name", "   ")
                    put("content", "test")
                },
            )
        }

        assertContains(error.message.orEmpty(), "file_name")
    }

    @Test
    fun executeRejectsPathsOutsideWorkspace() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot()
        ensureDirectoryExists(workspaceRoot)
        val executor = SaveToFileBuiltInToolExecutor(
            runtimeEnvironment = TestRuntimeEnvironment(workspaceRoot),
        )
        val absoluteOutsidePath = "/tmp/save-to-file-outside-${Random.nextLong().toString().replace('-', '0')}.txt"

        val traversalError = assertFailsWith<IllegalArgumentException> {
            executor.execute(
                buildJsonObject {
                    put("file_name", "../outside.txt")
                    put("content", "test")
                },
            )
        }
        val absoluteError = assertFailsWith<IllegalArgumentException> {
            executor.execute(
                buildJsonObject {
                    put("file_name", absoluteOutsidePath)
                    put("content", "test")
                },
            )
        }

        assertContains(traversalError.message.orEmpty(), "inside workspace")
        assertContains(absoluteError.message.orEmpty(), "inside workspace")
        assertFalse(fileExists(absoluteOutsidePath))
    }
}

private class TestRuntimeEnvironment(
    private val currentWorkingDirectory: String,
) : AppRuntimeEnvironment {
    override fun homeDirectory(): String? = "/tmp"
    override fun currentWorkingDirectory(): String = currentWorkingDirectory
    override fun currentExecutablePath(): String? = "/tmp/agent-cli.kexe"
    override fun pathEnvironment(): String? = ""
    override fun timeZoneId(): String? = "UTC"
    override fun changeWorkingDirectory(path: String) = Unit
}

private fun uniqueWorkspaceRoot(): String {
    val seed = Random.nextLong().toString().replace('-', '0')
    return "/tmp/kotlin-agent-cli-tests/$seed/save-to-file-workspace"
}

@OptIn(ExperimentalForeignApi::class)
private fun ensureDirectoryExists(path: String) {
    if (path.isBlank() || path == "/") return

    val parent = parentDirectory(path)
    if (parent != null && parent != path) {
        ensureDirectoryExists(parent)
    }

    val createResult = mkdir(path, DIRECTORY_MODE.convert<mode_t>())
    if (createResult == 0 || errno == EEXIST) {
        return
    }

    error("Unable to create directory '$path'.")
}

private fun parentDirectory(path: String): String? {
    if (path.isBlank() || path == "/") return null
    val normalized = path.trimEnd('/')
    val separatorIndex = normalized.lastIndexOf('/')
    if (separatorIndex < 0) return null
    return if (separatorIndex == 0) "/" else normalized.substring(0, separatorIndex)
}

@OptIn(ExperimentalForeignApi::class)
private fun writeTextFile(path: String, text: String) {
    ensureDirectoryExists(parentDirectory(path) ?: "/")
    val file = fopen(path, "w")
        ?: error("Unable to open '$path' for writing.")
    try {
        if (fputs(text, file) < 0) {
            error("Unable to write '$path'.")
        }
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readTextFile(path: String): String {
    val file = fopen(path, "r")
        ?: error("Unable to open '$path' for reading.")
    return try {
        buildString {
            memScoped {
                val buffer = allocArray<ByteVar>(READ_BUFFER_SIZE)
                while (fgets(buffer, READ_BUFFER_SIZE, file) != null) {
                    append(buffer.toKString())
                }
            }
        }
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun fileExists(path: String): Boolean {
    return access(path, F_OK.convert()) == 0
}

private const val DIRECTORY_MODE = 493 // 0755
private const val READ_BUFFER_SIZE = 4096
