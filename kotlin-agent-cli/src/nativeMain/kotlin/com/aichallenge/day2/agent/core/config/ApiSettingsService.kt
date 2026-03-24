package com.aichallenge.day2.agent.core.config

interface ApiSettingsService {
    fun currentSettings(): ApiSettings?

    fun currentProvider(): ApiProvider? = currentSettings()?.activeProvider

    fun currentProviderSettings(): ApiProviderSettings? = currentSettings()?.activeProviderSettingsOrNull()

    fun replace(settings: ApiSettings?)
}

class MutableApiSettingsService(
    initialSettings: ApiSettings? = null,
) : ApiSettingsService {
    private var settings: ApiSettings? = initialSettings?.normalizedOrNull()

    override fun currentSettings(): ApiSettings? = settings

    override fun replace(settings: ApiSettings?) {
        this.settings = settings?.normalizedOrNull()
    }
}

