package com.aichallenge.day2.agent.data.local

import com.aichallenge.day2.agent.domain.model.McpServerConfig
import com.aichallenge.day2.agent.domain.repository.McpServerStore
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.posix.EEXIST
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.mode_t

class JsonFileMcpServerStore(
    private val filePath: String,
    private val json: Json = defaultJson(),
) : McpServerStore {
    override fun load(): List<McpServerConfig> {
        val fileContents = readTextFile(filePath) ?: return emptyList()
        val snapshot = runCatching {
            json.decodeFromString<McpServersSnapshotDto>(fileContents)
        }.getOrNull() ?: return emptyList()

        return snapshot.servers.mapNotNull { server ->
            server.toDomainModelOrNull()
        }
    }

    override fun save(servers: List<McpServerConfig>) {
        val normalizedServers = servers.mapNotNull { server ->
            server.normalizedOrNull()
        }
        val payload = json.encodeToString(
            McpServersSnapshotDto(
                servers = normalizedServers.map { server ->
                    McpServerSnapshotDto(
                        name = server.name,
                        url = server.url,
                        enabled = server.enabled,
                    )
                },
            ),
        )
        ensureParentDirectoryExists(filePath)
        writeTextFile(filePath, payload)
    }

    private fun ensureParentDirectoryExists(path: String) {
        val parent = parentDirectory(path) ?: return
        ensureDirectoryExists(parent)
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

        throw IllegalStateException("Unable to create directory '$path'.")
    }

    private fun parentDirectory(path: String): String? {
        if (path.isBlank() || path == "/") return null
        val normalized = path.trimEnd('/')
        val separatorIndex = normalized.lastIndexOf('/')
        if (separatorIndex < 0) return null
        return if (separatorIndex == 0) "/" else normalized.substring(0, separatorIndex)
    }

    companion object {
        private const val DIRECTORY_MODE = 493 // 0755
        private const val READ_BUFFER_SIZE = 4096
        private const val DEFAULT_DIRECTORY_NAME = ".kotlin-agent-cli"
        private const val DEFAULT_FILE_NAME = "mcp-servers.json"

        fun fromDefaultLocation(json: Json = defaultJson()): JsonFileMcpServerStore? {
            val homeDirectory = readHomeDirectory() ?: return null
            val normalizedHome = homeDirectory.trimEnd('/')
            return JsonFileMcpServerStore(
                filePath = "$normalizedHome/$DEFAULT_DIRECTORY_NAME/$DEFAULT_FILE_NAME",
                json = json,
            )
        }

        private fun defaultJson(): Json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        @OptIn(ExperimentalForeignApi::class)
        private fun readHomeDirectory(): String? = getenv("HOME")?.toKString()?.takeIf { it.isNotBlank() }

        @OptIn(ExperimentalForeignApi::class)
        private fun readTextFile(path: String): String? {
            val file = fopen(path, "r") ?: return null
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
        private fun writeTextFile(path: String, text: String) {
            val file = fopen(path, "w")
                ?: throw IllegalStateException("Unable to open '$path' for writing.")

            try {
                if (fputs(text, file) < 0) {
                    throw IllegalStateException("Unable to write MCP servers file '$path'.")
                }
            } finally {
                fclose(file)
            }
        }
    }
}

@Serializable
private data class McpServersSnapshotDto(
    val servers: List<McpServerSnapshotDto> = emptyList(),
)

@Serializable
private data class McpServerSnapshotDto(
    val name: String = "",
    val url: String = "",
    val enabled: Boolean = false,
) {
    fun toDomainModelOrNull(): McpServerConfig? {
        val normalizedName = name.trim()
        val normalizedUrl = url.trim()
        if (normalizedName.isEmpty() || normalizedUrl.isEmpty()) {
            return null
        }
        return McpServerConfig(
            name = normalizedName,
            url = normalizedUrl,
            enabled = enabled,
        )
    }
}

private fun McpServerConfig.normalizedOrNull(): McpServerConfig? {
    val normalizedName = name.trim()
    val normalizedUrl = url.trim()
    if (normalizedName.isEmpty() || normalizedUrl.isEmpty()) {
        return null
    }
    return copy(
        name = normalizedName,
        url = normalizedUrl,
    )
}
