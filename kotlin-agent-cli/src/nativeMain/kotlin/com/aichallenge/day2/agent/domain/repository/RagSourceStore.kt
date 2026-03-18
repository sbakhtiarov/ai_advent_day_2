package com.aichallenge.day2.agent.domain.repository

import com.aichallenge.day2.agent.domain.model.RagSourceConfig

interface RagSourceStore {
    fun load(): List<RagSourceConfig>
    fun save(sources: List<RagSourceConfig>)
}
