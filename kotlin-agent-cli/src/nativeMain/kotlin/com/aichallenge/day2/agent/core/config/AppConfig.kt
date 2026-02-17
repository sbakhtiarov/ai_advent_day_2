package com.aichallenge.day2.agent.core.config

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
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

        fun fromEnvironment(): AppConfig {
            val apiKey = readEnv("OPENAI_API_KEY").orEmpty().trim()
            require(apiKey.isNotEmpty()) {
                "OPENAI_API_KEY is required."
            }

            val model = readEnv("OPENAI_MODEL").orEmpty().trim().ifEmpty { DEFAULT_MODEL }
            val baseUrl = readEnv("OPENAI_BASE_URL").orEmpty().trim().ifEmpty { DEFAULT_BASE_URL }
            val systemPrompt = readEnv("AGENT_SYSTEM_PROMPT")
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
        private fun readEnv(name: String): String? = getenv(name)?.toKString()
    }
}
