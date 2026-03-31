package com.aichallenge.day2.agent

import com.aichallenge.day2.agent.core.config.ApiSettings
import com.aichallenge.day2.agent.core.config.AppConfig
import com.aichallenge.day2.agent.core.config.ConfiguredApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AppMainConfigNormalizationTest {
    @Test
    fun normalizeApiSettingsForCatalogPreservesUnknownModelsAndKeepsSelectedModelWhenValid() {
        val result = normalizeApiSettingsForCatalog(
            settings = ApiSettings(
                activeApiId = "prod",
                apis = listOf(
                    ConfiguredApi(
                        id = "prod",
                        name = "Production",
                        baseUrl = "https://api.openai.com/v1",
                        apiKey = "test-key",
                        availableModels = listOf("gpt-4.1-mini", "unknown-model", "gpt-4.1-nano"),
                        defaultModel = "gpt-4.1-mini",
                        selectedModel = "gpt-4.1-nano",
                    ),
                ),
            ),
            config = AppConfig(
                models = AppConfig.internalModelCatalog(),
                systemPrompt = "prompt",
                apiTrafficLogFilePath = null,
                wireAppRagBaseUrl = "http://localhost:8000",
            ),
        )

        assertEquals(listOf("gpt-4.1-mini", "unknown-model", "gpt-4.1-nano"), result?.activeApiOrNull()?.availableModels)
        assertEquals("gpt-4.1-nano", result?.activeApiOrNull()?.selectedModel)
    }

    @Test
    fun normalizeApiSettingsForCatalogFallsBackSelectedModelToDefaultModel() {
        val result = normalizeApiSettingsForCatalog(
            settings = ApiSettings(
                activeApiId = "prod",
                apis = listOf(
                    ConfiguredApi(
                        id = "prod",
                        name = "Production",
                        baseUrl = "https://api.openai.com/v1",
                        apiKey = "test-key",
                        availableModels = listOf("gpt-4.1-mini", "gpt-4.1-nano"),
                        defaultModel = "gpt-4.1-mini",
                        selectedModel = "unknown-model",
                    ),
                ),
            ),
            config = AppConfig(
                models = AppConfig.internalModelCatalog(),
                systemPrompt = "prompt",
                apiTrafficLogFilePath = null,
                wireAppRagBaseUrl = "http://localhost:8000",
            ),
        )

        assertEquals("gpt-4.1-mini", result?.activeApiOrNull()?.selectedModel)
    }

    @Test
    fun normalizeApiSettingsForCatalogKeepsApisWhoseModelsAreNotInBuiltInCatalog() {
        val result = normalizeApiSettingsForCatalog(
            settings = ApiSettings(
                activeApiId = "prod",
                apis = listOf(
                    ConfiguredApi(
                        id = "prod",
                        name = "Production",
                        baseUrl = "https://api.openai.com/v1",
                        apiKey = "test-key",
                        availableModels = listOf("unknown-model"),
                        defaultModel = "unknown-model",
                        selectedModel = "unknown-model",
                    ),
                ),
            ),
            config = AppConfig(
                models = AppConfig.internalModelCatalog(),
                systemPrompt = "prompt",
                apiTrafficLogFilePath = null,
                wireAppRagBaseUrl = "http://localhost:8000",
            ),
        )

        assertNotNull(result)
        assertEquals(listOf("unknown-model"), result.activeApiOrNull()?.availableModels)
        assertEquals("unknown-model", result.activeApiOrNull()?.selectedModel)
    }

    @Test
    fun normalizeApiSettingsForCatalogKeepsDefaultModelOutsideBuiltInCatalogWhenApiConfigIsInternallyConsistent() {
        val result = normalizeApiSettingsForCatalog(
            settings = ApiSettings(
                activeApiId = "prod",
                apis = listOf(
                    ConfiguredApi(
                        id = "prod",
                        name = "Production",
                        baseUrl = "https://api.openai.com/v1",
                        apiKey = "test-key",
                        availableModels = listOf("unknown-model", "gpt-4.1-nano"),
                        defaultModel = "unknown-model",
                        selectedModel = "gpt-4.1-nano",
                    ),
                ),
            ),
            config = AppConfig(
                models = AppConfig.internalModelCatalog(),
                systemPrompt = "prompt",
                apiTrafficLogFilePath = null,
                wireAppRagBaseUrl = "http://localhost:8000",
            ),
        )

        assertNotNull(result)
        assertEquals("unknown-model", result.activeApiOrNull()?.defaultModel)
        assertEquals(listOf("unknown-model", "gpt-4.1-nano"), result.activeApiOrNull()?.availableModels)
    }
}
