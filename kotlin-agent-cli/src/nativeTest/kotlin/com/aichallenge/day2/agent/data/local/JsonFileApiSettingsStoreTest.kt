package com.aichallenge.day2.agent.data.local

import com.aichallenge.day2.agent.core.config.ApiSettings
import com.aichallenge.day2.agent.core.config.ConfiguredApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.posix.EEXIST
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.mkdir
import platform.posix.mode_t
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JsonFileApiSettingsStoreTest {
    @Test
    fun saveAndLoadRoundTripPreservesConfiguredApis() {
        val filePath = uniqueApiSettingsFilePath()
        val store = JsonFileApiSettingsStore(filePath)
        val settings = ApiSettings(
            activeApiId = "local",
            apis = listOf(
                ConfiguredApi(
                    id = "prod",
                    name = "Production",
                    baseUrl = "https://api.openai.com/v1",
                    apiKey = "sk-prod",
                    availableModels = listOf("gpt-4.1-mini", "gpt-4.1-nano"),
                    defaultModel = "gpt-4.1-mini",
                    selectedModel = "gpt-4.1-mini",
                ),
                ConfiguredApi(
                    id = "local",
                    name = "Local",
                    baseUrl = "https://localhost:8080/v1",
                    apiKey = "sk-local",
                    availableModels = listOf("gpt-4.1-nano"),
                    defaultModel = "gpt-4.1-nano",
                    selectedModel = "gpt-4.1-nano",
                ),
            ),
        )

        store.save(settings)

        assertEquals(settings, store.load())
    }

    @Test
    fun saveAndLoadRoundTripPreservesTemperatureOverride() {
        val filePath = uniqueApiSettingsFilePath()
        val store = JsonFileApiSettingsStore(filePath)
        val settings = ApiSettings(
            activeApiId = "local",
            apis = listOf(
                ConfiguredApi(
                    id = "prod",
                    name = "Production",
                    baseUrl = "https://api.openai.com/v1",
                    apiKey = "sk-prod",
                    availableModels = listOf("gpt-4.1-mini", "gpt-4.1-nano"),
                    defaultModel = "gpt-4.1-mini",
                    selectedModel = "gpt-4.1-mini",
                ),
                ConfiguredApi(
                    id = "local",
                    name = "Local",
                    baseUrl = "https://localhost:8080/v1",
                    apiKey = "sk-local",
                    availableModels = listOf("gpt-4.1-nano"),
                    defaultModel = "gpt-4.1-nano",
                    selectedModel = "gpt-4.1-nano",
                ),
            ),
            temperature = 0.65,
        )

        store.save(settings)

        assertEquals(0.65, store.load()?.temperature)
    }

    @Test
    fun loadReturnsNullWhenFileIsMissing() {
        val store = JsonFileApiSettingsStore(uniqueApiSettingsFilePath())

        assertNull(store.load())
    }

    @Test
    fun loadReturnsNullWhenFileIsMalformed() {
        val filePath = uniqueApiSettingsFilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(filePath, "{ malformed json")
        val store = JsonFileApiSettingsStore(filePath)

        assertNull(store.load())
    }

    @Test
    fun loadReturnsNullWhenSnapshotVersionIsOld() {
        val filePath = uniqueApiSettingsFilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(
            filePath,
            """
                {
                  "version": 2,
                  "active_api_id": "prod",
                  "apis": [
                    {
                      "id": "prod",
                      "name": "Production",
                      "base_url": "https://api.openai.com/v1",
                      "api_key": "sk-prod",
                      "selected_model": "gpt-4.1-mini"
                    }
                  ]
                }
            """.trimIndent(),
        )
        val store = JsonFileApiSettingsStore(filePath)

        assertNull(store.load())
    }

    @Test
    fun loadReturnsNullWhenDuplicateApiIdsExist() {
        val filePath = uniqueApiSettingsFilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(filePath, validSnapshot().replaceFirst(""""id": "local"""", """"id": "prod""""))
        val store = JsonFileApiSettingsStore(filePath)

        assertNull(store.load())
    }

    @Test
    fun loadReturnsNullWhenDuplicateApiNamesExist() {
        val filePath = uniqueApiSettingsFilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(filePath, validSnapshot().replaceFirst(""""name": "Local"""", """"name": "Production""""))
        val store = JsonFileApiSettingsStore(filePath)

        assertNull(store.load())
    }

    @Test
    fun loadReturnsNullWhenAvailableModelsAreMissing() {
        val filePath = uniqueApiSettingsFilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(
            filePath,
            """
                {
                  "version": 3,
                  "active_api_id": "prod",
                  "apis": [
                    {
                      "id": "prod",
                      "name": "Production",
                      "base_url": "https://api.openai.com/v1",
                      "api_key": "sk-prod",
                      "available_models": [],
                      "default_model": "gpt-4.1-mini",
                      "selected_model": "gpt-4.1-mini"
                    }
                  ]
                }
            """.trimIndent(),
        )
        val store = JsonFileApiSettingsStore(filePath)

        assertNull(store.load())
    }

    @Test
    fun loadReturnsNullWhenDefaultModelIsNotInAvailableModels() {
        val filePath = uniqueApiSettingsFilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(
            filePath,
            """
                {
                  "version": 3,
                  "active_api_id": "prod",
                  "apis": [
                    {
                      "id": "prod",
                      "name": "Production",
                      "base_url": "https://api.openai.com/v1",
                      "api_key": "sk-prod",
                      "available_models": ["gpt-4.1-nano"],
                      "default_model": "gpt-4.1-mini",
                      "selected_model": "gpt-4.1-nano"
                    }
                  ]
                }
            """.trimIndent(),
        )
        val store = JsonFileApiSettingsStore(filePath)

        assertNull(store.load())
    }

    @Test
    fun loadReturnsNullWhenAvailableModelsContainDuplicates() {
        val filePath = uniqueApiSettingsFilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(
            filePath,
            """
                {
                  "version": 3,
                  "active_api_id": "prod",
                  "apis": [
                    {
                      "id": "prod",
                      "name": "Production",
                      "base_url": "https://api.openai.com/v1",
                      "api_key": "sk-prod",
                      "available_models": ["gpt-4.1-mini", "gpt-4.1-mini"],
                      "default_model": "gpt-4.1-mini",
                      "selected_model": "gpt-4.1-mini"
                    }
                  ]
                }
            """.trimIndent(),
        )
        val store = JsonFileApiSettingsStore(filePath)

        assertNull(store.load())
    }

    @Test
    fun loadNormalizesMissingSelectedModelToDefaultModel() {
        val filePath = uniqueApiSettingsFilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(
            filePath,
            """
                {
                  "version": 3,
                  "active_api_id": "prod",
                  "apis": [
                    {
                      "id": "prod",
                      "name": "Production",
                      "base_url": "https://api.openai.com/v1",
                      "api_key": "sk-prod",
                      "available_models": ["gpt-4.1-mini", "gpt-4.1-nano"],
                      "default_model": "gpt-4.1-mini",
                      "selected_model": ""
                    }
                  ]
                }
            """.trimIndent(),
        )
        val store = JsonFileApiSettingsStore(filePath)

        assertEquals("gpt-4.1-mini", store.load()?.activeApiOrNull()?.selectedModel)
    }

    @Test
    fun loadFallsBackToFirstApiWhenActiveApiIdIsMissing() {
        val filePath = uniqueApiSettingsFilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(filePath, validSnapshot().replaceFirst(""""active_api_id": "prod"""", """"active_api_id": "missing""""))
        val store = JsonFileApiSettingsStore(filePath)

        assertEquals("prod", store.load()?.activeApiId)
    }

    @Test
    fun loadTreatsMissingTemperatureAsNull() {
        val filePath = uniqueApiSettingsFilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(filePath, validSnapshot())
        val store = JsonFileApiSettingsStore(filePath)

        assertEquals(null, store.load()?.temperature)
    }

    private fun validSnapshot(): String {
        return """
            {
              "version": 3,
              "active_api_id": "prod",
              "apis": [
                {
                  "id": "prod",
                  "name": "Production",
                  "base_url": "https://api.openai.com/v1",
                  "api_key": "sk-prod",
                  "available_models": ["gpt-4.1-mini", "gpt-4.1-nano"],
                  "default_model": "gpt-4.1-mini",
                  "selected_model": "gpt-4.1-mini"
                },
                {
                  "id": "local",
                  "name": "Local",
                  "base_url": "https://localhost:8080/v1",
                  "api_key": "sk-local",
                  "available_models": ["gpt-4.1-nano"],
                  "default_model": "gpt-4.1-nano",
                  "selected_model": "gpt-4.1-nano"
                }
              ]
            }
        """.trimIndent()
    }
}

private fun uniqueApiSettingsFilePath(): String {
    val seed = Random.nextLong().toString().replace('-', '0')
    return "/tmp/kotlin-agent-cli-tests/$seed/api-settings.json"
}

private fun parentDirectory(path: String): String {
    val normalized = path.trimEnd('/')
    val separatorIndex = normalized.lastIndexOf('/')
    return if (separatorIndex <= 0) "/" else normalized.substring(0, separatorIndex)
}

@OptIn(ExperimentalForeignApi::class)
private fun ensureDirectoryExists(path: String) {
    if (path.isBlank() || path == "/") return

    val parent = parentDirectory(path)
    if (parent != path) {
        ensureDirectoryExists(parent)
    }

    val result = mkdir(path, 493.convert<mode_t>())
    if (result == 0 || errno == EEXIST) return
    error("Failed to create test directory '$path'.")
}

@OptIn(ExperimentalForeignApi::class)
private fun writeTextFile(path: String, text: String) {
    val file = fopen(path, "w") ?: error("Unable to open test file '$path'.")
    try {
        if (fputs(text, file) < 0) {
            error("Unable to write test file '$path'.")
        }
    } finally {
        fclose(file)
    }
}
