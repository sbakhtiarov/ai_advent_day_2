package com.aichallenge.day2.agent.data.mcp

import com.aichallenge.day2.agent.domain.model.McpRuntimeStatus
import com.aichallenge.day2.agent.domain.model.McpServerConfig
import com.aichallenge.day2.agent.domain.model.McpToolCatalogStatus
import com.aichallenge.day2.agent.domain.model.McpToolDefinition
import com.aichallenge.day2.agent.domain.model.McpTransportConfig
import com.aichallenge.day2.agent.domain.service.NoOpMcpRuntimeService
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.io.Buffer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SdkMcpRuntimeServiceTest {
    @Test
    fun initializeEnabledServersMarksSuccessfulConnectionsReady() = runSuspendTest {
        val service = SdkMcpRuntimeService(
            sessionManager = McpSessionManager(
                connector = FakeMcpClientConnector(),
            ),
        )
        val server = httpServer(name = "Linear", url = "http://localhost:3000", enabled = true)

        val states = service.initializeEnabledServers(listOf(server))

        assertEquals(McpRuntimeStatus.READY, states.single().status)
        assertEquals(McpToolCatalogStatus.LOADED, states.single().toolCatalogStatus)
        assertNotNull(service.connectedSession("Linear"))
    }

    @Test
    fun initializeEnabledServersMarksFailuresAndPreservesReason() = runSuspendTest {
        val service = SdkMcpRuntimeService(
            sessionManager = McpSessionManager(
                connector = FakeMcpClientConnector(
                    failuresByName = mapOf("Linear" to "Connection refused"),
                ),
            ),
        )
        val server = httpServer(name = "Linear", url = "http://localhost:3000", enabled = true)

        val states = service.initializeEnabledServers(listOf(server))

        assertEquals(McpRuntimeStatus.FAILED, states.single().status)
        assertEquals("Connection refused", states.single().failureMessage)
        assertNull(service.connectedSession("Linear"))
    }

    @Test
    fun initializeEnabledServersSkipsDisabledServers() = runSuspendTest {
        val connector = FakeMcpClientConnector()
        val service = SdkMcpRuntimeService(
            sessionManager = McpSessionManager(
                connector = connector,
            ),
        )
        val server = httpServer(name = "GitHub", url = "http://localhost:3001", enabled = false)

        val states = service.initializeEnabledServers(listOf(server))

        assertEquals(McpRuntimeStatus.DISABLED, states.single().status)
        assertTrue(connector.connectCalls.isEmpty())
    }

    @Test
    fun initializeEnabledServersLoadsPaginatedTools() = runSuspendTest {
        val connector = FakeMcpClientConnector(
            toolCatalogsByName = mapOf(
                "Linear" to listOf(
                    FakeToolPage(
                        tools = listOf(
                            McpToolDefinition(
                                name = "search_issues",
                                title = "Search issues",
                                description = "Search Linear issues",
                                inputSchemaJson = """{"type":"object"}""",
                            ),
                        ),
                        nextCursor = "page-2",
                    ),
                    FakeToolPage(
                        tools = listOf(
                            McpToolDefinition(
                                name = "create_issue",
                                description = "Create Linear issue",
                                inputSchemaJson = """{"type":"object"}""",
                            ),
                        ),
                        nextCursor = null,
                    ),
                ),
            ),
        )
        val service = SdkMcpRuntimeService(
            sessionManager = McpSessionManager(connector),
        )
        val server = httpServer(name = "Linear", url = "http://localhost:3000", enabled = true)

        val states = service.initializeEnabledServers(listOf(server))

        assertEquals(McpRuntimeStatus.READY, states.single().status)
        assertEquals(McpToolCatalogStatus.LOADED, states.single().toolCatalogStatus)
        assertEquals(listOf("search_issues", "create_issue"), states.single().tools.map { it.name })
    }

    @Test
    fun initializeEnabledServersKeepsReadyWhenToolLoadingFails() = runSuspendTest {
        val connector = FakeMcpClientConnector(
            toolFailuresByName = mapOf("Linear" to "tools unavailable"),
        )
        val service = SdkMcpRuntimeService(
            sessionManager = McpSessionManager(connector),
        )
        val server = httpServer(name = "Linear", url = "http://localhost:3000", enabled = true)

        val states = service.initializeEnabledServers(listOf(server))

        assertEquals(McpRuntimeStatus.READY, states.single().status)
        assertEquals(McpToolCatalogStatus.FAILED, states.single().toolCatalogStatus)
        assertEquals("tools unavailable", states.single().toolCatalogFailureMessage)
    }

    @Test
    fun initializeEnabledServersKeysSessionsByTransport() = runSuspendTest {
        val connector = FakeMcpClientConnector()
        val service = SdkMcpRuntimeService(
            sessionManager = McpSessionManager(connector),
        )
        val httpServer = httpServer(name = "Shared", url = "http://localhost:3000", enabled = true)
        val stdioServer = stdioServer(name = "Shared", command = "node", args = listOf("/tmp/server.js"), enabled = true)

        service.initializeEnabledServers(listOf(httpServer, stdioServer))

        assertEquals(2, connector.connectCalls.size)
        assertEquals(listOf(httpServer, stdioServer), connector.connectCalls)
    }

    @Test
    fun callToolUsesConnectedSessionAndNormalizesResult() = runSuspendTest {
        val connector = FakeMcpClientConnector(
            toolCallResultsByServerName = mapOf(
                "Linear" to mapOf(
                    "search_issues" to CallToolResult(
                        content = listOf(TextContent("rain in Berlin")),
                        isError = false,
                        structuredContent = buildJsonObject {
                            put("forecast", "rain")
                        },
                        meta = buildJsonObject {
                            put("request_id", "req-123")
                        },
                    ),
                ),
            ),
        )
        val service = SdkMcpRuntimeService(
            sessionManager = McpSessionManager(connector),
        )
        val server = httpServer(name = "Linear", url = "http://localhost:3000", enabled = true)
        val arguments = buildJsonObject {
            put("city", "Berlin")
        }

        service.initializeEnabledServers(listOf(server))
        val result = service.callTool(server, "search_issues", arguments)

        assertEquals(
            listOf(RecordedToolCall(toolName = "search_issues", arguments = arguments)),
            connector.createdSessions.single().toolCallRequests,
        )
        assertEquals(false, result.isError)
        assertEquals("text", result.content.single().jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("rain in Berlin", result.content.single().jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals("rain", result.structuredContent?.get("forecast")?.jsonPrimitive?.content)
        assertEquals("req-123", result.meta?.get("request_id")?.jsonPrimitive?.content)
    }

    @Test
    fun callToolPropagatesRuntimeFailureReason() = runSuspendTest {
        val connector = FakeMcpClientConnector(
            toolCallFailuresByServerName = mapOf(
                "Linear" to mapOf(
                    "search_issues" to "Broken pipe",
                ),
            ),
        )
        val service = SdkMcpRuntimeService(
            sessionManager = McpSessionManager(connector),
        )
        val server = httpServer(name = "Linear", url = "http://localhost:3000", enabled = true)

        service.initializeEnabledServers(listOf(server))
        val failure = runCatching {
            service.callTool(
                server = server,
                toolName = "search_issues",
                arguments = buildJsonObject {},
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals("Broken pipe", failure.message)
    }

    @Test
    fun callToolFailsWhenServerIsNotConnected() = runSuspendTest {
        val service = SdkMcpRuntimeService(
            sessionManager = McpSessionManager(FakeMcpClientConnector()),
        )
        val server = httpServer(name = "Linear", url = "http://localhost:3000", enabled = true)

        val failure = runCatching {
            service.callTool(
                server = server,
                toolName = "search_issues",
                arguments = buildJsonObject {},
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals("MCP server 'Linear' is not connected", failure.message)
    }

    @Test
    fun noOpRuntimeServiceReturnsNotAvailableToolCallResult() = runSuspendTest {
        val server = httpServer(name = "Linear", url = "http://localhost:3000", enabled = true)

        val result = NoOpMcpRuntimeService.callTool(
            server = server,
            toolName = "search_issues",
            arguments = buildJsonObject {},
        )

        assertTrue(result.isError)
        assertEquals("text", result.content.single().jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("MCP runtime is not available", result.content.single().jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun closeClosesActiveSessions() = runSuspendTest {
        val connector = FakeMcpClientConnector()
        val service = SdkMcpRuntimeService(
            sessionManager = McpSessionManager(
                connector = connector,
            ),
        )
        val server = httpServer(name = "Linear", url = "http://localhost:3000", enabled = true)

        service.initializeEnabledServers(listOf(server))
        val session = connector.createdSessions.single()

        service.close()

        assertTrue(session.closed)
        assertNull(service.connectedSession("Linear"))
    }

    @Test
    fun transportFactoryUsesHttpTransportForHttpServers() = runSuspendTest {
        val processLauncher = FakeMcpProcessLauncher()
        val httpClient = HttpClient(Curl)
        try {
            val resources = SdkMcpTransportFactory(
                httpClient = httpClient,
                processLauncher = processLauncher,
            ).create(httpServer(name = "Linear", url = "http://localhost:3000", enabled = true))

            assertIs<StreamableHttpClientTransport>(resources.transport)
            assertTrue(processLauncher.launchCalls.isEmpty())

            resources.close()
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun transportFactoryUsesStdioTransportAndClosesProcess() = runSuspendTest {
        val processLauncher = FakeMcpProcessLauncher()
        val httpClient = HttpClient(Curl)
        try {
            val resources = SdkMcpTransportFactory(
                httpClient = httpClient,
                processLauncher = processLauncher,
            ).create(stdioServer(name = "Local", command = "node", args = listOf("/tmp/server.js"), enabled = true))

            assertIs<StdioClientTransport>(resources.transport)
            assertEquals(
                listOf("node" to listOf("/tmp/server.js")),
                processLauncher.launchCalls,
            )

            resources.close()

            assertTrue(processLauncher.processes.single().closed)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun transportFactoryPropagatesLauncherFailureReason() {
        val processLauncher = FakeMcpProcessLauncher(
            failure = IllegalStateException("No such file or directory"),
        )
        val httpClient = HttpClient(Curl)
        try {
            val failure = runCatching {
                SdkMcpTransportFactory(
                    httpClient = httpClient,
                    processLauncher = processLauncher,
                ).create(stdioServer(name = "Local", command = "missing", args = emptyList(), enabled = true))
            }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals("No such file or directory", failure.message)
        } finally {
            httpClient.close()
        }
    }
}

private class FakeMcpClientConnector(
    private val failuresByName: Map<String, String> = emptyMap(),
    private val toolCatalogsByName: Map<String, List<FakeToolPage>> = emptyMap(),
    private val toolFailuresByName: Map<String, String> = emptyMap(),
    private val toolCallResultsByServerName: Map<String, Map<String, CallToolResult>> = emptyMap(),
    private val toolCallFailuresByServerName: Map<String, Map<String, String>> = emptyMap(),
) : McpClientConnector {
    val connectCalls = mutableListOf<McpServerConfig>()
    val createdSessions = mutableListOf<FakeManagedMcpSession>()

    override suspend fun connect(server: McpServerConfig): ManagedMcpSession {
        connectCalls += server.copy()
        failuresByName[server.name]?.let { reason ->
            error(reason)
        }

        return FakeManagedMcpSession(
            server = server,
            toolPages = toolCatalogsByName[server.name].orEmpty(),
            toolFailureMessage = toolFailuresByName[server.name],
            toolCallResultsByName = toolCallResultsByServerName[server.name].orEmpty(),
            toolCallFailuresByName = toolCallFailuresByServerName[server.name].orEmpty(),
        ).also { session ->
            createdSessions += session
        }
    }
}

private class FakeManagedMcpSession(
    override val server: McpServerConfig,
    private val toolPages: List<FakeToolPage> = emptyList(),
    private val toolFailureMessage: String? = null,
    private val toolCallResultsByName: Map<String, CallToolResult> = emptyMap(),
    private val toolCallFailuresByName: Map<String, String> = emptyMap(),
) : ManagedMcpSession {
    var closed: Boolean = false
        private set

    private var nextToolPageIndex: Int = 0
    val toolCallRequests = mutableListOf<RecordedToolCall>()

    override suspend fun listTools(cursor: String?): ManagedToolPage {
        toolFailureMessage?.let(::error)
        val page = toolPages.getOrNull(nextToolPageIndex) ?: FakeToolPage(emptyList(), null)
        nextToolPageIndex += 1
        return ManagedToolPage(
            tools = page.tools,
            nextCursor = page.nextCursor,
        )
    }

    override suspend fun callTool(toolName: String, arguments: JsonObject): CallToolResult {
        toolCallRequests += RecordedToolCall(toolName = toolName, arguments = arguments)
        toolCallFailuresByName[toolName]?.let(::error)
        return toolCallResultsByName[toolName]
            ?: CallToolResult(content = listOf(TextContent("ok")))
    }

    override suspend fun close() {
        closed = true
    }
}

private class FakeMcpProcessLauncher(
    private val failure: Throwable? = null,
) : McpProcessLauncher {
    val launchCalls = mutableListOf<Pair<String, List<String>>>()
    val processes = mutableListOf<FakeManagedMcpProcess>()

    override fun launch(command: String, args: List<String>): ManagedMcpProcess {
        failure?.let { throwable -> throw throwable }
        launchCalls += command to args
        return FakeManagedMcpProcess().also { process ->
            processes += process
        }
    }
}

private class FakeManagedMcpProcess : ManagedMcpProcess {
    private val buffer = Buffer()
    var closed: Boolean = false
        private set

    override val stdin = buffer
    override val stdout = buffer
    override val stderr = Buffer()

    override fun close() {
        closed = true
    }
}

private data class FakeToolPage(
    val tools: List<McpToolDefinition>,
    val nextCursor: String?,
)

private data class RecordedToolCall(
    val toolName: String,
    val arguments: JsonObject,
)

private fun httpServer(name: String, url: String, enabled: Boolean): McpServerConfig {
    return McpServerConfig(
        name = name,
        enabled = enabled,
        transport = McpTransportConfig.Http(url = url),
    )
}

private fun stdioServer(name: String, command: String, args: List<String>, enabled: Boolean): McpServerConfig {
    return McpServerConfig(
        name = name,
        enabled = enabled,
        transport = McpTransportConfig.Stdio(
            command = command,
            args = args,
        ),
    )
}

private fun runSuspendTest(block: suspend () -> Unit) {
    kotlinx.coroutines.runBlocking {
        block()
    }
}
