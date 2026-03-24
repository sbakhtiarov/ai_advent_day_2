package com.aichallenge.day2.agent.data.local

import com.aichallenge.day2.agent.core.config.ApiProvider
import com.aichallenge.day2.agent.core.config.ApiProviderSettings
import com.aichallenge.day2.agent.core.config.ApiSettings
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
    fun saveAndLoadRoundTripPreservesProviders() {
        val filePath = uniqueApiSettingsFilePath()
        val store = JsonFileApiSettingsStore(filePath)
        val settings = ApiSettings(
            activeProvider = ApiProvider.OLLAMA,
            openAi = ApiProviderSettings(
                baseUrl = "https://api.openai.com/v1",
                apiKey = "sk-test",
                selectedModel = "gpt-4.1-mini",
            ),
            ollama = ApiProviderSettings(
                baseUrl = "http://127.0.0.1:11434/v1",
                apiKey = "ollama",
                selectedModel = "qwen3:8b",
            ),
        )

        store.save(settings)

        assertEquals(settings, store.load())
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
    fun loadReturnsNullWhenActiveProviderIsIncomplete() {
        val filePath = uniqueApiSettingsFilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(
            filePath,
            """
                {
                  "version": 1,
                  "active_provider": "OPENAI",
                  "openai": {
                    "base_url": "https://api.openai.com/v1",
                    "api_key": "",
                    "selected_model": "gpt-4.1-mini"
                  }
                }
            """.trimIndent(),
        )
        val store = JsonFileApiSettingsStore(filePath)

        assertNull(store.load())
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
