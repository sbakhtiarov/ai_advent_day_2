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

data class AppConfig(
    val apiKey: String,
    val model: String,
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

        fun fromEnvironment(): AppConfig {
            val localProperties = loadLocalProperties()
            val apiKey = readConfig("OPENAI_API_KEY", localProperties).orEmpty().trim()
            require(apiKey.isNotEmpty()) {
                "OPENAI_API_KEY is required."
            }

            val model = readConfig("OPENAI_MODEL", localProperties).orEmpty().trim().ifEmpty { DEFAULT_MODEL }
            val baseUrl = readConfig("OPENAI_BASE_URL", localProperties).orEmpty().trim().ifEmpty { DEFAULT_BASE_URL }
            val systemPrompt = readConfig("AGENT_SYSTEM_PROMPT", localProperties)
                .orEmpty()
                .trim()
                .ifEmpty { DEFAULT_SYSTEM_PROMPT }

            return AppConfig(
                apiKey = apiKey,
                model = model,
                baseUrl = baseUrl.trimEnd('/'),
                systemPrompt = systemPrompt,
            )
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
    }
}
