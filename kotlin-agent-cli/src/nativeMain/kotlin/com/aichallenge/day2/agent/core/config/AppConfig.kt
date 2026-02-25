package com.aichallenge.day2.agent.core.config

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.getcwd
import platform.posix.getenv

data class ModelPricing(
    val inputUsdPer1M: Double,
    val outputUsdPer1M: Double,
)

data class ModelProperties(
    val id: String,
    val pricing: ModelPricing,
    val contextWindowTokens: Int,
)

data class AppConfig(
    val apiKey: String,
    val model: String,
    val models: List<ModelProperties>,
    val baseUrl: String,
    val systemPrompt: String,
) {
    companion object {
        private const val DEFAULT_MODEL = "gpt-4.1-mini"
        private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        private const val DEFAULT_SYSTEM_PROMPT =
            "You are a concise and pragmatic assistant. Ask for clarification only when needed."
        private const val LOCAL_PROPERTIES_FILE = "local.properties"
        private const val READ_BUFFER_SIZE = 4096

        private val INTERNAL_MODEL_CATALOG = listOf(
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

        fun fromEnvironment(): AppConfig {
            val localProperties = loadLocalProperties()
            val apiKey = readConfig("OPENAI_API_KEY", localProperties).orEmpty().trim()
            require(apiKey.isNotEmpty()) {
                "OPENAI_API_KEY is required."
            }

            val models = internalModelCatalog()
            validateModelCatalog(models)
            val baseUrl = readConfig("OPENAI_BASE_URL", localProperties).orEmpty().trim().ifEmpty { DEFAULT_BASE_URL }
            val systemPrompt = readConfig("AGENT_SYSTEM_PROMPT", localProperties)
                .orEmpty()
                .trim()
                .ifEmpty { DEFAULT_SYSTEM_PROMPT }

            return AppConfig(
                apiKey = apiKey,
                model = DEFAULT_MODEL,
                models = models,
                baseUrl = baseUrl.trimEnd('/'),
                systemPrompt = systemPrompt,
            )
        }

        internal fun internalModelCatalog(): List<ModelProperties> = INTERNAL_MODEL_CATALOG.toList()

        internal fun defaultModelId(): String = DEFAULT_MODEL

        @OptIn(ExperimentalForeignApi::class)
        private fun readConfig(name: String, localProperties: Map<String, String>): String? {
            val envValue = readEnv(name)?.trim().orEmpty()
            if (envValue.isNotEmpty()) {
                return envValue
            }
            return localProperties[name]
        }

        @OptIn(ExperimentalForeignApi::class)
        private fun readEnv(name: String): String? = getenv(name)?.toKString()

        @OptIn(ExperimentalForeignApi::class)
        private fun loadLocalProperties(): Map<String, String> {
            val cwd = currentDirectory() ?: return emptyMap()
            val candidates = discoverLocalPropertiesPaths(cwd)

            for (path in candidates) {
                val content = readTextFile(path) ?: continue
                val parsed = parseProperties(content)
                if (parsed.isNotEmpty()) {
                    return parsed
                }
            }

            return emptyMap()
        }

        private fun discoverLocalPropertiesPaths(cwd: String): List<String> {
            val paths = linkedSetOf<String>()
            var current = normalizeDirectory(cwd)

            while (true) {
                paths += "$current/$LOCAL_PROPERTIES_FILE"
                paths += "$current/kotlin-agent-cli/$LOCAL_PROPERTIES_FILE"

                val parent = parentDirectory(current) ?: break
                if (parent == current) break
                current = parent
            }

            return paths.toList()
        }

        private fun normalizeDirectory(path: String): String {
            if (path == "/") return path
            return path.trimEnd('/').ifEmpty { "/" }
        }

        private fun parentDirectory(path: String): String? {
            val normalized = normalizeDirectory(path)
            if (normalized == "/") return null

            val index = normalized.lastIndexOf('/')
            return if (index <= 0) "/" else normalized.substring(0, index)
        }

        @OptIn(ExperimentalForeignApi::class)
        private fun currentDirectory(): String? = memScoped {
            val buffer = allocArray<ByteVar>(READ_BUFFER_SIZE)
            getcwd(buffer, READ_BUFFER_SIZE.toULong())?.toKString()
        }

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

        private fun parseProperties(content: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            content.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
                .forEach { line ->
                    val separatorIndex = line.indexOf('=').takeIf { it >= 0 } ?: line.indexOf(':')
                    if (separatorIndex <= 0) return@forEach
                    val key = line.substring(0, separatorIndex).trim()
                    val rawValue = line.substring(separatorIndex + 1).trim()
                    if (key.isEmpty()) return@forEach
                    result[key] = normalizeValue(rawValue)
                }
            return result
        }

        private fun normalizeValue(value: String): String {
            if (value.length < 2) return value
            val startsWithQuote = value.startsWith("\"") && value.endsWith("\"")
            val startsWithSingleQuote = value.startsWith("'") && value.endsWith("'")
            return if (startsWithQuote || startsWithSingleQuote) value.substring(1, value.length - 1) else value
        }

        private fun validateModelCatalog(models: List<ModelProperties>) {
            require(models.isNotEmpty()) {
                "Internal model catalog must not be empty."
            }

            val uniqueIds = mutableSetOf<String>()
            models.forEach { model ->
                require(model.id.isNotBlank()) {
                    "Model id must not be blank."
                }
                require(uniqueIds.add(model.id)) {
                    "Duplicate model id in internal catalog: '${model.id}'."
                }
                require(model.pricing.inputUsdPer1M >= 0.0) {
                    "Model '${model.id}' has invalid input rate: ${model.pricing.inputUsdPer1M}."
                }
                require(model.pricing.outputUsdPer1M >= 0.0) {
                    "Model '${model.id}' has invalid output rate: ${model.pricing.outputUsdPer1M}."
                }
                require(model.contextWindowTokens > 0) {
                    "Model '${model.id}' has invalid context window: ${model.contextWindowTokens}."
                }
            }

            require(models.any { it.id == DEFAULT_MODEL }) {
                "Default model '$DEFAULT_MODEL' must be present in the internal model catalog."
            }
        }
    }
}
