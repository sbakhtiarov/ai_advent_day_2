package com.aichallenge.day2.agent.data.mcp

import com.aichallenge.day2.agent.domain.model.McpRuntimeStatus
import com.aichallenge.day2.agent.domain.model.McpServerConfig
import com.aichallenge.day2.agent.domain.model.McpToolCatalogStatus
import com.aichallenge.day2.agent.domain.model.McpToolDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val server = McpServerConfig(name = "Linear", url = "http://localhost:3000", enabled = true)

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
        val server = McpServerConfig(name = "Linear", url = "http://localhost:3000", enabled = true)

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
        val server = McpServerConfig(name = "GitHub", url = "http://localhost:3001", enabled = false)

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
        val server = McpServerConfig(name = "Linear", url = "http://localhost:3000", enabled = true)

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
        val server = McpServerConfig(name = "Linear", url = "http://localhost:3000", enabled = true)

        val states = service.initializeEnabledServers(listOf(server))

        assertEquals(McpRuntimeStatus.READY, states.single().status)
        assertEquals(McpToolCatalogStatus.FAILED, states.single().toolCatalogStatus)
        assertEquals("tools unavailable", states.single().toolCatalogFailureMessage)
    }

    @Test
    fun closeClosesActiveSessions() = runSuspendTest {
        val connector = FakeMcpClientConnector()
        val service = SdkMcpRuntimeService(
            sessionManager = McpSessionManager(
                connector = connector,
            ),
        )
        val server = McpServerConfig(name = "Linear", url = "http://localhost:3000", enabled = true)

        service.initializeEnabledServers(listOf(server))
        val session = connector.createdSessions.single()

        service.close()

        assertTrue(session.closed)
        assertNull(service.connectedSession("Linear"))
    }
}

private class FakeMcpClientConnector(
    private val failuresByName: Map<String, String> = emptyMap(),
    private val toolCatalogsByName: Map<String, List<FakeToolPage>> = emptyMap(),
    private val toolFailuresByName: Map<String, String> = emptyMap(),
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
        ).also { session ->
            createdSessions += session
        }
    }
}

private class FakeManagedMcpSession(
    override val server: McpServerConfig,
    private val toolPages: List<FakeToolPage> = emptyList(),
    private val toolFailureMessage: String? = null,
) : ManagedMcpSession {
    var closed: Boolean = false
        private set

    private var nextToolPageIndex: Int = 0

    override suspend fun listTools(cursor: String?): ManagedToolPage {
        toolFailureMessage?.let(::error)
        val page = toolPages.getOrNull(nextToolPageIndex) ?: FakeToolPage(emptyList(), null)
        nextToolPageIndex += 1
        return ManagedToolPage(
            tools = page.tools,
            nextCursor = page.nextCursor,
        )
    }

    override suspend fun close() {
        closed = true
    }
}

private data class FakeToolPage(
    val tools: List<McpToolDefinition>,
    val nextCursor: String?,
)

private fun runSuspendTest(block: suspend () -> Unit) {
    kotlinx.coroutines.runBlocking {
        block()
    }
}
