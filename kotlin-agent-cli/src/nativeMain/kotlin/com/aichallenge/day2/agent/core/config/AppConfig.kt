package com.aichallenge.day2.agent.core.config

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
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
    val pricing: ModelPricing? = null,
    val contextWindowTokens: Int? = null,
)

data class AppConfig(
    val fallbackApiSettings: ApiSettings?,
    val openAiModels: List<ModelProperties>,
    val ollamaModels: List<ModelProperties>,
    val systemPrompt: String,
    val apiTrafficLogFilePath: String?,
) {
    fun modelsFor(provider: ApiProvider): List<ModelProperties> {
        return when (provider) {
            ApiProvider.OPENAI -> openAiModels
            ApiProvider.OLLAMA -> ollamaModels
        }
    }

    companion object {
        private const val DEFAULT_OPENAI_MODEL = "gpt-4.1-mini"
        private const val DEFAULT_OLLAMA_MODEL = "qwen3:8b"
        private const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
        private const val DEFAULT_OLLAMA_BASE_URL = "http://127.0.0.1:11434/v1"
        private const val DEFAULT_SYSTEM_PROMPT =
            "You are a concise and pragmatic assistant. Ask for clarification only when needed."
        private const val DEFAULT_API_TRAFFIC_LOG_FILE = ".kotlin-agent-cli/openai-api-traffic.log"
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

        private val OLLAMA_MODEL_CATALOG = listOf(
            ModelProperties(
                id = DEFAULT_OLLAMA_MODEL,
            ),
        )

        fun fromEnvironment(): AppConfig {
            val localProperties = loadLocalProperties()
            val openAiModels = internalModelCatalog()
            val ollamaModels = ollamaModelCatalog()
            validateModelCatalog(openAiModels, requirePricing = true, requireContextWindow = true)
            validateModelCatalog(ollamaModels, requirePricing = false, requireContextWindow = false)

            val apiKey = readConfig("OPENAI_API_KEY", localProperties).orEmpty().trim()
            val baseUrl = readConfig("OPENAI_BASE_URL", localProperties).orEmpty().trim()
                .ifEmpty { DEFAULT_OPENAI_BASE_URL }
            val configuredApiTrafficLogPath = readConfigAllowingBlank("OPENAI_API_LOG_FILE", localProperties)
            val apiTrafficLogFilePath = when {
                configuredApiTrafficLogPath == null -> defaultApiTrafficLogFilePath()
                configuredApiTrafficLogPath.isBlank() -> null
                else -> configuredApiTrafficLogPath.trim()
            }
            val fallbackApiSettings = apiKey.takeIf { it.isNotEmpty() }?.let { normalizedApiKey ->
                ApiSettings(
                    activeProvider = ApiProvider.OPENAI,
                    openAi = ApiProviderSettings(
                        baseUrl = baseUrl.trimEnd('/'),
                        apiKey = normalizedApiKey,
                        selectedModel = DEFAULT_OPENAI_MODEL,
                    ),
                    ollama = null,
                )
            }

            return AppConfig(
                fallbackApiSettings = fallbackApiSettings,
                openAiModels = openAiModels,
                ollamaModels = ollamaModels,
                systemPrompt = DEFAULT_SYSTEM_PROMPT,
                apiTrafficLogFilePath = apiTrafficLogFilePath,
            )
        }

        internal fun internalModelCatalog(): List<ModelProperties> = INTERNAL_MODEL_CATALOG.toList()

        internal fun ollamaModelCatalog(): List<ModelProperties> = OLLAMA_MODEL_CATALOG.toList()

        internal fun defaultModelId(provider: ApiProvider = ApiProvider.OPENAI): String {
            return when (provider) {
                ApiProvider.OPENAI -> DEFAULT_OPENAI_MODEL
                ApiProvider.OLLAMA -> DEFAULT_OLLAMA_MODEL
            }
        }

        internal fun defaultBaseUrl(provider: ApiProvider): String {
            return when (provider) {
                ApiProvider.OPENAI -> DEFAULT_OPENAI_BASE_URL
                ApiProvider.OLLAMA -> DEFAULT_OLLAMA_BASE_URL
            }
        }

        @OptIn(ExperimentalForeignApi::class)
        private fun readConfig(name: String, localProperties: Map<String, String>): String? {
            val envValue = readEnv(name)?.trim().orEmpty()
            if (envValue.isNotEmpty()) {
                return envValue
            }
            return localProperties[name]
        }

        @OptIn(ExperimentalForeignApi::class)
        private fun readConfigAllowingBlank(name: String, localProperties: Map<String, String>): String? {
            val envValue = readEnv(name)
            if (envValue != null) {
                return envValue
            }
            return if (localProperties.containsKey(name)) {
                localProperties[name].orEmpty()
            } else {
                null
            }
        }

        @OptIn(ExperimentalForeignApi::class)
        private fun readEnv(name: String): String? = getenv(name)?.toKString()

        @OptIn(ExperimentalForeignApi::class)
        private fun defaultApiTrafficLogFilePath(): String? {
            val homeDirectory = readEnv("HOME")?.trim().orEmpty()
            if (homeDirectory.isEmpty()) {
                return null
            }
            return "${homeDirectory.trimEnd('/')}/$DEFAULT_API_TRAFFIC_LOG_FILE"
        }

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

        private fun validateModelCatalog(
            models: List<ModelProperties>,
            requirePricing: Boolean,
            requireContextWindow: Boolean,
        ) {
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
                if (requirePricing) {
                    requireNotNull(model.pricing) {
                        "Model '${model.id}' must define pricing."
                    }
                    require(model.pricing.inputUsdPer1M >= 0.0) {
                        "Model '${model.id}' has invalid input rate: ${model.pricing.inputUsdPer1M}."
                    }
                    require(model.pricing.outputUsdPer1M >= 0.0) {
                        "Model '${model.id}' has invalid output rate: ${model.pricing.outputUsdPer1M}."
                    }
                }
                if (requireContextWindow) {
                    requireNotNull(model.contextWindowTokens) {
                        "Model '${model.id}' must define a context window."
                    }
                    require(model.contextWindowTokens > 0) {
                        "Model '${model.id}' has invalid context window: ${model.contextWindowTokens}."
                    }
                }
            }

            val expectedDefaultModel = if (requirePricing) DEFAULT_OPENAI_MODEL else DEFAULT_OLLAMA_MODEL
            require(models.any { it.id == expectedDefaultModel }) {
                "Default model '$expectedDefaultModel' must be present in the internal model catalog."
            }
        }
    }
}
