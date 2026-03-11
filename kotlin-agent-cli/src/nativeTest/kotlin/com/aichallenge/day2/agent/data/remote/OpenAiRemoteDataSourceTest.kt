package com.aichallenge.day2.agent.data.remote

import com.aichallenge.day2.agent.core.config.AppConfig
import com.aichallenge.day2.agent.core.config.ModelPricing
import com.aichallenge.day2.agent.core.config.ModelProperties
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.McpLlmCapabilities
import com.aichallenge.day2.agent.domain.model.McpPrivateToolBinding
import com.aichallenge.day2.agent.domain.model.McpPublicServerCapability
import com.aichallenge.day2.agent.domain.model.McpServerConfig
import com.aichallenge.day2.agent.domain.model.McpToolCallResult
import com.aichallenge.day2.agent.domain.model.McpToolCatalogState
import com.aichallenge.day2.agent.domain.model.McpToolCatalogStatus
import com.aichallenge.day2.agent.domain.model.McpToolDefinition
import com.aichallenge.day2.agent.domain.model.McpServerRuntimeState
import com.aichallenge.day2.agent.domain.model.McpRuntimeStatus
import com.aichallenge.day2.agent.domain.model.McpTransportConfig
import com.aichallenge.day2.agent.domain.model.PromptRequestData
import com.aichallenge.day2.agent.domain.model.TokenUsage
import com.aichallenge.day2.agent.domain.service.McpConnectedSession
import com.aichallenge.day2.agent.domain.service.McpRuntimeService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class OpenAiRemoteDataSourceTest {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Test
    fun fetchAssistantReplySendsMixedPublicAndPrivateTools() = runSuspendTest {
        val requestBodies = mutableListOf<String>()
        val dataSource = createDataSource(
            requestBodies = requestBodies,
            responses = listOf(
                """
                    {
                      "id": "resp_1",
                      "output_text": "Hello from OpenAI",
                      "usage": {
                        "total_tokens": 10,
                        "input_tokens": 6,
                        "output_tokens": 4
                      }
                    }
                """.trimIndent(),
            ),
        )

        val reply = dataSource.fetchAssistantReply(
            prompt = PromptRequestData(
                systemPrompt = "System",
                messages = listOf(ConversationMessage.user("Hi")),
                mcpCapabilities = McpLlmCapabilities(
                    publicServers = listOf(
                        McpPublicServerCapability(
                            serverLabel = "Weather",
                            serverUrl = "https://weather.chukai.io/mcp",
                        ),
                    ),
                    privateTools = listOf(privateToolBinding()),
                ),
            ),
            model = "gpt-4.1-mini",
        )

        assertEquals("Hello from OpenAI", reply.content)
        val request = json.parseToJsonElement(requestBodies.single()).jsonObject
        val tools = request["tools"]?.jsonArray ?: error("Missing tools")
        assertEquals(2, tools.size)
        val publicTool = tools[0].jsonObject
        assertEquals("mcp", publicTool["type"]?.jsonPrimitive?.content)
        assertEquals("Weather", publicTool["server_label"]?.jsonPrimitive?.content)
        assertEquals("https://weather.chukai.io/mcp", publicTool["server_url"]?.jsonPrimitive?.content)
        assertEquals("never", publicTool["require_approval"]?.jsonPrimitive?.content)
        val privateTool = tools[1].jsonObject
        assertEquals("function", privateTool["type"]?.jsonPrimitive?.content)
        assertEquals("linear__search_issues", privateTool["name"]?.jsonPrimitive?.content)
        assertEquals("object", privateTool["parameters"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(false, request["parallel_tool_calls"]?.jsonPrimitive?.content?.toBooleanStrict())
    }

    @Test
    fun fetchAssistantReplyContinuesWithFunctionCallOutputAndAggregatesUsage() = runSuspendTest {
        val requestBodies = mutableListOf<String>()
        val runtimeService = RecordingRemoteDataSourceMcpRuntimeService(
            toolResultsByKey = mapOf(
                ("Linear" to "search_issues") to Result.success(
                    McpToolCallResult(
                        isError = false,
                        content = buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", "issue-1")
                                },
                            )
                        },
                        structuredContent = buildJsonObject {
                            put("count", 1)
                        },
                        meta = buildJsonObject {
                            put("request_id", "req-1")
                        },
                    ),
                ),
            ),
        )
        val dataSource = createDataSource(
            requestBodies = requestBodies,
            responses = listOf(
                """
                    {
                      "id": "resp_1",
                      "output": [
                        {
                          "type": "function_call",
                          "call_id": "call_1",
                          "name": "linear__search_issues",
                          "arguments": "{\"query\":\"bug\"}"
                        }
                      ],
                      "usage": {
                        "total_tokens": 5,
                        "input_tokens": 3,
                        "output_tokens": 2
                      }
                    }
                """.trimIndent(),
                """
                    {
                      "id": "resp_2",
                      "output_text": "Found one issue",
                      "usage": {
                        "total_tokens": 7,
                        "input_tokens": 4,
                        "output_tokens": 3
                      }
                    }
                """.trimIndent(),
            ),
            mcpRuntimeService = runtimeService,
        )

        val reply = dataSource.fetchAssistantReply(
            prompt = promptWithPrivateTools(),
            model = "gpt-4.1-mini",
        )

        assertEquals("Found one issue", reply.content)
        assertEquals(
            TokenUsage(
                totalTokens = 12,
                inputTokens = 7,
                outputTokens = 5,
            ),
            reply.usage,
        )
        assertEquals(2, requestBodies.size)
        assertEquals(
            listOf(
                RecordedRemoteToolCall(
                    server = privateServer(),
                    toolName = "search_issues",
                    arguments = buildJsonObject {
                        put("query", "bug")
                    },
                ),
            ),
            runtimeService.callToolRequests,
        )

        val continuationRequest = json.parseToJsonElement(requestBodies[1]).jsonObject
        assertEquals("resp_1", continuationRequest["previous_response_id"]?.jsonPrimitive?.content)
        val continuationInput = continuationRequest["input"]?.jsonArray?.single()?.jsonObject ?: error("Missing continuation input")
        assertEquals("function_call_output", continuationInput["type"]?.jsonPrimitive?.content)
        assertEquals("call_1", continuationInput["call_id"]?.jsonPrimitive?.content)
        val outputEnvelope = json.parseToJsonElement(
            continuationInput["output"]?.jsonPrimitive?.content ?: error("Missing tool output"),
        ).jsonObject
        assertEquals(true, outputEnvelope["ok"]?.jsonPrimitive?.content?.toBooleanStrict())
        assertEquals("Linear", outputEnvelope["server"]?.jsonPrimitive?.content)
        assertEquals("search_issues", outputEnvelope["tool"]?.jsonPrimitive?.content)
        assertEquals(false, outputEnvelope["is_error"]?.jsonPrimitive?.content?.toBooleanStrict())
        assertEquals(1, outputEnvelope["structured_content"]?.jsonObject?.get("count")?.jsonPrimitive?.content?.toInt())
        assertTrue(outputEnvelope.containsKey("_meta"))
    }

    @Test
    fun fetchAssistantReplySupportsMultipleSequentialPrivateToolCalls() = runSuspendTest {
        val requestBodies = mutableListOf<String>()
        val runtimeService = RecordingRemoteDataSourceMcpRuntimeService(
            toolResultsByKey = mapOf(
                ("Linear" to "search_issues") to Result.success(successfulToolCallResult("issue-1")),
                ("Linear" to "create_issue") to Result.success(successfulToolCallResult("issue-2")),
            ),
        )
        val dataSource = createDataSource(
            requestBodies = requestBodies,
            responses = listOf(
                """
                    {
                      "id": "resp_1",
                      "output": [
                        {
                          "type": "function_call",
                          "call_id": "call_1",
                          "name": "linear__search_issues",
                          "arguments": "{\"query\":\"bug\"}"
                        }
                      ]
                    }
                """.trimIndent(),
                """
                    {
                      "id": "resp_2",
                      "output": [
                        {
                          "type": "function_call",
                          "call_id": "call_2",
                          "name": "linear__create_issue",
                          "arguments": "{\"title\":\"Bug\"}"
                        }
                      ]
                    }
                """.trimIndent(),
                """
                    {
                      "id": "resp_3",
                      "output_text": "Created follow-up issue"
                    }
                """.trimIndent(),
            ),
            mcpRuntimeService = runtimeService,
        )

        val reply = dataSource.fetchAssistantReply(
            prompt = promptWithPrivateTools(
                privateTools = listOf(
                    privateToolBinding(
                        modelToolName = "linear__search_issues",
                        sourceToolName = "search_issues",
                    ),
                    privateToolBinding(
                        modelToolName = "linear__create_issue",
                        sourceToolName = "create_issue",
                    ),
                ),
            ),
            model = "gpt-4.1-mini",
        )

        assertEquals("Created follow-up issue", reply.content)
        assertEquals(3, requestBodies.size)
        assertEquals(2, runtimeService.callToolRequests.size)
    }

    @Test
    fun fetchAssistantReplyReturnsToolFailureOutputForInvalidArguments() = runSuspendTest {
        val requestBodies = mutableListOf<String>()
        val runtimeService = RecordingRemoteDataSourceMcpRuntimeService()
        val dataSource = createDataSource(
            requestBodies = requestBodies,
            responses = listOf(
                """
                    {
                      "id": "resp_1",
                      "output": [
                        {
                          "type": "function_call",
                          "call_id": "call_1",
                          "name": "linear__search_issues",
                          "arguments": "[1,2,3]"
                        }
                      ]
                    }
                """.trimIndent(),
                """
                    {
                      "id": "resp_2",
                      "output_text": "Recovered"
                    }
                """.trimIndent(),
            ),
            mcpRuntimeService = runtimeService,
        )

        val reply = dataSource.fetchAssistantReply(
            prompt = promptWithPrivateTools(),
            model = "gpt-4.1-mini",
        )

        assertEquals("Recovered", reply.content)
        assertTrue(runtimeService.callToolRequests.isEmpty())
        val continuationRequest = json.parseToJsonElement(requestBodies[1]).jsonObject
        val outputEnvelope = json.parseToJsonElement(
            continuationRequest["input"]?.jsonArray?.single()?.jsonObject?.get("output")?.jsonPrimitive?.content
                ?: error("Missing tool output"),
        ).jsonObject
        assertEquals(false, outputEnvelope["ok"]?.jsonPrimitive?.content?.toBooleanStrict())
        assertContains(outputEnvelope["error"]?.jsonPrimitive?.content.orEmpty(), "JSON object")
    }

    @Test
    fun fetchAssistantReplyTreatsBlankPrivateToolArgumentsAsEmptyObject() = runSuspendTest {
        val requestBodies = mutableListOf<String>()
        val runtimeService = RecordingRemoteDataSourceMcpRuntimeService(
            toolResultsByKey = mapOf(
                ("Linear" to "search_issues") to Result.success(successfulToolCallResult("issue-1")),
            ),
        )
        val dataSource = createDataSource(
            requestBodies = requestBodies,
            responses = listOf(
                """
                    {
                      "id": "resp_1",
                      "output": [
                        {
                          "type": "function_call",
                          "call_id": "call_1",
                          "name": "linear__search_issues",
                          "arguments": ""
                        }
                      ]
                    }
                """.trimIndent(),
                """
                    {
                      "id": "resp_2",
                      "output_text": "Recovered"
                    }
                """.trimIndent(),
            ),
            mcpRuntimeService = runtimeService,
        )

        val reply = dataSource.fetchAssistantReply(
            prompt = promptWithPrivateTools(),
            model = "gpt-4.1-mini",
        )

        assertEquals("Recovered", reply.content)
        assertEquals(
            listOf(
                RecordedRemoteToolCall(
                    server = privateServer(),
                    toolName = "search_issues",
                    arguments = buildJsonObject {},
                ),
            ),
            runtimeService.callToolRequests,
        )
    }

    @Test
    fun fetchAssistantReplyReturnsToolFailureOutputWhenPrivateToolThrows() = runSuspendTest {
        val requestBodies = mutableListOf<String>()
        val runtimeService = RecordingRemoteDataSourceMcpRuntimeService(
            toolResultsByKey = mapOf(
                ("Linear" to "search_issues") to Result.failure(IllegalStateException("Broken pipe")),
            ),
        )
        val dataSource = createDataSource(
            requestBodies = requestBodies,
            responses = listOf(
                """
                    {
                      "id": "resp_1",
                      "output": [
                        {
                          "type": "function_call",
                          "call_id": "call_1",
                          "name": "linear__search_issues",
                          "arguments": "{\"query\":\"bug\"}"
                        }
                      ]
                    }
                """.trimIndent(),
                """
                    {
                      "id": "resp_2",
                      "output_text": "Tool error explained"
                    }
                """.trimIndent(),
            ),
            mcpRuntimeService = runtimeService,
        )

        val reply = dataSource.fetchAssistantReply(
            prompt = promptWithPrivateTools(),
            model = "gpt-4.1-mini",
        )

        assertEquals("Tool error explained", reply.content)
        val continuationRequest = json.parseToJsonElement(requestBodies[1]).jsonObject
        val outputEnvelope = json.parseToJsonElement(
            continuationRequest["input"]?.jsonArray?.single()?.jsonObject?.get("output")?.jsonPrimitive?.content
                ?: error("Missing tool output"),
        ).jsonObject
        assertEquals(false, outputEnvelope["ok"]?.jsonPrimitive?.content?.toBooleanStrict())
        assertEquals("Broken pipe", outputEnvelope["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun fetchAssistantReplyFailsWhenPrivateToolLoopExceedsLimit() = runSuspendTest {
        val requestBodies = mutableListOf<String>()
        val repeatedFunctionCalls = (1..17).joinToString(separator = ",") { index ->
            """
                {
                  "type": "function_call",
                  "call_id": "call_$index",
                  "name": "linear__search_issues",
                  "arguments": "{\"query\":\"bug-$index\"}"
                }
            """.trimIndent()
        }
        val dataSource = createDataSource(
            requestBodies = requestBodies,
            responses = listOf(
                """
                    {
                      "id": "resp_1",
                      "output": [$repeatedFunctionCalls]
                    }
                """.trimIndent(),
            ),
            mcpRuntimeService = RecordingRemoteDataSourceMcpRuntimeService(
                toolResultsByKey = mapOf(
                    ("Linear" to "search_issues") to Result.success(successfulToolCallResult("issue")),
                ),
            ),
        )

        val error = assertFailsWith<IllegalStateException> {
            dataSource.fetchAssistantReply(
                prompt = promptWithPrivateTools(),
                model = "gpt-4.1-mini",
            )
        }

        assertContains(error.message.orEmpty(), "more than 16 private MCP tool calls")
        assertEquals(1, requestBodies.size)
    }

    private fun createDataSource(
        requestBodies: MutableList<String>,
        responses: List<String>,
        mcpRuntimeService: McpRuntimeService = RecordingRemoteDataSourceMcpRuntimeService(),
    ): OpenAiRemoteDataSource {
        val queuedResponses = ArrayDeque(responses)
        val httpClient = HttpClient(
            MockEngine { request ->
                requestBodies += request.body.toByteArray().decodeToString()
                val responseBody = queuedResponses.removeFirstOrNull()
                    ?: error("No prepared HTTP response for request #${requestBodies.size}")
                respond(
                    content = responseBody,
                    status = io.ktor.http.HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentType,
                        ContentType.Application.Json.toString(),
                    ),
                )
            },
        ) {
            install(ContentNegotiation) {
                json(json)
            }
        }

        return OpenAiRemoteDataSource(
            httpClient = httpClient,
            config = AppConfig(
                apiKey = "test-key",
                model = "gpt-4.1-mini",
                models = listOf(
                    ModelProperties(
                        id = "gpt-4.1-mini",
                        pricing = ModelPricing(
                            inputUsdPer1M = 0.40,
                            outputUsdPer1M = 1.60,
                        ),
                        contextWindowTokens = 1_000_000,
                    ),
                ),
                baseUrl = "https://api.openai.com/v1",
                systemPrompt = "System",
                apiTrafficLogFilePath = null,
            ),
            json = json,
            mcpRuntimeService = mcpRuntimeService,
        )
    }

    private fun promptWithPrivateTools(
        privateTools: List<McpPrivateToolBinding> = listOf(privateToolBinding()),
    ): PromptRequestData {
        return PromptRequestData(
            systemPrompt = "System",
            messages = listOf(ConversationMessage.user("Find issues")),
            mcpCapabilities = McpLlmCapabilities(
                privateTools = privateTools,
            ),
        )
    }

    private fun privateToolBinding(
        modelToolName: String = "linear__search_issues",
        sourceToolName: String = "search_issues",
    ): McpPrivateToolBinding {
        return McpPrivateToolBinding(
            modelToolName = modelToolName,
            server = privateServer(),
            sourceToolName = sourceToolName,
            description = "Search Linear issues",
            parametersSchema = buildJsonObject {
                put("type", "object")
            },
        )
    }

    private fun privateServer(): McpServerConfig {
        return McpServerConfig(
            name = "Linear",
            enabled = true,
            transport = McpTransportConfig.Http(url = "http://localhost:3000"),
        )
    }

    private fun successfulToolCallResult(text: String): McpToolCallResult {
        return McpToolCallResult(
            isError = false,
            content = buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    },
                )
            },
        )
    }
}

private class RecordingRemoteDataSourceMcpRuntimeService(
    private val toolResultsByKey: Map<Pair<String, String>, Result<McpToolCallResult>> = emptyMap(),
) : McpRuntimeService {
    val callToolRequests = mutableListOf<RecordedRemoteToolCall>()

    override suspend fun initializeEnabledServers(servers: List<McpServerConfig>): List<McpServerRuntimeState> {
        return servers.map { server ->
            McpServerRuntimeState(
                server = server,
                status = if (server.enabled) McpRuntimeStatus.READY else McpRuntimeStatus.DISABLED,
                toolCatalogStatus = if (server.enabled) McpToolCatalogStatus.LOADED else McpToolCatalogStatus.NOT_REQUESTED,
                tools = emptyList(),
            )
        }
    }

    override suspend fun callTool(server: McpServerConfig, toolName: String, arguments: JsonObject): McpToolCallResult {
        callToolRequests += RecordedRemoteToolCall(
            server = server,
            toolName = toolName,
            arguments = arguments,
        )
        return toolResultsByKey[server.name to toolName]
            ?.getOrThrow()
            ?: error("No prepared MCP tool result for ${server.name}/$toolName")
    }

    override fun runtimeStateFor(server: McpServerConfig): McpServerRuntimeState {
        return McpServerRuntimeState(
            server = server,
            status = if (server.enabled) McpRuntimeStatus.READY else McpRuntimeStatus.DISABLED,
        )
    }

    override fun toolCatalogFor(server: McpServerConfig): McpToolCatalogState {
        return McpToolCatalogState(
            server = server,
            status = McpToolCatalogStatus.NOT_REQUESTED,
        )
    }

    override fun runtimeStates(): List<McpServerRuntimeState> = emptyList()

    override fun connectedSession(serverName: String): McpConnectedSession? = null

    override fun clearFailureState(server: McpServerConfig) = Unit

    override suspend fun close() = Unit
}

private data class RecordedRemoteToolCall(
    val server: McpServerConfig,
    val toolName: String,
    val arguments: JsonObject,
)

private fun runSuspendTest(block: suspend () -> Unit) {
    runBlocking {
        block()
    }
}
