package com.aichallenge.day2.agent.data.local

import com.aichallenge.day2.agent.domain.model.RagSourceConfig
import com.aichallenge.day2.agent.domain.model.RagSourceType
import com.aichallenge.day2.agent.domain.repository.RagSourceStore
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.serialization.SerialName
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

class JsonFileRagSourceStore(
    private val filePath: String,
    private val json: Json = defaultJson(),
) : RagSourceStore {
    override fun load(): List<RagSourceConfig> {
        val fileContents = readTextFile(filePath) ?: return emptyList()
        val snapshot = runCatching {
            json.decodeFromString<RagSourcesSnapshotDto>(fileContents)
        }.getOrNull() ?: return emptyList()

        return snapshot.sources.mapNotNull { source ->
            source.toDomainModelOrNull()
        }
    }

    override fun save(sources: List<RagSourceConfig>) {
        val normalizedSources = sources.mapNotNull { source ->
            source.normalizedOrNull()
        }
        val payload = json.encodeToString(
            RagSourcesSnapshotDto(
                sources = normalizedSources.map { source ->
                    source.toSnapshotDto()
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
        private const val DEFAULT_FILE_NAME = "rag-sources.json"

        fun fromDefaultLocation(json: Json = defaultJson()): JsonFileRagSourceStore? {
            val homeDirectory = readHomeDirectory() ?: return null
            val normalizedHome = homeDirectory.trimEnd('/')
            return JsonFileRagSourceStore(
                filePath = "$normalizedHome/$DEFAULT_DIRECTORY_NAME/$DEFAULT_FILE_NAME",
                json = json,
            )
        }

        private fun defaultJson(): Json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
            allowTrailingComma = true
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
                    throw IllegalStateException("Unable to write RAG sources file '$path'.")
                }
            } finally {
                fclose(file)
            }
        }
    }
}

@Serializable
private data class RagSourcesSnapshotDto(
    val sources: List<RagSourceSnapshotDto> = emptyList(),
)

@Serializable
private data class RagSourceSnapshotDto(
    val name: String = "",
    val type: String = "",
    @SerialName("database_url")
    val databaseUrl: String = "",
    val enabled: Boolean = false,
) {
    fun toDomainModelOrNull(): RagSourceConfig? {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) {
            return null
        }

        val normalizedType = when (type.trim().lowercase()) {
            POSTGRES_TYPE -> RagSourceType.POSTGRES
            else -> null
        } ?: return null

        val normalizedDatabaseUrl = databaseUrl.trim()
        if (normalizedDatabaseUrl.isEmpty()) {
            return null
        }

        return RagSourceConfig(
            name = normalizedName,
            enabled = enabled,
            type = normalizedType,
            databaseUrl = normalizedDatabaseUrl,
        )
    }
}

private fun RagSourceConfig.normalizedOrNull(): RagSourceConfig? {
    val normalizedName = name.trim()
    if (normalizedName.isEmpty()) {
        return null
    }

    val normalizedDatabaseUrl = databaseUrl.trim()
    if (normalizedDatabaseUrl.isEmpty()) {
        return null
    }

    return copy(
        name = normalizedName,
        databaseUrl = normalizedDatabaseUrl,
    )
}

private fun RagSourceConfig.toSnapshotDto(): RagSourceSnapshotDto = RagSourceSnapshotDto(
    name = name,
    type = when (type) {
        RagSourceType.POSTGRES -> POSTGRES_TYPE
    },
    databaseUrl = databaseUrl,
    enabled = enabled,
)

private const val POSTGRES_TYPE = "postgres"
