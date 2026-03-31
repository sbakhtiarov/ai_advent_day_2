package com.aichallenge.day2.agent.core.config

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.chdir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.getcwd
import platform.posix.mkdir
import platform.posix.remove
import platform.posix.rmdir
import platform.posix.setenv
import platform.posix.unsetenv
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppConfigTest {
    private val tempPathSuffix = Random.nextInt(1_000_000)

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
    fun deprecatedModelEnvKeysDoNotAffectModelCatalog() {
        withEnvironment(
            mapOf(
                "OPENAI_MODEL" to "unexpected-model",
                "OPENAI_MODELS" to "unexpected-model,another-model",
                "OPENAI_MODEL_PRICING" to "unexpected-model=broken-pricing",
            ),
        ) {
            val config = AppConfig.fromEnvironment()

            assertEquals(
                AppConfig.internalModelCatalog().map { it.id },
                config.models.map { it.id },
            )
        }
    }

    @Test
    fun agentSystemPromptEnvKeyDoesNotAffectConfiguration() {
        withEnvironment(
            mapOf(
                "AGENT_SYSTEM_PROMPT" to "unexpected override",
            ),
        ) {
            val config = AppConfig.fromEnvironment()

            assertEquals(
                "You are a concise and pragmatic assistant. Ask for clarification only when needed.",
                config.systemPrompt,
            )
        }
    }

    @Test
    fun defaultApiTrafficLogFileUsesHomeDirectory() {
        withEnvironment(
            mapOf(
                "HOME" to "/tmp/app-config-home",
                "OPENAI_API_LOG_FILE" to "",
            ),
        ) {
            unsetEnvironment("OPENAI_API_LOG_FILE")

            val config = AppConfig.fromEnvironment()

            assertEquals(
                "/tmp/app-config-home/.kotlin-agent-cli/openai-api-traffic.log",
                config.apiTrafficLogFilePath,
            )
        }
    }

    @Test
    fun explicitBlankApiTrafficLogFileDisablesLogging() {
        withEnvironment(
            mapOf(
                "HOME" to "/tmp/app-config-home",
                "OPENAI_API_LOG_FILE" to "",
            ),
        ) {
            val config = AppConfig.fromEnvironment()

            assertEquals(null, config.apiTrafficLogFilePath)
        }
    }

    @Test
    fun explicitApiTrafficLogFileOverridesDefaultLocation() {
        withEnvironment(
            mapOf(
                "HOME" to "/tmp/app-config-home",
                "OPENAI_API_LOG_FILE" to "/tmp/custom-openai.log",
            ),
        ) {
            val config = AppConfig.fromEnvironment()

            assertEquals("/tmp/custom-openai.log", config.apiTrafficLogFilePath)
        }
    }

    @Test
    fun wireAppRagBaseUrlDefaultsToLocalhost() {
        withEnvironment(
            mapOf(
                "WIRE_APP_RAG_BASE_URL" to "",
            ),
        ) {
            unsetEnvironment("WIRE_APP_RAG_BASE_URL")

            val config = AppConfig.fromEnvironment()

            assertEquals("http://localhost:8000", config.wireAppRagBaseUrl)
        }
    }

    @Test
    fun explicitWireAppRagBaseUrlOverridesDefaultLocation() {
        withEnvironment(
            mapOf(
                "WIRE_APP_RAG_BASE_URL" to "http://wire-rag.internal:8123",
            ),
        ) {
            val config = AppConfig.fromEnvironment()

            assertEquals("http://wire-rag.internal:8123", config.wireAppRagBaseUrl)
        }
    }

    @Test
    fun localPropertiesWireAppRagBaseUrlIsUsedWhenEnvironmentIsUnset() {
        withTemporaryWorkingDirectory(
            localPropertiesContent = "WIRE_APP_RAG_BASE_URL=http://localhost:9001\n",
        ) {
            unsetEnvironment("WIRE_APP_RAG_BASE_URL")

            val config = AppConfig.fromEnvironment()

            assertEquals("http://localhost:9001", config.wireAppRagBaseUrl)
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

    @OptIn(ExperimentalForeignApi::class)
    private fun unsetEnvironment(name: String) {
        unsetenv(name)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun withTemporaryWorkingDirectory(
        localPropertiesContent: String,
        block: () -> Unit,
    ) {
        val originalDirectory = currentDirectory()
        val tempDirectory = "/tmp/kotlin-agent-cli-config-$tempPathSuffix-${Random.nextInt(1_000_000)}"
        check(mkdir(tempDirectory, 511u) == 0) {
            "Failed to create temporary directory: $tempDirectory"
        }

        val localPropertiesPath = "$tempDirectory/local.properties"
        val file = fopen(localPropertiesPath, "w")
            ?: error("Failed to create $localPropertiesPath")
        try {
            fputs(localPropertiesContent, file)
        } finally {
            fclose(file)
        }

        check(chdir(tempDirectory) == 0) {
            "Failed to change directory to $tempDirectory"
        }

        try {
            block()
        } finally {
            check(chdir(originalDirectory) == 0) {
                "Failed to restore working directory to $originalDirectory"
            }
            remove(localPropertiesPath)
            rmdir(tempDirectory)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun currentDirectory(): String = memScoped {
        val buffer = allocArray<ByteVar>(4096)
        getcwd(buffer, 4096u)?.toKString()
            ?: error("Failed to resolve current working directory")
    }
}
