package com.aichallenge.day2.agent.core.config

enum class ApiProvider(
    val displayName: String,
) {
    OPENAI("OpenAI"),
    OLLAMA("Ollama"),
    ;

    companion object {
        fun fromStorageValue(value: String?): ApiProvider? {
            return entries.firstOrNull { provider ->
                provider.name.equals(value?.trim(), ignoreCase = true)
            }
        }
    }
}

data class ApiProviderSettings(
    val baseUrl: String,
    val apiKey: String,
    val selectedModel: String,
) {
    fun normalizedOrNull(): ApiProviderSettings? {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val normalizedApiKey = apiKey.trim()
        val normalizedSelectedModel = selectedModel.trim()
        if (normalizedBaseUrl.isEmpty() || normalizedSelectedModel.isEmpty()) {
            return null
        }
        return ApiProviderSettings(
            baseUrl = normalizedBaseUrl,
            apiKey = normalizedApiKey,
            selectedModel = normalizedSelectedModel,
        )
    }
}

data class ApiSettings(
    val activeProvider: ApiProvider,
    val openAi: ApiProviderSettings?,
    val ollama: ApiProviderSettings?,
) {
    fun activeProviderSettingsOrNull(): ApiProviderSettings? = validatedSettingsFor(activeProvider)

    fun settingsFor(provider: ApiProvider): ApiProviderSettings? {
        return when (provider) {
            ApiProvider.OPENAI -> openAi
            ApiProvider.OLLAMA -> ollama
        }?.normalizedOrNull()
    }

    fun validatedSettingsFor(provider: ApiProvider): ApiProviderSettings? {
        val settings = settingsFor(provider) ?: return null
        return when (provider) {
            ApiProvider.OPENAI -> settings.takeIf { it.apiKey.isNotBlank() }
            ApiProvider.OLLAMA -> settings
        }
    }

    fun normalizedOrNull(): ApiSettings? {
        val normalizedOpenAi = openAi?.normalizedOrNull()
        val normalizedOllama = ollama?.normalizedOrNull()
        val normalized = ApiSettings(
            activeProvider = activeProvider,
            openAi = normalizedOpenAi,
            ollama = normalizedOllama,
        )
        return if (normalized.validatedSettingsFor(activeProvider) == null) {
            null
        } else {
            normalized
        }
    }
}
