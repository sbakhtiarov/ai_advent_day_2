@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.core.config.AppRuntimeEnvironment
import com.aichallenge.day2.agent.core.config.DefaultAppRuntimeEnvironment
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import platform.posix.DIR
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.ENOENT
import platform.posix.F_OK
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.access
import platform.posix.closedir
import platform.posix.close
import platform.posix.dirent
import platform.posix.errno
import platform.posix.mkdir
import platform.posix.mode_t
import platform.posix.open
import platform.posix.opendir
import platform.posix.read
import platform.posix.readdir
import platform.posix.unlink
import platform.posix.write

private const val DIRECTORY_MODE = 493 // 0755
private const val FILE_MODE = 420 // 0644
private const val FILE_READ_BUFFER_SIZE = 8 * 1024

internal data class WorkspaceResolvedPath(
    val workspaceRoot: String,
    val absolutePath: String,
    val workspaceRelativePath: String,
)

internal data class WorkspaceDirectoryEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
)

internal data class OffsetPage<T>(
    val items: List<T>,
    val offset: Int,
    val limit: Int,
    val totalCount: Int,
    val nextOffset: Int?,
)

internal data class LineSlice(
    val lines: List<String>,
    val startLine: Int,
    val maxLines: Int,
    val totalLines: Int,
    val nextStartLine: Int?,
)

internal data class UnifiedDiffResult(
    val allLines: List<String>,
    val pageLines: List<String>,
    val offset: Int,
    val limit: Int,
    val nextOffset: Int?,
    val isDifferent: Boolean,
)

internal class WorkspaceFileSupport(
    private val runtimeEnvironment: AppRuntimeEnvironment = DefaultAppRuntimeEnvironment(),
) {
    fun resolveWorkspaceRoot(toolName: String): String {
        val cwd = runtimeEnvironment.currentWorkingDirectory()?.trim()?.takeIf { value -> value.isNotEmpty() }
            ?: throw IllegalStateException("Unable to determine current working directory for $toolName.")
        if (!cwd.startsWith("/")) {
            throw IllegalStateException("Current working directory must be an absolute path.")
        }
        return normalizeAbsolutePath(cwd)
    }

    fun resolvePath(path: String, toolName: String, argumentName: String = "path"): WorkspaceResolvedPath {
        val workspaceRoot = resolveWorkspaceRoot(toolName)
        val absolutePath = resolveAbsolutePath(workspaceRoot, path, argumentName)
        ensureInsideWorkspace(workspaceRoot, absolutePath, argumentName)
        return WorkspaceResolvedPath(
            workspaceRoot = workspaceRoot,
            absolutePath = absolutePath,
            workspaceRelativePath = toWorkspaceRelativePath(workspaceRoot, absolutePath),
        )
    }

    fun resolveOptionalPath(
        path: String?,
        toolName: String,
        argumentName: String = "path",
        defaultPath: String = ".",
    ): WorkspaceResolvedPath {
        return resolvePath(
            path = path?.takeIf { value -> value.isNotBlank() } ?: defaultPath,
            toolName = toolName,
            argumentName = argumentName,
        )
    }

    fun ensureDirectory(path: WorkspaceResolvedPath, argumentName: String = "path") {
        if (!directoryExists(path.absolutePath)) {
            if (pathExists(path.absolutePath)) {
                throw IllegalArgumentException("Argument '$argumentName' must resolve to a directory inside the workspace.")
            }
            throw IllegalArgumentException("Directory '${path.absolutePath}' does not exist.")
        }
    }

    fun ensureFile(path: WorkspaceResolvedPath, argumentName: String = "path") {
        if (directoryExists(path.absolutePath)) {
            throw IllegalArgumentException("Argument '$argumentName' must resolve to a file inside the workspace.")
        }
        if (!pathExists(path.absolutePath)) {
            throw IllegalArgumentException("File '${path.absolutePath}' does not exist.")
        }
    }

    fun pathExists(path: String): Boolean = access(path, F_OK.convert()) == 0

    fun directoryExists(path: String): Boolean {
        val directory = opendir(path) ?: return false
        closedir(directory)
        return true
    }

    fun ensureParentDirectoryExists(absolutePath: String) {
        val parent = parentDirectory(absolutePath) ?: return
        ensureDirectoryExists(parent)
    }

    fun ensureDirectoryExists(path: String) {
        if (path.isBlank() || path == "/") return

        val parent = parentDirectory(path)
        if (parent != null && parent != path) {
            ensureDirectoryExists(parent)
        }

        val result = mkdir(path, DIRECTORY_MODE.convert<mode_t>())
        if (result == 0 || errno == EEXIST) {
            return
        }
        throw IllegalStateException("Unable to create directory '$path'.")
    }

    fun writeUtf8TextFile(path: String, content: String) {
        writeBytesToFile(path, content.encodeToByteArray())
    }

    fun readUtf8TextFile(path: String): String {
        if (directoryExists(path)) {
            throw IllegalArgumentException("File '$path' is a directory.")
        }
        val bytes = readBytesFromFile(path)
        if (bytes.contains(0.toByte())) {
            throw IllegalArgumentException("File '$path' is not valid UTF-8 text.")
        }
        val decoded = runCatching { bytes.decodeToString() }
            .getOrElse { throw IllegalArgumentException("File '$path' is not valid UTF-8 text.") }
        if (decoded.encodeToByteArray().contentEquals(bytes).not()) {
            throw IllegalArgumentException("File '$path' is not valid UTF-8 text.")
        }
        return decoded
    }

    fun listDirectory(path: String): List<WorkspaceDirectoryEntry> {
        val directory = opendir(path)
            ?: throw IllegalArgumentException("Directory '$path' does not exist.")
        return try {
            val entries = mutableListOf<WorkspaceDirectoryEntry>()
            while (true) {
                val entry = readdir(directory) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name == "." || name == "..") {
                    continue
                }
                val absoluteChildPath = joinPaths(path, name)
                entries += WorkspaceDirectoryEntry(
                    name = name,
                    path = absoluteChildPath,
                    isDirectory = directoryExists(absoluteChildPath),
                )
            }
            entries.sortedWith(compareBy<WorkspaceDirectoryEntry>({ !it.isDirectory }, { it.name.lowercase() }, { it.name }))
        } finally {
            closedir(directory)
        }
    }

    fun walkFiles(path: String): List<String> {
        val result = mutableListOf<String>()
        walkFilesRecursively(path, result)
        return result
    }

    fun deleteFile(path: String): Boolean {
        if (directoryExists(path)) {
            throw IllegalArgumentException("File '$path' is a directory.")
        }
        val deleted = unlink(path)
        return when {
            deleted == 0 -> true
            errno == ENOENT -> false
            else -> throw IllegalStateException("Unable to delete '$path'.")
        }
    }

    fun sliceLines(content: String, startLine: Int, maxLines: Int): LineSlice {
        require(startLine >= 1) {
            "startLine must be at least 1."
        }
        require(maxLines >= 1) {
            "maxLines must be at least 1."
        }

        val lines = normalizeLines(content)
        val startIndex = (startLine - 1).coerceAtMost(lines.size)
        val endExclusive = (startIndex + maxLines).coerceAtMost(lines.size)
        val slice = lines.subList(startIndex, endExclusive)
        val nextStartLine = if (endExclusive < lines.size) endExclusive + 1 else null
        return LineSlice(
            lines = slice,
            startLine = startLine,
            maxLines = maxLines,
            totalLines = lines.size,
            nextStartLine = nextStartLine,
        )
    }

    fun <T> paginate(items: List<T>, offset: Int, limit: Int): OffsetPage<T> {
        require(offset >= 0) {
            "offset must be at least 0."
        }
        require(limit >= 1) {
            "limit must be at least 1."
        }
        val safeOffset = offset.coerceAtMost(items.size)
        val pageItems = items.drop(safeOffset).take(limit)
        val nextOffset = (safeOffset + pageItems.size).takeIf { value -> value < items.size }
        return OffsetPage(
            items = pageItems,
            offset = safeOffset,
            limit = limit,
            totalCount = items.size,
            nextOffset = nextOffset,
        )
    }

    fun buildUnifiedDiff(
        beforeContent: String,
        afterContent: String,
        pathHint: String?,
        offset: Int,
        limit: Int,
    ): UnifiedDiffResult {
        val beforeLines = normalizeLines(beforeContent)
        val afterLines = normalizeLines(afterContent)
        if (beforeLines == afterLines) {
            return UnifiedDiffResult(
                allLines = emptyList(),
                pageLines = emptyList(),
                offset = 0,
                limit = limit,
                nextOffset = null,
                isDifferent = false,
            )
        }

        val displayPath = pathHint?.trim()?.takeIf { value -> value.isNotEmpty() } ?: "file"
        val operations = buildDiffOperations(beforeLines, afterLines)
        val allLines = buildList {
            add("--- a/$displayPath")
            add("+++ b/$displayPath")
            add("@@ -1,${beforeLines.size} +1,${afterLines.size} @@")
            operations.forEach { operation ->
                val prefix = when (operation.type) {
                    DiffLineType.CONTEXT -> " "
                    DiffLineType.REMOVED -> "-"
                    DiffLineType.ADDED -> "+"
                }
                add(prefix + operation.line)
            }
        }
        val page = paginate(allLines, offset = offset, limit = limit)
        return UnifiedDiffResult(
            allLines = allLines,
            pageLines = page.items,
            offset = page.offset,
            limit = page.limit,
            nextOffset = page.nextOffset,
            isDifferent = true,
        )
    }

    fun toWorkspaceRelativePath(workspaceRoot: String, absolutePath: String): String {
        if (absolutePath == workspaceRoot) {
            return "."
        }
        return absolutePath.removePrefix(workspaceRoot).removePrefix("/")
    }

    private fun walkFilesRecursively(path: String, collector: MutableList<String>) {
        listDirectory(path).forEach { entry ->
            if (entry.isDirectory) {
                walkFilesRecursively(entry.path, collector)
            } else {
                collector += entry.path
            }
        }
    }

    private fun resolveAbsolutePath(workspaceRoot: String, path: String, argumentName: String): String {
        val normalizedInput = path.trim().takeIf { value -> value.isNotEmpty() }
            ?: throw IllegalArgumentException("Argument '$argumentName' must be a non-blank string.")
        val rawPath = if (normalizedInput.startsWith("/")) {
            normalizedInput
        } else if (workspaceRoot == "/") {
            "/$normalizedInput"
        } else {
            "${workspaceRoot.trimEnd('/')}/$normalizedInput"
        }
        return normalizeAbsolutePath(rawPath)
    }

    private fun ensureInsideWorkspace(workspaceRoot: String, absolutePath: String, argumentName: String) {
        if (workspaceRoot == "/") {
            return
        }
        if (absolutePath == workspaceRoot || absolutePath.startsWith("$workspaceRoot/")) {
            return
        }
        throw IllegalArgumentException("Argument '$argumentName' must resolve to a path inside workspace '$workspaceRoot'.")
    }

    private fun normalizeAbsolutePath(path: String): String {
        require(path.startsWith("/")) {
            "Path must be absolute."
        }
        val segments = mutableListOf<String>()
        path.split('/').forEach { segment ->
            when (segment) {
                "",
                ".",
                -> Unit

                ".." -> if (segments.isNotEmpty()) {
                    segments.removeAt(segments.lastIndex)
                }

                else -> segments += segment
            }
        }
        return if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")
    }

    private fun parentDirectory(path: String): String? {
        if (path.isBlank() || path == "/") return null
        val normalized = path.trimEnd('/')
        val separatorIndex = normalized.lastIndexOf('/')
        if (separatorIndex < 0) return null
        return if (separatorIndex == 0) "/" else normalized.substring(0, separatorIndex)
    }

    private fun joinPaths(parent: String, child: String): String {
        return if (parent == "/") {
            "/$child"
        } else {
            "${parent.trimEnd('/')}/$child"
        }
    }

    private fun readBytesFromFile(path: String): ByteArray {
        val fd = open(path, O_RDONLY)
        if (fd < 0) {
            throw IllegalStateException("Unable to open '$path' for reading.")
        }
        return try {
            val chunks = mutableListOf<ByteArray>()
            while (true) {
                val buffer = ByteArray(FILE_READ_BUFFER_SIZE)
                val bytesRead = buffer.usePinned { pinned ->
                    read(fd, pinned.addressOf(0), buffer.size.convert())
                }
                when {
                    bytesRead > 0 -> chunks += buffer.copyOf(bytesRead.toInt())
                    bytesRead == 0L -> break
                    bytesRead < 0 && errno == EINTR -> continue
                    else -> throw IllegalStateException("Unable to read file '$path'.")
                }
            }

            if (chunks.isEmpty()) {
                return ByteArray(0)
            }

            val totalBytes = chunks.sumOf(ByteArray::size)
            val bytes = ByteArray(totalBytes)
            var offset = 0
            chunks.forEach { chunk ->
                chunk.copyInto(bytes, destinationOffset = offset)
                offset += chunk.size
            }
            bytes
        } finally {
            close(fd)
        }
    }

    private fun writeBytesToFile(path: String, bytes: ByteArray) {
        val fd = open(
            path,
            O_WRONLY or O_CREAT or O_TRUNC,
            FILE_MODE.convert<mode_t>(),
        )
        if (fd < 0) {
            throw IllegalStateException("Unable to open '$path' for writing.")
        }
        try {
            var offset = 0
            while (offset < bytes.size) {
                val written = bytes.usePinned { pinned ->
                    write(fd, pinned.addressOf(offset), (bytes.size - offset).convert())
                }
                when {
                    written > 0 -> offset += written.toInt()
                    written < 0 && errno == EINTR -> continue
                    else -> throw IllegalStateException("Unable to write file '$path'.")
                }
            }
        } finally {
            close(fd)
        }
    }
}

private enum class DiffLineType {
    CONTEXT,
    REMOVED,
    ADDED,
}

private data class DiffLine(
    val type: DiffLineType,
    val line: String,
)

private fun buildDiffOperations(beforeLines: List<String>, afterLines: List<String>): List<DiffLine> {
    val lcs = Array(beforeLines.size + 1) { IntArray(afterLines.size + 1) }
    for (beforeIndex in beforeLines.size - 1 downTo 0) {
        for (afterIndex in afterLines.size - 1 downTo 0) {
            lcs[beforeIndex][afterIndex] = if (beforeLines[beforeIndex] == afterLines[afterIndex]) {
                lcs[beforeIndex + 1][afterIndex + 1] + 1
            } else {
                maxOf(lcs[beforeIndex + 1][afterIndex], lcs[beforeIndex][afterIndex + 1])
            }
        }
    }

    val operations = mutableListOf<DiffLine>()
    var beforeIndex = 0
    var afterIndex = 0
    while (beforeIndex < beforeLines.size && afterIndex < afterLines.size) {
        if (beforeLines[beforeIndex] == afterLines[afterIndex]) {
            operations += DiffLine(DiffLineType.CONTEXT, beforeLines[beforeIndex])
            beforeIndex += 1
            afterIndex += 1
        } else if (lcs[beforeIndex + 1][afterIndex] >= lcs[beforeIndex][afterIndex + 1]) {
            operations += DiffLine(DiffLineType.REMOVED, beforeLines[beforeIndex])
            beforeIndex += 1
        } else {
            operations += DiffLine(DiffLineType.ADDED, afterLines[afterIndex])
            afterIndex += 1
        }
    }
    while (beforeIndex < beforeLines.size) {
        operations += DiffLine(DiffLineType.REMOVED, beforeLines[beforeIndex])
        beforeIndex += 1
    }
    while (afterIndex < afterLines.size) {
        operations += DiffLine(DiffLineType.ADDED, afterLines[afterIndex])
        afterIndex += 1
    }
    return operations
}

internal fun JsonObject.requireNonBlankStringArgument(name: String): String {
    val primitive = this[name] as? JsonPrimitive
        ?: throw IllegalArgumentException("Argument '$name' must be a non-blank string.")
    val value = primitive.strictStringValue()
        ?: throw IllegalArgumentException("Argument '$name' must be a non-blank string.")
    return value.trim().takeIf { normalized -> normalized.isNotEmpty() }
        ?: throw IllegalArgumentException("Argument '$name' must be a non-blank string.")
}

internal fun JsonObject.requireStringArgument(name: String): String {
    val primitive = this[name] as? JsonPrimitive
        ?: throw IllegalArgumentException("Argument '$name' must be a string.")
    return primitive.strictStringValue()
        ?: throw IllegalArgumentException("Argument '$name' must be a string.")
}

internal fun JsonObject.optionalStringArgument(name: String): String? {
    val value = this[name] ?: return null
    val primitive = value as? JsonPrimitive
        ?: throw IllegalArgumentException("Argument '$name' must be a string.")
    return primitive.strictStringValue()
        ?: throw IllegalArgumentException("Argument '$name' must be a string.")
}

internal fun JsonObject.optionalBooleanArgument(name: String): Boolean? {
    val value = this[name] ?: return null
    val primitive = value as? JsonPrimitive
        ?: throw IllegalArgumentException("Argument '$name' must be a boolean.")
    return primitive.booleanOrNull
        ?: throw IllegalArgumentException("Argument '$name' must be a boolean.")
}

internal fun JsonObject.optionalIntArgument(name: String): Int? {
    val value = this[name] ?: return null
    val primitive = value as? JsonPrimitive
        ?: throw IllegalArgumentException("Argument '$name' must be an integer.")
    return primitive.intOrNull
        ?: throw IllegalArgumentException("Argument '$name' must be an integer.")
}

internal fun JsonPrimitive.strictStringValue(): String? {
    val raw = toString()
    if (raw.length < 2 || raw.first() != '"' || raw.last() != '"') {
        return null
    }
    return contentOrNull
}

internal fun textContent(text: String): JsonArray {
    return buildJsonArray {
        add(
            buildJsonObject {
                put("type", "text")
                put("text", text)
            },
        )
    }
}

private fun normalizeLines(content: String): List<String> {
    if (content.isEmpty()) {
        return emptyList()
    }
    return content.split('\n').map { line -> line.removeSuffix("\r") }
}
