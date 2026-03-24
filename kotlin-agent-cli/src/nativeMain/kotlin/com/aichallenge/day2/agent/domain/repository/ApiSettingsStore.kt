package com.aichallenge.day2.agent.domain.repository

import com.aichallenge.day2.agent.core.config.ApiSettings

interface ApiSettingsStore {
    fun load(): ApiSettings?

    fun save(settings: ApiSettings)
}

