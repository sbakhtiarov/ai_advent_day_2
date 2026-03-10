package com.aichallenge.day2.agent.data.mcp

import com.aichallenge.day2.agent.core.config.AppRuntimeInfo
import com.aichallenge.day2.agent.domain.model.McpRuntimeStatus
import com.aichallenge.day2.agent.domain.model.McpServerConfig
import com.aichallenge.day2.agent.domain.model.McpServerRuntimeState
import com.aichallenge.day2.agent.domain.model.McpToolCatalogState
import com.aichallenge.day2.agent.domain.model.McpToolCatalogStatus
import com.aichallenge.day2.agent.domain.model.McpToolDefinition
import com.aichallenge.day2.agent.domain.service.McpConnectedSession
import com.aichallenge.day2.agent.domain.service.McpRuntimeService
import io.ktor.client.HttpClient
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SdkMcpRuntimeService internal constructor(
    private val sessionManager: McpSessionManager,
) : McpRuntimeService {
    private val runtimeStates = linkedMapOf<McpServerKey, McpServerRuntimeState>()

    override suspend fun initializeEnabledServers(servers: List<McpServerConfig>): List<McpServerRuntimeState> {
        servers.forEach { server ->
            val key = McpServerKey(server)
            if (!server.enabled) {
                runtimeStates[key] = McpServerRuntimeState(
                    server = server,
                    status = McpRuntimeStatus.DISABLED,
                )
                return@forEach
            }

            val existingState = runtimeStates[key]
            if (existingState?.status == McpRuntimeStatus.READY || existingState?.status == McpRuntimeStatus.FAILED) {
                runtimeStates[key] = existingState.copy(server = server)
                return@forEach
            }

            runtimeStates[key] = runCatching {
                val session = sessionManager.connect(server)
                val toolCatalog = loadToolCatalog(session, server)
                McpServerRuntimeState(
                    server = server,
                    status = McpRuntimeStatus.READY,
                    toolCatalogStatus = toolCatalog.status,
                    tools = toolCatalog.tools,
                    toolCatalogFailureMessage = toolCatalog.failureMessage,
                )
            }.getOrElse { throwable ->
                McpServerRuntimeState(
                    server = server,
                    status = McpRuntimeStatus.FAILED,
                    failureMessage = throwable.message?.trim().takeUnless { it.isNullOrEmpty() } ?: "Unexpected error",
                )
            }
        }

        return servers.map(::runtimeStateFor)
    }

    override fun runtimeStateFor(server: McpServerConfig): McpServerRuntimeState {
        return runtimeStates[McpServerKey(server)] ?: McpServerRuntimeState(
            server = server,
            status = if (server.enabled) McpRuntimeStatus.NOT_ATTEMPTED else McpRuntimeStatus.DISABLED,
        )
    }

    override fun toolCatalogFor(server: McpServerConfig): McpToolCatalogState {
        val runtimeState = runtimeStateFor(server)
        return McpToolCatalogState(
            server = server,
            status = runtimeState.toolCatalogStatus,
            tools = runtimeState.tools,
            failureMessage = runtimeState.toolCatalogFailureMessage,
        )
    }

    override fun runtimeStates(): List<McpServerRuntimeState> = runtimeStates.values.toList()

    override fun connectedSession(serverName: String): McpConnectedSession? = sessionManager.sessionByName(serverName)

    override fun clearFailureState(server: McpServerConfig) {
        val key = McpServerKey(server)
        if (runtimeStates[key]?.status == McpRuntimeStatus.FAILED) {
            runtimeStates.remove(key)
        }
    }

    override suspend fun close() {
        sessionManager.closeAll()
    }

    private suspend fun loadToolCatalog(session: ManagedMcpSession, server: McpServerConfig): McpToolCatalogState {
        return runCatching {
            val tools = mutableListOf<McpToolDefinition>()
            var cursor: String? = null
            do {
                val response = session.listTools(cursor)
                tools += response.tools
                cursor = response.nextCursor
            } while (cursor != null)

            McpToolCatalogState(
                server = server,
                status = McpToolCatalogStatus.LOADED,
                tools = tools,
            )
        }.getOrElse { throwable ->
            McpToolCatalogState(
                server = server,
                status = McpToolCatalogStatus.FAILED,
                failureMessage = throwable.message?.trim().takeUnless { it.isNullOrEmpty() } ?: "Unexpected error",
            )
        }
    }

    companion object {
        fun create(httpClient: HttpClient): SdkMcpRuntimeService {
            return SdkMcpRuntimeService(
                sessionManager = McpSessionManager(
                    connector = SdkMcpClientConnector(
                        transportFactory = SdkMcpTransportFactory(httpClient),
                    ),
                ),
            )
        }
    }
}

internal class McpSessionManager(
    private val connector: McpClientConnector,
) {
    private val sessions = linkedMapOf<McpServerKey, ManagedMcpSession>()

    suspend fun connect(server: McpServerConfig): ManagedMcpSession {
        val key = McpServerKey(server)
        sessions[key]?.let { return it }

        val session = connector.connect(server)
        sessions[key] = session
        return session
    }

    fun sessionByName(serverName: String): McpConnectedSession? {
        return sessions.values.firstOrNull { session -> session.server.name == serverName }
    }

    suspend fun closeAll() {
        val activeSessions = sessions.values.toList()
        sessions.clear()
        activeSessions.forEach { session ->
            runCatching {
                session.close()
            }
        }
    }
}

internal interface McpClientConnector {
    suspend fun connect(server: McpServerConfig): ManagedMcpSession
}

internal interface ManagedMcpSession : McpConnectedSession {
    suspend fun listTools(cursor: String?): ManagedToolPage
    suspend fun close()
}

internal interface McpTransportFactory {
    fun create(url: String): StreamableHttpClientTransport
}

internal class SdkMcpClientConnector(
    private val transportFactory: McpTransportFactory,
) : McpClientConnector {
    override suspend fun connect(server: McpServerConfig): ManagedMcpSession {
        val client = Client(
            clientInfo = Implementation(
                name = AppRuntimeInfo.APP_NAME,
                version = AppRuntimeInfo.APP_VERSION,
            ),
        )
        val transport = transportFactory.create(server.url)
        client.connect(transport)
        return SdkManagedMcpSession(
            server = server,
            client = client,
        )
    }
}

internal class SdkMcpTransportFactory(
    private val httpClient: HttpClient,
) : McpTransportFactory {
    override fun create(url: String): StreamableHttpClientTransport {
        return StreamableHttpClientTransport(
            client = httpClient,
            url = url,
        )
    }
}

internal class SdkManagedMcpSession(
    override val server: McpServerConfig,
    private val client: Client,
) : ManagedMcpSession {
    private val json = Json { prettyPrint = true }

    override suspend fun listTools(cursor: String?): ManagedToolPage {
        val response = client.listTools(
            request = if (cursor == null) {
                ListToolsRequest()
            } else {
                ListToolsRequest(cursor = cursor, meta = null)
            },
        )
        return ManagedToolPage(
            tools = response.tools.map { tool ->
                McpToolDefinition(
                    name = tool.name,
                    title = tool.annotations?.title ?: tool.title,
                    description = tool.description,
                    inputSchemaJson = json.encodeToString(tool.inputSchema),
                    outputSchemaJson = tool.outputSchema?.let(json::encodeToString),
                )
            },
            nextCursor = response.nextCursor,
        )
    }

    override suspend fun close() {
        client.close()
    }
}

internal data class ManagedToolPage(
    val tools: List<McpToolDefinition>,
    val nextCursor: String?,
)

internal data class McpServerKey(
    val name: String,
    val url: String,
) {
    constructor(server: McpServerConfig) : this(
        name = server.name,
        url = server.url,
    )
}
