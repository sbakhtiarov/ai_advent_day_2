package com.aichallenge.day2.agent.core.config

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.setenv
import platform.posix.unsetenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppConfigTest {
    @Test
    fun internalModelCatalogMatchesExpectedModelsInOrder() {
        val expectedCatalog = listOf(
            ModelProperties(
                id = "gpt-5.2-codex",
                pricing = ModelPricing(
                    inputUsdPer1M = 1.75,
                    outputUsdPer1M = 14.0,
                ),
                contextWindowTokens = 400_000,
            ),
            ModelProperties(
                id = "gpt-5.2-pro",
                pricing = ModelPricing(
                    inputUsdPer1M = 21.0,
                    outputUsdPer1M = 168.0,
                ),
                contextWindowTokens = 400_000,
            ),
            ModelProperties(
                id = "gpt-5.2",
                pricing = ModelPricing(
                    inputUsdPer1M = 1.75,
                    outputUsdPer1M = 14.0,
                ),
                contextWindowTokens = 400_000,
            ),
            ModelProperties(
                id = "gpt-5.1",
                pricing = ModelPricing(
                    inputUsdPer1M = 1.25,
                    outputUsdPer1M = 10.0,
                ),
                contextWindowTokens = 400_000,
            ),
            ModelProperties(
                id = "gpt-5",
                pricing = ModelPricing(
                    inputUsdPer1M = 1.25,
                    outputUsdPer1M = 10.0,
                ),
                contextWindowTokens = 400_000,
            ),
            ModelProperties(
                id = "gpt-5-mini",
                pricing = ModelPricing(
                    inputUsdPer1M = 0.25,
                    outputUsdPer1M = 2.0,
                ),
                contextWindowTokens = 400_000,
            ),
            ModelProperties(
                id = "gpt-5-nano",
                pricing = ModelPricing(
                    inputUsdPer1M = 0.05,
                    outputUsdPer1M = 0.40,
                ),
                contextWindowTokens = 400_000,
            ),
            ModelProperties(
                id = "gpt-4.1-mini",
                pricing = ModelPricing(
                    inputUsdPer1M = 0.40,
                    outputUsdPer1M = 1.60,
                ),
                contextWindowTokens = 1_047_576,
            ),
            ModelProperties(
                id = "gpt-4.1-nano",
                pricing = ModelPricing(
                    inputUsdPer1M = 0.10,
                    outputUsdPer1M = 0.40,
                ),
                contextWindowTokens = 1_047_576,
            ),
            ModelProperties(
                id = "gpt-3.5-turbo",
                pricing = ModelPricing(
                    inputUsdPer1M = 0.50,
                    outputUsdPer1M = 1.50,
                ),
                contextWindowTokens = 16_385,
            ),
        )

        assertEquals(expectedCatalog, AppConfig.internalModelCatalog())
    }

    @Test
    fun defaultModelIsPresentInInternalCatalog() {
        val modelIds = AppConfig.internalModelCatalog().map { it.id }
        assertTrue(AppConfig.defaultModelId() in modelIds)
    }

    @Test
    fun deprecatedModelEnvKeysDoNotAffectConfiguration() {
        withEnvironment(
            mapOf(
                "OPENAI_API_KEY" to "test-api-key",
                "OPENAI_MODEL" to "unexpected-model",
                "OPENAI_MODELS" to "unexpected-model,another-model",
                "OPENAI_MODEL_PRICING" to "unexpected-model=broken-pricing",
            ),
        ) {
            val config = AppConfig.fromEnvironment()

            assertEquals(AppConfig.defaultModelId(), config.model)
            assertEquals(
                AppConfig.internalModelCatalog().map { it.id },
                config.models.map { it.id },
            )
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun withEnvironment(
        overrides: Map<String, String>,
        block: () -> Unit,
    ) {
        val previousValues = overrides.keys.associateWith { key ->
            getenv(key)?.toKString()
        }

        try {
            overrides.forEach { (key, value) ->
                setenv(key, value, 1)
            }
            block()
        } finally {
            previousValues.forEach { (key, previousValue) ->
                if (previousValue == null) {
                    unsetenv(key)
                } else {
                    setenv(key, previousValue, 1)
                }
            }
        }
    }
}
