package com.aichallenge.day2.agent.data.local

import com.aichallenge.day2.agent.core.config.ApiProvider
import com.aichallenge.day2.agent.core.config.ApiProviderSettings
import com.aichallenge.day2.agent.core.config.ApiSettings
import com.aichallenge.day2.agent.domain.repository.ApiSettingsStore
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

class JsonFileApiSettingsStore(
    private val filePath: String,
    private val json: Json = defaultJson(),
) : ApiSettingsStore {
    override fun load(): ApiSettings? {
        val fileContents = readTextFile(filePath) ?: return null
        val snapshot = runCatching {
            json.decodeFromString<ApiSettingsSnapshotDto>(fileContents)
        }.getOrNull() ?: return null
        if (snapshot.version != SNAPSHOT_VERSION) {
            return null
        }
        return snapshot.toDomainModelOrNull()
    }

    override fun save(settings: ApiSettings) {
        val normalizedSettings = settings.normalizedOrNull()
            ?: throw IllegalArgumentException("API settings must include a configured active provider.")
        val payload = json.encodeToString(
            ApiSettingsSnapshotDto.fromDomainModel(normalizedSettings),
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
        private const val SNAPSHOT_VERSION = 1
        private const val DIRECTORY_MODE = 493 // 0755
        private const val READ_BUFFER_SIZE = 4096

        fun fromDefaultLocation(json: Json = defaultJson()): JsonFileApiSettingsStore? {
            val homeDirectory = readHomeDirectory() ?: return null
            val normalizedHome = homeDirectory.trimEnd('/')
            return JsonFileApiSettingsStore(
                filePath = "$normalizedHome/.kotlin-agent-cli/api-settings.json",
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
                    throw IllegalStateException("Unable to write API settings file '$path'.")
                }
            } finally {
                fclose(file)
            }
        }
    }
}

@Serializable
private data class ApiSettingsSnapshotDto(
    val version: Int = 1,
    val active_provider: String = "",
    val openai: ApiProviderSettingsSnapshotDto? = null,
    val ollama: ApiProviderSettingsSnapshotDto? = null,
) {
    fun toDomainModelOrNull(): ApiSettings? {
        val activeProvider = ApiProvider.fromStorageValue(active_provider) ?: return null
        return ApiSettings(
            activeProvider = activeProvider,
            openAi = openai?.toDomainModelOrNull(),
            ollama = ollama?.toDomainModelOrNull(),
        ).normalizedOrNull()
    }

    companion object {
        fun fromDomainModel(settings: ApiSettings): ApiSettingsSnapshotDto {
            return ApiSettingsSnapshotDto(
                version = 1,
                active_provider = settings.activeProvider.name,
                openai = settings.openAi?.let(ApiProviderSettingsSnapshotDto::fromDomainModel),
                ollama = settings.ollama?.let(ApiProviderSettingsSnapshotDto::fromDomainModel),
            )
        }
    }
}

@Serializable
private data class ApiProviderSettingsSnapshotDto(
    val base_url: String = "",
    val api_key: String = "",
    val selected_model: String = "",
) {
    fun toDomainModelOrNull(): ApiProviderSettings? {
        return ApiProviderSettings(
            baseUrl = base_url,
            apiKey = api_key,
            selectedModel = selected_model,
        ).normalizedOrNull()
    }

    companion object {
        fun fromDomainModel(settings: ApiProviderSettings): ApiProviderSettingsSnapshotDto {
            return ApiProviderSettingsSnapshotDto(
                base_url = settings.baseUrl,
                api_key = settings.apiKey,
                selected_model = settings.selectedModel,
            )
        }
    }
}
