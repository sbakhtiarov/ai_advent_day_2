package com.aichallenge.day2.agent.core.config

data class ConfiguredApi(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val availableModels: List<String>,
    val defaultModel: String,
    val selectedModel: String,
) {
    fun normalizedOrNull(): ConfiguredApi? {
        val normalizedId = id.trim()
        val normalizedName = name.trim()
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val normalizedApiKey = apiKey.trim()
        val normalizedAvailableModels = availableModels.map { modelId -> modelId.trim() }
        val normalizedDefaultModel = defaultModel.trim()
        val normalizedSelectedModel = selectedModel.trim()
        if (
            normalizedId.isEmpty() ||
            normalizedName.isEmpty() ||
            normalizedBaseUrl.isEmpty() ||
            normalizedApiKey.isEmpty()
        ) {
            return null
        }
        if (normalizedAvailableModels.isEmpty() || normalizedAvailableModels.any { modelId -> modelId.isEmpty() }) {
            return null
        }
        if (normalizedAvailableModels.distinct().size != normalizedAvailableModels.size) {
            return null
        }
        if (normalizedDefaultModel.isEmpty() || normalizedDefaultModel !in normalizedAvailableModels) {
            return null
        }
        return ConfiguredApi(
            id = normalizedId,
            name = normalizedName,
            baseUrl = normalizedBaseUrl,
            apiKey = normalizedApiKey,
            availableModels = normalizedAvailableModels,
            defaultModel = normalizedDefaultModel,
            selectedModel = normalizedSelectedModel.takeIf { modelId ->
                modelId.isNotEmpty() && modelId in normalizedAvailableModels
            } ?: normalizedDefaultModel,
        )
    }
}

data class ApiSettings(
    val activeApiId: String,
    val apis: List<ConfiguredApi>,
) {
    fun activeApiOrNull(): ConfiguredApi? {
        val normalized = normalizedOrNull() ?: return null
        return normalized.apis.firstOrNull { api -> api.id == normalized.activeApiId }
    }

    fun apiById(id: String): ConfiguredApi? {
        val normalizedId = id.trim()
        if (normalizedId.isEmpty()) {
            return null
        }
        return normalizedOrNull()?.apis?.firstOrNull { api -> api.id == normalizedId }
    }

    fun normalizedOrNull(): ApiSettings? {
        val normalizedApis = apis.mapNotNull { api -> api.normalizedOrNull() }
        if (normalizedApis.size != apis.size || normalizedApis.isEmpty()) {
            return null
        }

        val ids = normalizedApis.map(ConfiguredApi::id)
        if (ids.distinct().size != ids.size) {
            return null
        }

        val names = normalizedApis.map(ConfiguredApi::name)
        if (names.distinct().size != names.size) {
            return null
        }

        val normalizedActiveApiId = activeApiId.trim()
            .takeIf { id -> id.isNotEmpty() && ids.contains(id) }
            ?: ids.first()

        return ApiSettings(
            activeApiId = normalizedActiveApiId,
            apis = normalizedApis,
        )
    }
}
