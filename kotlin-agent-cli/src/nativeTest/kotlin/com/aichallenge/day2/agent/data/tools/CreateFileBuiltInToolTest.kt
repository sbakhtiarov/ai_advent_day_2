package com.aichallenge.day2.agent.data.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreateFileBuiltInToolTest {
    @Test
    fun executeCreatesNewFileWithProvidedContent() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot("create-file-workspace")
        ensureTestDirectoryExists(workspaceRoot)
        val executor = CreateFileBuiltInToolExecutor(
            runtimeEnvironment = TestRuntimeEnvironment(workspaceRoot),
        )
        val content = "line one\nline two"

        val result = executor.execute(
            buildJsonObject {
                put("path", "notes/todo.txt")
                put("content", content)
            },
        )

        val absolutePath = "$workspaceRoot/notes/todo.txt"
        assertEquals(content, readTestTextFile(absolutePath))
        assertEquals("notes/todo.txt", result.structuredContent?.get("path")?.jsonPrimitive?.content)
        assertEquals(absolutePath, result.structuredContent?.get("absolute_path")?.jsonPrimitive?.content)
        assertEquals("false", result.structuredContent?.get("overwritten")?.jsonPrimitive?.content)
        assertEquals(content.encodeToByteArray().size.toString(), result.structuredContent?.get("bytes_written")?.jsonPrimitive?.content)
        assertEquals(workspaceRoot, result.structuredContent?.get("workspace_root")?.jsonPrimitive?.content)
    }

    @Test
    fun executeAllowsEmptyContentAndCreatesEmptyFile() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot("create-file-empty")
        ensureTestDirectoryExists(workspaceRoot)
        val executor = CreateFileBuiltInToolExecutor(
            runtimeEnvironment = TestRuntimeEnvironment(workspaceRoot),
        )

        val result = executor.execute(
            buildJsonObject {
                put("path", "empty.txt")
                put("content", "")
            },
        )

        val absolutePath = "$workspaceRoot/empty.txt"
        assertEquals("", readTestTextFile(absolutePath))
        assertEquals("0", result.structuredContent?.get("bytes_written")?.jsonPrimitive?.content)
    }

    @Test
    fun executeCreatesMissingParentDirectories() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot("create-file-dirs")
        ensureTestDirectoryExists(workspaceRoot)
        val executor = CreateFileBuiltInToolExecutor(
            runtimeEnvironment = TestRuntimeEnvironment(workspaceRoot),
        )

        executor.execute(
            buildJsonObject {
                put("path", "deep/nested/path/output.txt")
                put("content", "done")
            },
        )

        assertTrue(testFileExists("$workspaceRoot/deep/nested/path/output.txt"))
    }

    @Test
    fun executeFailsWhenFileExistsAndOverwriteIsFalse() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot("create-file-overwrite-false")
        ensureTestDirectoryExists(workspaceRoot)
        writeTestTextFile("$workspaceRoot/existing.txt", "old")
        val executor = CreateFileBuiltInToolExecutor(
            runtimeEnvironment = TestRuntimeEnvironment(workspaceRoot),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            executor.execute(
                buildJsonObject {
                    put("path", "existing.txt")
                    put("content", "new")
                },
            )
        }

        assertContains(error.message.orEmpty(), "already exists")
        assertEquals("old", readTestTextFile("$workspaceRoot/existing.txt"))
    }

    @Test
    fun executeOverwritesWhenOverwriteTrue() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot("create-file-overwrite-true")
        ensureTestDirectoryExists(workspaceRoot)
        writeTestTextFile("$workspaceRoot/existing.txt", "old")
        val executor = CreateFileBuiltInToolExecutor(
            runtimeEnvironment = TestRuntimeEnvironment(workspaceRoot),
        )

        val result = executor.execute(
            buildJsonObject {
                put("path", "existing.txt")
                put("content", "new")
                put("overwrite", true)
            },
        )

        assertEquals("new", readTestTextFile("$workspaceRoot/existing.txt"))
        assertEquals("true", result.structuredContent?.get("overwritten")?.jsonPrimitive?.content)
    }

    @Test
    fun executeRejectsBlankPath() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot("create-file-blank")
        ensureTestDirectoryExists(workspaceRoot)
        val executor = CreateFileBuiltInToolExecutor(
            runtimeEnvironment = TestRuntimeEnvironment(workspaceRoot),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            executor.execute(
                buildJsonObject {
                    put("path", "   ")
                    put("content", "test")
                },
            )
        }

        assertContains(error.message.orEmpty(), "path")
    }

    @Test
    fun executeRejectsPathsOutsideWorkspace() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot("create-file-outside")
        ensureTestDirectoryExists(workspaceRoot)
        val executor = CreateFileBuiltInToolExecutor(
            runtimeEnvironment = TestRuntimeEnvironment(workspaceRoot),
        )
        val absoluteOutsidePath = "/tmp/create-file-outside-${Random.nextLong().toString().replace('-', '0')}.txt"

        val traversalError = assertFailsWith<IllegalArgumentException> {
            executor.execute(
                buildJsonObject {
                    put("path", "../outside.txt")
                    put("content", "test")
                },
            )
        }
        val absoluteError = assertFailsWith<IllegalArgumentException> {
            executor.execute(
                buildJsonObject {
                    put("path", absoluteOutsidePath)
                    put("content", "test")
                },
            )
        }

        assertContains(traversalError.message.orEmpty(), "inside workspace")
        assertContains(absoluteError.message.orEmpty(), "inside workspace")
        assertFalse(testFileExists(absoluteOutsidePath))
    }
}
