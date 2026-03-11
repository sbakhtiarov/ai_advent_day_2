package com.aichallenge.day2.agent.domain.service

import com.aichallenge.day2.agent.domain.model.McpRuntimeStatus
import com.aichallenge.day2.agent.domain.model.McpServerConfig
import com.aichallenge.day2.agent.domain.model.McpServerRuntimeState
import com.aichallenge.day2.agent.domain.model.McpToolCallResult
import com.aichallenge.day2.agent.domain.model.McpToolCatalogState
import com.aichallenge.day2.agent.domain.model.McpToolCatalogStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface McpConnectedSession {
    val server: McpServerConfig
}

interface McpRuntimeService {
    suspend fun initializeEnabledServers(servers: List<McpServerConfig>): List<McpServerRuntimeState>
    suspend fun callTool(server: McpServerConfig, toolName: String, arguments: JsonObject): McpToolCallResult
    fun runtimeStateFor(server: McpServerConfig): McpServerRuntimeState
    fun toolCatalogFor(server: McpServerConfig): McpToolCatalogState
    fun runtimeStates(): List<McpServerRuntimeState>
    fun connectedSession(serverName: String): McpConnectedSession?
    fun clearFailureState(server: McpServerConfig)
    suspend fun close()
}

object NoOpMcpRuntimeService : McpRuntimeService {
    override suspend fun initializeEnabledServers(servers: List<McpServerConfig>): List<McpServerRuntimeState> {
        return servers.map(::defaultStateFor)
    }

    override suspend fun callTool(server: McpServerConfig, toolName: String, arguments: JsonObject): McpToolCallResult {
        return McpToolCallResult(
            isError = true,
            content = buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", "MCP runtime is not available")
                    },
                )
            },
        )
    }

    override fun runtimeStateFor(server: McpServerConfig): McpServerRuntimeState = defaultStateFor(server)

    override fun toolCatalogFor(server: McpServerConfig): McpToolCatalogState {
        return McpToolCatalogState(
            server = server,
            status = if (server.enabled) McpToolCatalogStatus.NOT_REQUESTED else McpToolCatalogStatus.NOT_REQUESTED,
        )
    }

    override fun runtimeStates(): List<McpServerRuntimeState> = emptyList()

    override fun connectedSession(serverName: String): McpConnectedSession? = null

    override fun clearFailureState(server: McpServerConfig) = Unit

    override suspend fun close() = Unit

    private fun defaultStateFor(server: McpServerConfig): McpServerRuntimeState {
        return McpServerRuntimeState(
            server = server,
            status = if (server.enabled) McpRuntimeStatus.NOT_ATTEMPTED else McpRuntimeStatus.DISABLED,
        )
    }
}
