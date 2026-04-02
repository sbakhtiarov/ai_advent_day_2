package com.aichallenge.day2.agent.server

object JvmModelCatalog {
    private val contextWindowsByModelId = mapOf(
        "gpt-5.2-codex" to 400_000,
        "gpt-5.2-pro" to 400_000,
        "gpt-5.2" to 400_000,
        "gpt-5.1" to 400_000,
        "gpt-5" to 400_000,
        "gpt-5-mini" to 400_000,
        "gpt-5-nano" to 400_000,
        "gpt-4.1-mini" to 1_047_576,
        "gpt-4.1-nano" to 1_047_576,
        "gpt-3.5-turbo" to 16_385,
    )

    fun contextWindowTokens(modelId: String): Int? = contextWindowsByModelId[modelId.trim()]
}
