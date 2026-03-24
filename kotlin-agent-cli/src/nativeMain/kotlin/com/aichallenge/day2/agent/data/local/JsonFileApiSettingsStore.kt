package com.aichallenge.day2.agent.data.local

import com.aichallenge.day2.agent.core.config.ApiSettings
import com.aichallenge.day2.agent.core.config.ConfiguredApi
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
            ?: throw IllegalArgumentException("API settings must include at least one valid configured API.")
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
        private const val SNAPSHOT_VERSION = 3
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
    val version: Int = 3,
    val active_api_id: String = "",
    val apis: List<ConfiguredApiSnapshotDto> = emptyList(),
) {
    fun toDomainModelOrNull(): ApiSettings? {
        return ApiSettings(
            activeApiId = active_api_id,
            apis = apis.mapNotNull { api -> api.toDomainModelOrNull() },
        ).takeIf { settings -> settings.apis.size == apis.size }
            ?.normalizedOrNull()
    }

    companion object {
        fun fromDomainModel(settings: ApiSettings): ApiSettingsSnapshotDto {
            return ApiSettingsSnapshotDto(
                version = 3,
                active_api_id = settings.activeApiId,
                apis = settings.apis.map(ConfiguredApiSnapshotDto::fromDomainModel),
            )
        }
    }
}

@Serializable
private data class ConfiguredApiSnapshotDto(
    val id: String = "",
    val name: String = "",
    val base_url: String = "",
    val api_key: String = "",
    val available_models: List<String> = emptyList(),
    val default_model: String = "",
    val selected_model: String = "",
) {
    fun toDomainModelOrNull(): ConfiguredApi? {
        return ConfiguredApi(
            id = id,
            name = name,
            baseUrl = base_url,
            apiKey = api_key,
            availableModels = available_models,
            defaultModel = default_model,
            selectedModel = selected_model,
        ).normalizedOrNull()
    }

    companion object {
        fun fromDomainModel(settings: ConfiguredApi): ConfiguredApiSnapshotDto {
            return ConfiguredApiSnapshotDto(
                id = settings.id,
                name = settings.name,
                base_url = settings.baseUrl,
                api_key = settings.apiKey,
                available_models = settings.availableModels,
                default_model = settings.defaultModel,
                selected_model = settings.selectedModel,
            )
        }
    }
}
