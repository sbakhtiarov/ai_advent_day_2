@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.core.config.AppRuntimeEnvironment
import com.aichallenge.day2.agent.core.config.DefaultAppRuntimeEnvironment
import com.aichallenge.day2.agent.domain.model.PrivateToolResult
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.F_OK
import platform.posix.O_CREAT
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.access
import platform.posix.close
import platform.posix.errno
import platform.posix.mkdir
import platform.posix.mode_t
import platform.posix.open
import platform.posix.write

private const val SAVE_TO_FILE_TOOL_ID = "save_to_file"

fun saveToFileToolRegistration(
    runtimeEnvironment: AppRuntimeEnvironment = DefaultAppRuntimeEnvironment(),
): BuiltInToolRegistration {
    return BuiltInToolRegistration(
        definition = BuiltInToolDefinition(
            toolId = SAVE_TO_FILE_TOOL_ID,
            modelToolName = SAVE_TO_FILE_TOOL_ID,
            description = "Create a file with the provided file name and content inside the current workspace. Use this tool when the user asks to save generated content to disk.",
            parametersSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "file_name",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Target file path. Relative paths are resolved from the current workspace root; absolute paths must still be inside the workspace.")
                            },
                        )
                        put(
                            "content",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Text content to write to the file. Empty string is allowed.")
                            },
                        )
                        put(
                            "overwrite",
                            buildJsonObject {
                                put("type", "boolean")
                                put("description", "When true, replace the file if it already exists. Defaults to false.")
                            },
                        )
                    },
                )
                put(
                    "required",
                    buildJsonArray {
                        add(JsonPrimitive("file_name"))
                        add(JsonPrimitive("content"))
                    },
                )
                put("additionalProperties", false)
            },
        ),
        executor = SaveToFileBuiltInToolExecutor(runtimeEnvironment),
    )
}

class SaveToFileBuiltInToolExecutor(
    private val runtimeEnvironment: AppRuntimeEnvironment,
) : BuiltInToolExecutor {
    override suspend fun execute(arguments: JsonObject): PrivateToolResult {
        val fileName = requireStringArgument(arguments, "file_name")
        val content = requireContentArgument(arguments, "content")
        val overwrite = optionalBooleanArgument(arguments, "overwrite") ?: false

        val workspaceRoot = resolveWorkspaceRoot()
        val absolutePath = resolveAbsoluteTargetPath(
            workspaceRoot = workspaceRoot,
            fileName = fileName,
        )
        if (!isPathInsideWorkspace(workspaceRoot, absolutePath)) {
            throw IllegalArgumentException(
                "Argument 'file_name' must resolve to a path inside workspace '$workspaceRoot'.",
            )
        }

        val bytes = content.encodeToByteArray()
        val existed = fileExists(absolutePath)
        if (existed && !overwrite) {
            throw IllegalArgumentException(
                "File '$absolutePath' already exists. Set overwrite=true to replace it.",
            )
        }

        ensureParentDirectoryExists(absolutePath)
        writeBytesToFile(absolutePath, bytes)

        return PrivateToolResult(
            isError = false,
            content = textContent(
                if (existed) {
                    "Overwrote '$absolutePath'."
                } else {
                    "Saved '$absolutePath'."
                },
            ),
            structuredContent = buildJsonObject {
                put("file_name", fileName)
                put("absolute_path", absolutePath)
                put("overwritten", existed)
                put("bytes_written", bytes.size)
                put("workspace_root", workspaceRoot)
            },
        )
    }

    private fun requireStringArgument(arguments: JsonObject, name: String): String {
        val primitive = arguments[name] as? JsonPrimitive
            ?: throw IllegalArgumentException("Argument '$name' must be a non-blank string.")
        val value = primitive.strictStringValue()
            ?: throw IllegalArgumentException("Argument '$name' must be a non-blank string.")
        return value.trim().takeIf { normalized -> normalized.isNotEmpty() }
            ?: throw IllegalArgumentException("Argument '$name' must be a non-blank string.")
    }

    private fun requireContentArgument(arguments: JsonObject, name: String): String {
        val primitive = arguments[name] as? JsonPrimitive
            ?: throw IllegalArgumentException("Argument '$name' must be a string.")
        return primitive.strictStringValue()
            ?: throw IllegalArgumentException("Argument '$name' must be a string.")
    }

    private fun JsonPrimitive.strictStringValue(): String? {
        val raw = toString()
        if (!(raw.length >= 2 && raw.first() == '"' && raw.last() == '"')) {
            return null
        }
        return contentOrNull
    }

    private fun optionalBooleanArgument(arguments: JsonObject, name: String): Boolean? {
        val value = arguments[name] ?: return null
        val primitive = value as? JsonPrimitive
            ?: throw IllegalArgumentException("Argument '$name' must be a boolean.")
        return primitive.booleanOrNull
            ?: throw IllegalArgumentException("Argument '$name' must be a boolean.")
    }

    private fun resolveWorkspaceRoot(): String {
        val cwd = runtimeEnvironment.currentWorkingDirectory()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Unable to determine current working directory for save_to_file.")
        if (!cwd.startsWith("/")) {
            throw IllegalStateException("Current working directory must be an absolute path.")
        }
        return normalizeAbsolutePath(cwd)
    }

    private fun resolveAbsoluteTargetPath(
        workspaceRoot: String,
        fileName: String,
    ): String {
        val rawPath = if (fileName.startsWith("/")) {
            fileName
        } else {
            if (workspaceRoot == "/") {
                "/$fileName"
            } else {
                "${workspaceRoot.trimEnd('/')}/$fileName"
            }
        }
        return normalizeAbsolutePath(rawPath)
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

    private fun isPathInsideWorkspace(workspaceRoot: String, path: String): Boolean {
        if (workspaceRoot == "/") {
            return true
        }
        return path == workspaceRoot || path.startsWith("$workspaceRoot/")
    }

    private fun ensureParentDirectoryExists(path: String) {
        val parent = parentDirectory(path) ?: return
        ensureDirectoryExists(parent)
    }

    private fun ensureDirectoryExists(path: String) {
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

    private fun parentDirectory(path: String): String? {
        if (path.isBlank() || path == "/") return null
        val normalized = path.trimEnd('/')
        val separatorIndex = normalized.lastIndexOf('/')
        if (separatorIndex < 0) return null
        return if (separatorIndex == 0) "/" else normalized.substring(0, separatorIndex)
    }

    private fun fileExists(path: String): Boolean {
        return access(path, F_OK.convert()) == 0
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
                    write(
                        fd,
                        pinned.addressOf(offset),
                        (bytes.size - offset).convert(),
                    )
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

    private fun textContent(text: String): JsonArray {
        return buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                },
            )
        }
    }

    companion object {
        private const val DIRECTORY_MODE = 493 // 0755
        private const val FILE_MODE = 420 // 0644
    }
}
