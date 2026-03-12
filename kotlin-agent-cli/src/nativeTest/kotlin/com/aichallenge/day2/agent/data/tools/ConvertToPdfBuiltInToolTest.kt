package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.core.config.AppRuntimeEnvironment
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.posix.EEXIST
import platform.posix.F_OK
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.access
import platform.posix.close
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.mkdir
import platform.posix.mode_t
import platform.posix.open
import platform.posix.read
import platform.posix.write
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConvertToPdfBuiltInToolTest {
    @Test
    fun executeConvertsMarkdownFileToPdf() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot()
        ensureDirectoryExists(workspaceRoot)
        writeTextFile(
            path = "$workspaceRoot/docs/spec.md",
            text =
                """
                # Title

                Some paragraph.
                - one
                - two
                """.trimIndent(),
        )
        val fakeCommandExecutor = PdfToolFakeCommandExecutor { _, args ->
            writeBytesFile(args[3], "%PDF-1.4\n%EOF\n".encodeToByteArray())
            CommandExecutionResult(exitCode = 0, stdout = "", stderr = "")
        }
        val executor = ConvertToPdfBuiltInToolExecutor(
            commandExecutor = fakeCommandExecutor,
            runtimeEnvironment = PdfToolTestRuntimeEnvironment(workspaceRoot),
        )

        val result = executor.execute(
            buildJsonObject {
                put("input_file", "docs/spec.md")
                put("output_file", "build/spec.pdf")
            },
        )

        assertEquals(1, fakeCommandExecutor.calls.size)
        assertEquals("markdown", fakeCommandExecutor.calls.single().args[4])
        assertTrue(fileExists("$workspaceRoot/build/spec.pdf"))
        assertEquals("docs/spec.md", result.structuredContent?.get("input_file")?.jsonPrimitive?.content)
        assertEquals("build/spec.pdf", result.structuredContent?.get("output_file")?.jsonPrimitive?.content)
        assertEquals("python_reportlab", result.structuredContent?.get("backend")?.jsonPrimitive?.content)
        assertEquals("false", result.structuredContent?.get("overwritten")?.jsonPrimitive?.content)
    }

    @Test
    fun executeConvertsNonMarkdownFileAsPlainText() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot()
        ensureDirectoryExists(workspaceRoot)
        writeTextFile("$workspaceRoot/logs/app.log", "hello\nworld")
        val fakeCommandExecutor = PdfToolFakeCommandExecutor { _, args ->
            writeBytesFile(args[3], "%PDF-1.4\n%EOF\n".encodeToByteArray())
            CommandExecutionResult(exitCode = 0, stdout = "", stderr = "")
        }
        val executor = ConvertToPdfBuiltInToolExecutor(
            commandExecutor = fakeCommandExecutor,
            runtimeEnvironment = PdfToolTestRuntimeEnvironment(workspaceRoot),
        )

        executor.execute(
            buildJsonObject {
                put("input_file", "logs/app.log")
                put("output_file", "logs/app.pdf")
            },
        )

        assertEquals("text", fakeCommandExecutor.calls.single().args[4])
    }

    @Test
    fun executeRejectsInputPathOutsideWorkspace() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot()
        ensureDirectoryExists(workspaceRoot)
        val fakeCommandExecutor = PdfToolFakeCommandExecutor { _, _ ->
            CommandExecutionResult(exitCode = 0, stdout = "", stderr = "")
        }
        val executor = ConvertToPdfBuiltInToolExecutor(
            commandExecutor = fakeCommandExecutor,
            runtimeEnvironment = PdfToolTestRuntimeEnvironment(workspaceRoot),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            executor.execute(
                buildJsonObject {
                    put("input_file", "../outside.txt")
                    put("output_file", "out.pdf")
                },
            )
        }

        assertContains(error.message.orEmpty(), "inside workspace")
        assertEquals(0, fakeCommandExecutor.calls.size)
    }

    @Test
    fun executeRejectsOutputPathOutsideWorkspace() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot()
        ensureDirectoryExists(workspaceRoot)
        writeTextFile("$workspaceRoot/input.txt", "content")
        val fakeCommandExecutor = PdfToolFakeCommandExecutor { _, _ ->
            CommandExecutionResult(exitCode = 0, stdout = "", stderr = "")
        }
        val executor = ConvertToPdfBuiltInToolExecutor(
            commandExecutor = fakeCommandExecutor,
            runtimeEnvironment = PdfToolTestRuntimeEnvironment(workspaceRoot),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            executor.execute(
                buildJsonObject {
                    put("input_file", "input.txt")
                    put("output_file", "../outside.pdf")
                },
            )
        }

        assertContains(error.message.orEmpty(), "inside workspace")
        assertEquals(0, fakeCommandExecutor.calls.size)
    }

    @Test
    fun executeFailsWhenOutputExistsAndOverwriteIsFalse() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot()
        ensureDirectoryExists(workspaceRoot)
        writeTextFile("$workspaceRoot/input.txt", "content")
        writeBytesFile("$workspaceRoot/out.pdf", "existing".encodeToByteArray())
        val fakeCommandExecutor = PdfToolFakeCommandExecutor { _, _ ->
            CommandExecutionResult(exitCode = 0, stdout = "", stderr = "")
        }
        val executor = ConvertToPdfBuiltInToolExecutor(
            commandExecutor = fakeCommandExecutor,
            runtimeEnvironment = PdfToolTestRuntimeEnvironment(workspaceRoot),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            executor.execute(
                buildJsonObject {
                    put("input_file", "input.txt")
                    put("output_file", "out.pdf")
                },
            )
        }

        assertContains(error.message.orEmpty(), "already exists")
        assertEquals(0, fakeCommandExecutor.calls.size)
    }

    @Test
    fun executeOverwritesOutputWhenOverwriteTrue() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot()
        ensureDirectoryExists(workspaceRoot)
        writeTextFile("$workspaceRoot/input.txt", "content")
        writeBytesFile("$workspaceRoot/out.pdf", "old".encodeToByteArray())
        val fakeCommandExecutor = PdfToolFakeCommandExecutor { _, args ->
            writeBytesFile(args[3], "%PDF-1.4\nnew\n".encodeToByteArray())
            CommandExecutionResult(exitCode = 0, stdout = "", stderr = "")
        }
        val executor = ConvertToPdfBuiltInToolExecutor(
            commandExecutor = fakeCommandExecutor,
            runtimeEnvironment = PdfToolTestRuntimeEnvironment(workspaceRoot),
        )

        val result = executor.execute(
            buildJsonObject {
                put("input_file", "input.txt")
                put("output_file", "out.pdf")
                put("overwrite", true)
            },
        )

        assertEquals(1, fakeCommandExecutor.calls.size)
        assertTrue(readTextFile("$workspaceRoot/out.pdf").contains("%PDF-1.4"))
        assertEquals("true", result.structuredContent?.get("overwritten")?.jsonPrimitive?.content)
    }

    @Test
    fun executeFailsWithInstallHintWhenReportLabMissing() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot()
        ensureDirectoryExists(workspaceRoot)
        writeTextFile("$workspaceRoot/input.txt", "content")
        val fakeCommandExecutor = PdfToolFakeCommandExecutor { _, _ ->
            CommandExecutionResult(
                exitCode = 3,
                stdout = "",
                stderr = "__REPORTLAB_MISSING__",
            )
        }
        val executor = ConvertToPdfBuiltInToolExecutor(
            commandExecutor = fakeCommandExecutor,
            runtimeEnvironment = PdfToolTestRuntimeEnvironment(workspaceRoot),
        )

        val error = assertFailsWith<IllegalStateException> {
            executor.execute(
                buildJsonObject {
                    put("input_file", "input.txt")
                    put("output_file", "out.pdf")
                },
            )
        }

        assertContains(error.message.orEmpty(), "python3 -m pip install reportlab")
    }

    @Test
    fun executeFailsWhenConverterReturnsError() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot()
        ensureDirectoryExists(workspaceRoot)
        writeTextFile("$workspaceRoot/input.txt", "content")
        val fakeCommandExecutor = PdfToolFakeCommandExecutor { _, _ ->
            CommandExecutionResult(exitCode = 9, stdout = "", stderr = "boom")
        }
        val executor = ConvertToPdfBuiltInToolExecutor(
            commandExecutor = fakeCommandExecutor,
            runtimeEnvironment = PdfToolTestRuntimeEnvironment(workspaceRoot),
        )

        val error = assertFailsWith<IllegalStateException> {
            executor.execute(
                buildJsonObject {
                    put("input_file", "input.txt")
                    put("output_file", "out.pdf")
                },
            )
        }

        assertContains(error.message.orEmpty(), "exit code 9")
        assertContains(error.message.orEmpty(), "boom")
    }

    @Test
    fun executeDecodesBase64WrappedPdfOutput() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot()
        ensureDirectoryExists(workspaceRoot)
        writeTextFile("$workspaceRoot/input.txt", "content")
        val encodedPdf = "JVBERi0xLjMKJcTl8uXrp/Og0MTGCjEgMCBvYmogICUgZW50cnkgcGFnZQooKQplbmRvYmoKMiAwIG9iago8PAovVHlwZSAvUGFnZQovUGFyZW50IDMgMCBSCi9NZWRpYUJveCBbMCAwIDU5NSA4NDJdCi9Db250ZW50cyA0IDAgUgovUmVzb3VyY2VzIDw8L0ZvbnQgPDwKL0YxIDYgMCBSCj4+PgovUHJvY1NldCBbL1BERiAvVGV4dF0KPj4KZW5kb2JqCjMgMCBvYmoKPDwKL1R5cGUgL1BhZ2VzCi9LaWRzIFsgMSAwIFIgXQovQ291bnQgMQo+PgplbmRvYmoKNCAwIG9iago8PAovTGVuZ3RoIDExMwovRmlsdGVyIFsvRmxhdGVEZWNvZGUgL0ZsYXRlRGVjb2RlXQovRmlsdGVyIC9GbGF0ZURlY29kZQo+PgpcbiAgc3RhcnR4cmVmCjExNgoKJSVFT0YK"
        val fakeCommandExecutor = PdfToolFakeCommandExecutor { _, args ->
            writeTextFile(args[3], encodedPdf)
            CommandExecutionResult(exitCode = 0, stdout = "", stderr = "")
        }
        val executor = ConvertToPdfBuiltInToolExecutor(
            commandExecutor = fakeCommandExecutor,
            runtimeEnvironment = PdfToolTestRuntimeEnvironment(workspaceRoot),
        )

        executor.execute(
            buildJsonObject {
                put("input_file", "input.txt")
                put("output_file", "out.pdf")
            },
        )

        val outputBytes = readBytesFile("$workspaceRoot/out.pdf")
        assertContains(outputBytes.decodeToString(startIndex = 0, endIndex = 8), "%PDF-1.")
    }

    @Test
    fun executeFailsWhenBackendWritesInvalidPdfPayload() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot()
        ensureDirectoryExists(workspaceRoot)
        writeTextFile("$workspaceRoot/input.txt", "content")
        val fakeCommandExecutor = PdfToolFakeCommandExecutor { _, args ->
            writeTextFile(args[3], "not a pdf payload")
            CommandExecutionResult(exitCode = 0, stdout = "", stderr = "")
        }
        val executor = ConvertToPdfBuiltInToolExecutor(
            commandExecutor = fakeCommandExecutor,
            runtimeEnvironment = PdfToolTestRuntimeEnvironment(workspaceRoot),
        )

        val error = assertFailsWith<IllegalStateException> {
            executor.execute(
                buildJsonObject {
                    put("input_file", "input.txt")
                    put("output_file", "out.pdf")
                },
            )
        }

        assertContains(error.message.orEmpty(), "did not produce a valid binary PDF")
    }

    @Test
    fun executeDecodesDataUrlBase64PdfOutput() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot()
        ensureDirectoryExists(workspaceRoot)
        writeTextFile("$workspaceRoot/input.txt", "content")
        val encodedPdf = "data:application/pdf;base64,JVBERi0xLjMKJcTl8uXrp/Og0MTGCjEgMCBvYmogICUgZW50cnkgcGFnZQooKQplbmRvYmoKMiAwIG9iago8PAovVHlwZSAvUGFnZQovUGFyZW50IDMgMCBSCi9NZWRpYUJveCBbMCAwIDU5NSA4NDJdCi9Db250ZW50cyA0IDAgUgovUmVzb3VyY2VzIDw8L0ZvbnQgPDwKL0YxIDYgMCBSCj4+PgovUHJvY1NldCBbL1BERiAvVGV4dF0KPj4KZW5kb2JqCjMgMCBvYmoKPDwKL1R5cGUgL1BhZ2VzCi9LaWRzIFsgMSAwIFIgXQovQ291bnQgMQo+PgplbmRvYmoKNCAwIG9iago8PAovTGVuZ3RoIDExMwovRmlsdGVyIFsvRmxhdGVEZWNvZGUgL0ZsYXRlRGVjb2RlXQovRmlsdGVyIC9GbGF0ZURlY29kZQo+PgpcbiAgc3RhcnR4cmVmCjExNgoKJSVFT0YK"
        val fakeCommandExecutor = PdfToolFakeCommandExecutor { _, args ->
            writeTextFile(args[3], encodedPdf)
            CommandExecutionResult(exitCode = 0, stdout = "", stderr = "")
        }
        val executor = ConvertToPdfBuiltInToolExecutor(
            commandExecutor = fakeCommandExecutor,
            runtimeEnvironment = PdfToolTestRuntimeEnvironment(workspaceRoot),
        )

        executor.execute(
            buildJsonObject {
                put("input_file", "input.txt")
                put("output_file", "out.pdf")
            },
        )

        val outputBytes = readBytesFile("$workspaceRoot/out.pdf")
        assertContains(outputBytes.decodeToString(startIndex = 0, endIndex = 8), "%PDF-1.")
    }

    @Test
    fun executeDecodesNoPaddingBase64PdfOutput() = runBlocking {
        val workspaceRoot = uniqueWorkspaceRoot()
        ensureDirectoryExists(workspaceRoot)
        writeTextFile("$workspaceRoot/input.txt", "content")
        val encodedPdfWithoutPadding = "JVBERi0xLjMKJcTl8uXrp/Og0MTGCjEgMCBvYmogICUgZW50cnkgcGFnZQooKQplbmRvYmoKMiAwIG9iago8PAovVHlwZSAvUGFnZQovUGFyZW50IDMgMCBSCi9NZWRpYUJveCBbMCAwIDU5NSA4NDJdCi9Db250ZW50cyA0IDAgUgovUmVzb3VyY2VzIDw8L0ZvbnQgPDwKL0YxIDYgMCBSCj4+PgovUHJvY1NldCBbL1BERiAvVGV4dF0KPj4KZW5kb2JqCjMgMCBvYmoKPDwKL1R5cGUgL1BhZ2VzCi9LaWRzIFsgMSAwIFIgXQovQ291bnQgMQo+PgplbmRvYmoKNCAwIG9iago8PAovTGVuZ3RoIDExMwovRmlsdGVyIFsvRmxhdGVEZWNvZGUgL0ZsYXRlRGVjb2RlXQovRmlsdGVyIC9GbGF0ZURlY29kZQo+PgpcbiAgc3RhcnR4cmVmCjExNgoKJSVFT0YK".removeSuffix("==")
        val fakeCommandExecutor = PdfToolFakeCommandExecutor { _, args ->
            writeTextFile(args[3], encodedPdfWithoutPadding)
            CommandExecutionResult(exitCode = 0, stdout = "", stderr = "")
        }
        val executor = ConvertToPdfBuiltInToolExecutor(
            commandExecutor = fakeCommandExecutor,
            runtimeEnvironment = PdfToolTestRuntimeEnvironment(workspaceRoot),
        )

        executor.execute(
            buildJsonObject {
                put("input_file", "input.txt")
                put("output_file", "out.pdf")
            },
        )

        val outputBytes = readBytesFile("$workspaceRoot/out.pdf")
        assertContains(outputBytes.decodeToString(startIndex = 0, endIndex = 8), "%PDF-1.")
    }
}

private data class CommandCall(
    val command: String,
    val args: List<String>,
)

private class PdfToolFakeCommandExecutor(
    private val behavior: (command: String, args: List<String>) -> CommandExecutionResult,
) : CommandExecutor {
    val calls = mutableListOf<CommandCall>()

    override fun execute(command: String, args: List<String>): CommandExecutionResult {
        calls += CommandCall(command = command, args = args)
        return behavior(command, args)
    }
}

private class PdfToolTestRuntimeEnvironment(
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
    return "/tmp/kotlin-agent-cli-tests/$seed/convert-to-pdf-workspace"
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
private fun writeBytesFile(path: String, bytes: ByteArray) {
    ensureDirectoryExists(parentDirectory(path) ?: "/")
    val fd = open(path, O_WRONLY or O_CREAT or O_TRUNC, FILE_MODE.convert<mode_t>())
    if (fd < 0) {
        error("Unable to open '$path' for binary write.")
    }
    try {
        var offset = 0
        while (offset < bytes.size) {
            val written = bytes.usePinned { pinned ->
                write(fd, pinned.addressOf(offset), (bytes.size - offset).convert())
            }
            if (written <= 0) {
                error("Unable to write '$path'.")
            }
            offset += written.toInt()
        }
    } finally {
        close(fd)
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
private fun readBytesFile(path: String): ByteArray {
    val fd = open(path, O_RDONLY)
    if (fd < 0) {
        error("Unable to open '$path' for binary read.")
    }
    try {
        val chunks = mutableListOf<ByteArray>()
        while (true) {
            val chunk = ByteArray(READ_BUFFER_SIZE)
            val bytesRead = chunk.usePinned { pinned ->
                read(fd, pinned.addressOf(0), chunk.size.convert())
            }
            when {
                bytesRead > 0 -> chunks += chunk.copyOf(bytesRead.toInt())
                bytesRead == 0L -> break
                else -> error("Unable to read '$path'.")
            }
        }
        val result = ByteArray(chunks.sumOf { it.size })
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(result, destinationOffset = offset)
            offset += chunk.size
        }
        return result
    } finally {
        close(fd)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun fileExists(path: String): Boolean {
    return access(path, F_OK.convert()) == 0
}

private const val DIRECTORY_MODE = 493 // 0755
private const val FILE_MODE = 420 // 0644
private const val READ_BUFFER_SIZE = 4096
