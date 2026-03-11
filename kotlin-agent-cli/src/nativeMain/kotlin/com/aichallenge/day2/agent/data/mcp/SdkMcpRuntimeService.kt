package com.aichallenge.day2.agent.data.mcp

import com.aichallenge.day2.agent.core.config.AppRuntimeInfo
import com.aichallenge.day2.agent.domain.model.McpRuntimeStatus
import com.aichallenge.day2.agent.domain.model.McpServerConfig
import com.aichallenge.day2.agent.domain.model.McpServerRuntimeState
import com.aichallenge.day2.agent.domain.model.McpToolCallResult
import com.aichallenge.day2.agent.domain.model.McpToolCatalogState
import com.aichallenge.day2.agent.domain.model.McpToolCatalogStatus
import com.aichallenge.day2.agent.domain.model.McpToolDefinition
import com.aichallenge.day2.agent.domain.model.McpTransportConfig
import com.aichallenge.day2.agent.domain.service.McpConnectedSession
import com.aichallenge.day2.agent.domain.service.McpRuntimeService
import io.ktor.client.HttpClient
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray

class SdkMcpRuntimeService internal constructor(
    private val sessionManager: McpSessionManager,
) : McpRuntimeService {
    private val json = Json { prettyPrint = true }
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

    override suspend fun callTool(server: McpServerConfig, toolName: String, arguments: JsonObject): McpToolCallResult {
        val session = sessionManager.session(server)
            ?: error("MCP server '${server.name}' is not connected")
        return normalizeToolCallResult(session.callTool(toolName = toolName, arguments = arguments))
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

    private fun normalizeToolCallResult(result: CallToolResult): McpToolCallResult {
        return McpToolCallResult(
            isError = result.isError == true,
            content = json.encodeToJsonElement(result.content).jsonArray,
            structuredContent = result.structuredContent,
            meta = result.meta,
        )
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
        internal fun create(
            httpClient: HttpClient,
            processLauncher: McpProcessLauncher = PosixMcpProcessLauncher(),
        ): SdkMcpRuntimeService {
            return SdkMcpRuntimeService(
                sessionManager = McpSessionManager(
                    connector = SdkMcpClientConnector(
                        transportFactory = SdkMcpTransportFactory(
                            httpClient = httpClient,
                            processLauncher = processLauncher,
                        ),
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

    fun session(server: McpServerConfig): ManagedMcpSession? = sessions[McpServerKey(server)]

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
    suspend fun callTool(toolName: String, arguments: JsonObject): CallToolResult
    suspend fun listTools(cursor: String?): ManagedToolPage
    suspend fun close()
}

internal interface McpTransportFactory {
    fun create(server: McpServerConfig): ManagedTransportResources
}

internal interface ManagedTransportResources {
    val transport: Transport
    suspend fun close()
}

internal interface McpClientFactory {
    fun create(): Client
}

internal class SdkMcpClientConnector(
    private val transportFactory: McpTransportFactory,
    private val clientFactory: McpClientFactory = DefaultMcpClientFactory(),
) : McpClientConnector {
    override suspend fun connect(server: McpServerConfig): ManagedMcpSession {
        val client = clientFactory.create()
        val transportResources = transportFactory.create(server)
        return runCatching {
            client.connect(transportResources.transport)
            SdkManagedMcpSession(
                server = server,
                client = client,
                transportResources = transportResources,
            )
        }.getOrElse { throwable ->
            runCatching { client.close() }
            runCatching { transportResources.close() }
            throw throwable
        }
    }
}

internal class DefaultMcpClientFactory : McpClientFactory {
    override fun create(): Client {
        return Client(
            clientInfo = Implementation(
                name = AppRuntimeInfo.APP_NAME,
                version = AppRuntimeInfo.APP_VERSION,
            ),
        )
    }
}

internal class SdkMcpTransportFactory(
    private val httpClient: HttpClient,
    private val processLauncher: McpProcessLauncher,
) : McpTransportFactory {
    override fun create(server: McpServerConfig): ManagedTransportResources = when (val transport = server.transport) {
        is McpTransportConfig.Http -> HttpTransportResources(
            transport = StreamableHttpClientTransport(
                client = httpClient,
                url = transport.url,
            ),
        )

        is McpTransportConfig.Stdio -> {
            val process = processLauncher.launch(
                command = transport.command,
                args = transport.args,
            )
            StdioTransportResources(
                transport = StdioClientTransport(
                    input = process.stdout,
                    output = process.stdin,
                    error = process.stderr,
                    classifyStderr = { StdioClientTransport.StderrSeverity.DEBUG },
                ),
                process = process,
            )
        }
    }
}

internal class HttpTransportResources(
    override val transport: Transport,
) : ManagedTransportResources {
    override suspend fun close() = Unit
}

internal class StdioTransportResources(
    override val transport: Transport,
    private val process: ManagedMcpProcess,
) : ManagedTransportResources {
    override suspend fun close() {
        process.close()
    }
}

internal class SdkManagedMcpSession(
    override val server: McpServerConfig,
    private val client: Client,
    private val transportResources: ManagedTransportResources,
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

    override suspend fun callTool(toolName: String, arguments: JsonObject): CallToolResult {
        return client.callTool(
            request = CallToolRequest(
                params = CallToolRequestParams(
                    name = toolName,
                    arguments = arguments,
                ),
            ),
        )
    }

    override suspend fun close() {
        var failure: Throwable? = null
        runCatching {
            client.close()
        }.onFailure { throwable ->
            failure = throwable
        }
        runCatching {
            transportResources.close()
        }.onFailure { throwable ->
            if (failure == null) {
                failure = throwable
            }
        }
        failure?.let { throwable ->
            throw throwable
        }
    }
}

internal data class ManagedToolPage(
    val tools: List<McpToolDefinition>,
    val nextCursor: String?,
)

internal data class McpServerKey(
    val name: String,
    val transport: McpTransportConfig,
) {
    constructor(server: McpServerConfig) : this(
        name = server.name,
        transport = server.transport,
    )
}
