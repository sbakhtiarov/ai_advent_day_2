package com.aichallenge.day2.agent.domain.repository

import com.aichallenge.day2.agent.domain.model.McpServerConfig

interface McpServerStore {
    fun load(): List<McpServerConfig>
    fun save(servers: List<McpServerConfig>)
}
