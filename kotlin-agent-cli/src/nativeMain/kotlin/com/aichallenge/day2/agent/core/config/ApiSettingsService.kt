package com.aichallenge.day2.agent.core.config

interface ApiSettingsService {
    fun currentSettings(): ApiSettings?

    fun currentApi(): ConfiguredApi? = currentSettings()?.activeApiOrNull()

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
