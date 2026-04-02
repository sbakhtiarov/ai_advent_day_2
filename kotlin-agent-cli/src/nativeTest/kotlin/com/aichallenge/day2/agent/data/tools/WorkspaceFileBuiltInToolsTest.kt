package com.aichallenge.day2.agent.data.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListFilesBuiltInToolTest {
    @Test
    fun executeListsWorkspaceRootAndPaginates() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot("list-files-root")
        ensureTestDirectoryExists("$workspaceRoot/src")
        ensureTestDirectoryExists("$workspaceRoot/docs")
        writeTestTextFile("$workspaceRoot/README.md", "readme")
        writeTestTextFile("$workspaceRoot/src/App.kt", "fun main() = Unit")
        val executor = ListFilesBuiltInToolExecutor(TestRuntimeEnvironment(workspaceRoot))

        val result = executor.execute(
            buildJsonObject {
                put("limit", 2)
            },
        )

        val entries = result.structuredContent?.get("entries")?.jsonArray ?: error("Missing entries")
        assertEquals(2, entries.size)
        assertEquals(".", result.structuredContent?.get("path")?.jsonPrimitive?.content)
        assertEquals("docs", entries[0].jsonObject["path"]?.jsonPrimitive?.content)
        assertEquals("src", entries[1].jsonObject["path"]?.jsonPrimitive?.content)
        assertEquals("2", result.structuredContent?.get("next_offset")?.jsonPrimitive?.content)
    }

    @Test
    fun executeRejectsFilePath() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot("list-files-file")
        ensureTestDirectoryExists(workspaceRoot)
        writeTestTextFile("$workspaceRoot/file.txt", "content")
        val executor = ListFilesBuiltInToolExecutor(TestRuntimeEnvironment(workspaceRoot))

        val error = assertFailsWith<IllegalArgumentException> {
            executor.execute(
                buildJsonObject {
                    put("path", "file.txt")
                },
            )
        }

        assertContains(error.message.orEmpty(), "directory")
    }
}

class ReadFileBuiltInToolTest {
    @Test
    fun executeReadsPagedLines() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot("read-file-page")
        ensureTestDirectoryExists(workspaceRoot)
        writeTestTextFile("$workspaceRoot/notes.txt", "one\ntwo\nthree\nfour")
        val executor = ReadFileBuiltInToolExecutor(TestRuntimeEnvironment(workspaceRoot))

        val result = executor.execute(
            buildJsonObject {
                put("path", "notes.txt")
                put("start_line", 2)
                put("max_lines", 2)
            },
        )

        val lines = result.structuredContent?.get("lines")?.jsonArray ?: error("Missing lines")
        assertEquals(2, lines.size)
        assertEquals("2", lines[0].jsonObject["line_number"]?.jsonPrimitive?.content)
        assertEquals("two", lines[0].jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals("4", result.structuredContent?.get("next_start_line")?.jsonPrimitive?.content)
    }

    @Test
    fun executeRejectsBinaryFiles() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot("read-file-binary")
        ensureTestDirectoryExists(workspaceRoot)
        writeBinaryFile("$workspaceRoot/data.bin", byteArrayOf(0, 1, 2, 3))
        val executor = ReadFileBuiltInToolExecutor(TestRuntimeEnvironment(workspaceRoot))

        val error = assertFailsWith<IllegalArgumentException> {
            executor.execute(
                buildJsonObject {
                    put("path", "data.bin")
                },
            )
        }

        assertContains(error.message.orEmpty(), "UTF-8")
    }
}

class FindFileByNameBuiltInToolTest {
    @Test
    fun executeFindsMatchingFilesRecursively() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot("find-file")
        ensureTestDirectoryExists("$workspaceRoot/src/main")
        ensureTestDirectoryExists("$workspaceRoot/docs")
        writeTestTextFile("$workspaceRoot/src/main/AppService.kt", "class AppService")
        writeTestTextFile("$workspaceRoot/docs/service-notes.md", "service")
        writeTestTextFile("$workspaceRoot/src/main/AppController.kt", "class AppController")
        val executor = FindFileByNameBuiltInToolExecutor(TestRuntimeEnvironment(workspaceRoot))

        val result = executor.execute(
            buildJsonObject {
                put("query", "service")
            },
        )

        val matches = result.structuredContent?.get("matches")?.jsonArray ?: error("Missing matches")
        assertEquals(2, matches.size)
        assertEquals("docs/service-notes.md", matches[0].jsonPrimitive.content)
        assertEquals("src/main/AppService.kt", matches[1].jsonPrimitive.content)
    }
}

class SearchFileContentBuiltInToolTest {
    @Test
    fun executeFindsContentMatchesWithContextAndPagination() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot("search-content")
        ensureTestDirectoryExists("$workspaceRoot/src")
        writeTestTextFile("$workspaceRoot/src/alpha.txt", "zero\nmatch one\ncontext\nmatch two\nend")
        writeTestTextFile("$workspaceRoot/src/beta.txt", "another match one")
        writeBinaryFile("$workspaceRoot/src/image.bin", byteArrayOf(0, 42, 42))
        val executor = SearchFileContentBuiltInToolExecutor(TestRuntimeEnvironment(workspaceRoot))

        val result = executor.execute(
            buildJsonObject {
                put("query", "match")
                put("path", "src")
                put("limit", 2)
                put("context_lines", 1)
            },
        )

        val matches = result.structuredContent?.get("matches")?.jsonArray ?: error("Missing matches")
        assertEquals(2, matches.size)
        assertEquals("src/alpha.txt", matches[0].jsonObject["path"]?.jsonPrimitive?.content)
        assertEquals("2", matches[0].jsonObject["line_number"]?.jsonPrimitive?.content)
        assertContains(matches[0].jsonObject["snippet"]?.jsonPrimitive?.content.orEmpty(), "1| zero")
        assertEquals("1", result.structuredContent?.get("skipped_file_count")?.jsonPrimitive?.content)
        assertEquals("2", result.structuredContent?.get("next_offset")?.jsonPrimitive?.content)
    }
}

class EditFileBuiltInToolTest {
    @Test
    fun executeReplacesAllOccurrencesAndReturnsDiffPage() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot("edit-file")
        ensureTestDirectoryExists(workspaceRoot)
        writeTestTextFile("$workspaceRoot/story.txt", "hello world\nhello team\nbye")
        val executor = EditFileBuiltInToolExecutor(TestRuntimeEnvironment(workspaceRoot))

        val result = executor.execute(
            buildJsonObject {
                put("path", "story.txt")
                put("find_text", "hello")
                put("replace_text", "hi")
                put("diff_limit", 4)
            },
        )

        assertEquals("hi world\nhi team\nbye", readTestTextFile("$workspaceRoot/story.txt"))
        assertEquals("2", result.structuredContent?.get("replacement_count")?.jsonPrimitive?.content)
        val diffLines = result.structuredContent?.get("diff_lines")?.jsonArray ?: error("Missing diff lines")
        assertEquals(4, diffLines.size)
        assertEquals("--- a/story.txt", diffLines[0].jsonPrimitive.content)
        assertTrue(result.structuredContent?.get("next_diff_offset") != null)
    }

    @Test
    fun executeFailsWhenSearchTextIsMissing() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot("edit-file-miss")
        ensureTestDirectoryExists(workspaceRoot)
        writeTestTextFile("$workspaceRoot/story.txt", "hello world")
        val executor = EditFileBuiltInToolExecutor(TestRuntimeEnvironment(workspaceRoot))

        val error = assertFailsWith<IllegalArgumentException> {
            executor.execute(
                buildJsonObject {
                    put("path", "story.txt")
                    put("find_text", "goodbye")
                    put("replace_text", "hi")
                },
            )
        }

        assertContains(error.message.orEmpty(), "not found")
    }
}

class DeleteFileBuiltInToolTest {
    @Test
    fun executeDeletesExistingFilesAndIgnoresMissingFiles() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot("delete-file")
        ensureTestDirectoryExists(workspaceRoot)
        writeTestTextFile("$workspaceRoot/trash.txt", "bye")
        val executor = DeleteFileBuiltInToolExecutor(TestRuntimeEnvironment(workspaceRoot))

        val deletedResult = executor.execute(
            buildJsonObject {
                put("path", "trash.txt")
            },
        )
        val missingResult = executor.execute(
            buildJsonObject {
                put("path", "trash.txt")
            },
        )

        assertFalse(testFileExists("$workspaceRoot/trash.txt"))
        assertEquals("true", deletedResult.structuredContent?.get("deleted")?.jsonPrimitive?.content)
        assertEquals("false", missingResult.structuredContent?.get("deleted")?.jsonPrimitive?.content)
    }

    @Test
    fun executeRejectsDirectories() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot("delete-dir")
        ensureTestDirectoryExists("$workspaceRoot/dir")
        val executor = DeleteFileBuiltInToolExecutor(TestRuntimeEnvironment(workspaceRoot))

        val error = assertFailsWith<IllegalArgumentException> {
            executor.execute(
                buildJsonObject {
                    put("path", "dir")
                },
            )
        }

        assertContains(error.message.orEmpty(), "directory")
    }
}

class DiffFilesBuiltInToolTest {
    @Test
    fun executeBuildsPaginatedUnifiedDiff() = runBlocking {
        val executor = DiffFilesBuiltInToolExecutor()

        val result = executor.execute(
            buildJsonObject {
                put("before_content", "one\ntwo\nthree")
                put("after_content", "one\n2\nthree\nfour")
                put("path_hint", "story.txt")
                put("diff_limit", 4)
            },
        )

        val diffLines = result.structuredContent?.get("diff_lines")?.jsonArray ?: error("Missing diff lines")
        assertEquals(4, diffLines.size)
        assertEquals("--- a/story.txt", diffLines[0].jsonPrimitive.content)
        assertEquals("true", result.structuredContent?.get("is_different")?.jsonPrimitive?.content)
        assertTrue(result.structuredContent?.get("next_diff_offset") != null)
    }

    @Test
    fun executeReturnsNoDifferencesForIdenticalSnapshots() = runBlocking {
        val executor = DiffFilesBuiltInToolExecutor()

        val result = executor.execute(
            buildJsonObject {
                put("before_content", "same")
                put("after_content", "same")
            },
        )

        assertEquals("false", result.structuredContent?.get("is_different")?.jsonPrimitive?.content)
        assertNull(result.structuredContent?.get("next_diff_offset"))
        assertContains(result.content.first().jsonObject["text"]?.jsonPrimitive?.content.orEmpty(), "No differences")
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun writeBinaryFile(path: String, bytes: ByteArray) {
    val support = WorkspaceFileSupport(TestRuntimeEnvironment(testParentDirectory(path) ?: "/"))
    support.ensureParentDirectoryExists(path)
    val file = platform.posix.fopen(path, "wb")
        ?: error("Unable to open '$path' for binary writing.")
    try {
        bytes.forEach { byte ->
            if (platform.posix.fputc(byte.toInt() and 0xff, file) == platform.posix.EOF) {
                error("Unable to write '$path'.")
            }
        }
    } finally {
        platform.posix.fclose(file)
    }
}
