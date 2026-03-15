package com.aichallenge.day2.agent.data.remote

import com.aichallenge.day2.agent.core.config.AppConfig
import com.aichallenge.day2.agent.core.config.ModelPricing
import com.aichallenge.day2.agent.core.config.ModelProperties
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.LlmToolCapabilities
import com.aichallenge.day2.agent.domain.model.McpServerConfig
import com.aichallenge.day2.agent.domain.model.McpTransportConfig
import com.aichallenge.day2.agent.domain.model.PromptRequestData
import com.aichallenge.day2.agent.domain.model.PrivateToolBinding
import com.aichallenge.day2.agent.domain.model.PrivateToolResult
import com.aichallenge.day2.agent.domain.model.PrivateToolTarget
import com.aichallenge.day2.agent.domain.model.PublicMcpServerCapability
import com.aichallenge.day2.agent.domain.model.TokenUsage
import com.aichallenge.day2.agent.domain.model.ToolCallTraceEvent
import com.aichallenge.day2.agent.domain.model.ToolCallTraceObserver
import com.aichallenge.day2.agent.domain.service.PrivateToolExecutionService
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
                toolCapabilities = LlmToolCapabilities(
                    publicMcpServers = listOf(
                        PublicMcpServerCapability(
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
        val executionService = RecordingPrivateToolExecutionService(
            toolResultsByName = mapOf(
                "linear__search_issues" to Result.success(
                    PrivateToolResult(
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
            privateToolExecutionService = executionService,
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
        assertEquals(1, executionService.executeRequests.size)
        assertEquals(privateToolBinding(), executionService.executeRequests.single().binding)
        assertEquals(
            buildJsonObject {
                put("query", "bug")
            },
            executionService.executeRequests.single().arguments,
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
        assertEquals("linear__search_issues", outputEnvelope["tool"]?.jsonPrimitive?.content)
        assertEquals(false, outputEnvelope["is_error"]?.jsonPrimitive?.content?.toBooleanStrict())
        assertEquals(1, outputEnvelope["structured_content"]?.jsonObject?.get("count")?.jsonPrimitive?.content?.toInt())
        assertTrue(outputEnvelope.containsKey("_meta"))
    }

    @Test
    fun fetchAssistantReplyEmitsStartEventForBuiltInTool() = runSuspendTest {
        val events = mutableListOf<ToolCallTraceEvent>()
        val dataSource = createDataSource(
            requestBodies = mutableListOf(),
            responses = listOf(
                """
                    {
                      "id": "resp_1",
                      "output": [
                        {
                          "type": "function_call",
                          "call_id": "call_1",
                          "name": "notify_user",
                          "arguments": "{\"message\":\"Build finished\"}"
                        }
                      ]
                    }
                """.trimIndent(),
                """
                    {
                      "id": "resp_2",
                      "output_text": "Notified the user"
                    }
                """.trimIndent(),
            ),
            privateToolExecutionService = RecordingPrivateToolExecutionService(
                toolResultsByName = mapOf(
                    "notify_user" to Result.success(successfulPrivateToolResult("notified")),
                ),
            ),
        )

        dataSource.fetchAssistantReply(
            prompt = promptWithPrivateTools(privateTools = listOf(notifyUserToolBinding())),
            model = "gpt-4.1-mini",
            toolCallTraceObserver = RecordingToolCallTraceObserver(events),
        )

        assertEquals(
            listOf<ToolCallTraceEvent>(ToolCallTraceEvent.Started(toolLabel = "built-in 'notify_user'")),
            events,
        )
    }

    @Test
    fun fetchAssistantReplySupportsMultipleSequentialPrivateToolCalls() = runSuspendTest {
        val requestBodies = mutableListOf<String>()
        val executionService = RecordingPrivateToolExecutionService(
            toolResultsByName = mapOf(
                "linear__search_issues" to Result.success(successfulPrivateToolResult("issue-1")),
                "linear__create_issue" to Result.success(successfulPrivateToolResult("issue-2")),
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
            privateToolExecutionService = executionService,
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
        assertEquals(2, executionService.executeRequests.size)
    }

    @Test
    fun fetchAssistantReplyEmitsStartEventsForSequentialMcpToolsInOrder() = runSuspendTest {
        val events = mutableListOf<ToolCallTraceEvent>()
        val dataSource = createDataSource(
            requestBodies = mutableListOf(),
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
            privateToolExecutionService = RecordingPrivateToolExecutionService(
                toolResultsByName = mapOf(
                    "linear__search_issues" to Result.success(successfulPrivateToolResult("issue-1")),
                    "linear__create_issue" to Result.success(successfulPrivateToolResult("issue-2")),
                ),
            ),
        )

        dataSource.fetchAssistantReply(
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
            toolCallTraceObserver = RecordingToolCallTraceObserver(events),
        )

        assertEquals(
            listOf<ToolCallTraceEvent>(
                ToolCallTraceEvent.Started(toolLabel = "MCP 'Linear/search_issues'"),
                ToolCallTraceEvent.Started(toolLabel = "MCP 'Linear/create_issue'"),
            ),
            events,
        )
    }

    @Test
    fun fetchAssistantReplyReturnsToolFailureOutputForInvalidArguments() = runSuspendTest {
        val requestBodies = mutableListOf<String>()
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
        )

        val reply = dataSource.fetchAssistantReply(
            prompt = promptWithPrivateTools(),
            model = "gpt-4.1-mini",
        )

        assertEquals("Recovered", reply.content)
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
        val executionService = RecordingPrivateToolExecutionService(
            toolResultsByName = mapOf(
                "linear__search_issues" to Result.success(successfulPrivateToolResult("issue-1")),
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
            privateToolExecutionService = executionService,
        )

        val reply = dataSource.fetchAssistantReply(
            prompt = promptWithPrivateTools(),
            model = "gpt-4.1-mini",
        )

        assertEquals("Recovered", reply.content)
        assertEquals(
            buildJsonObject {},
            executionService.executeRequests.single().arguments,
        )
    }

    @Test
    fun fetchAssistantReplyReturnsToolFailureOutputWhenPrivateToolThrows() = runSuspendTest {
        val requestBodies = mutableListOf<String>()
        val executionService = RecordingPrivateToolExecutionService(
            toolResultsByName = mapOf(
                "linear__search_issues" to Result.failure(IllegalStateException("Broken pipe")),
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
            privateToolExecutionService = executionService,
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
    fun fetchAssistantReplyExecutesBuiltInNotifyUserTool() = runSuspendTest {
        val requestBodies = mutableListOf<String>()
        val executionService = RecordingPrivateToolExecutionService(
            toolResultsByName = mapOf(
                "notify_user" to Result.success(
                    PrivateToolResult(
                        isError = false,
                        content = buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", "Notification sent")
                                },
                            )
                        },
                        structuredContent = buildJsonObject {
                            put("title", "Heads up")
                            put("message", "Build finished")
                            put("backend", "osascript")
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
                          "name": "notify_user",
                          "arguments": "{\"message\":\"Build finished\",\"title\":\"Heads up\"}"
                        }
                      ]
                    }
                """.trimIndent(),
                """
                    {
                      "id": "resp_2",
                      "output_text": "Notified the user"
                    }
                """.trimIndent(),
            ),
            privateToolExecutionService = executionService,
        )

        val reply = dataSource.fetchAssistantReply(
            prompt = promptWithPrivateTools(
                privateTools = listOf(notifyUserToolBinding()),
            ),
            model = "gpt-4.1-mini",
        )

        assertEquals("Notified the user", reply.content)
        val firstRequest = json.parseToJsonElement(requestBodies.first()).jsonObject
        val tool = firstRequest["tools"]?.jsonArray?.single()?.jsonObject ?: error("Missing tool")
        assertEquals("function", tool["type"]?.jsonPrimitive?.content)
        assertEquals("notify_user", tool["name"]?.jsonPrimitive?.content)
        assertEquals(notifyUserToolBinding(), executionService.executeRequests.single().binding)
        val outputEnvelope = json.parseToJsonElement(
            json.parseToJsonElement(requestBodies[1]).jsonObject["input"]?.jsonArray?.single()?.jsonObject?.get("output")?.jsonPrimitive?.content
                ?: error("Missing tool output"),
        ).jsonObject
        assertEquals(true, outputEnvelope["ok"]?.jsonPrimitive?.content?.toBooleanStrict())
        assertEquals("notify_user", outputEnvelope["tool"]?.jsonPrimitive?.content)
    }

    @Test
    fun fetchAssistantReplyWrapsBuiltInToolFailureOutputAndContinues() = runSuspendTest {
        val requestBodies = mutableListOf<String>()
        val executionService = RecordingPrivateToolExecutionService(
            toolResultsByName = mapOf(
                "notify_user" to Result.failure(IllegalStateException("Notification Center unavailable")),
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
                          "name": "notify_user",
                          "arguments": "{\"message\":\"Build finished\"}"
                        }
                      ]
                    }
                """.trimIndent(),
                """
                    {
                      "id": "resp_2",
                      "output_text": "Could not notify the user"
                    }
                """.trimIndent(),
            ),
            privateToolExecutionService = executionService,
        )

        val reply = dataSource.fetchAssistantReply(
            prompt = promptWithPrivateTools(
                privateTools = listOf(notifyUserToolBinding()),
            ),
            model = "gpt-4.1-mini",
        )

        assertEquals("Could not notify the user", reply.content)
        val outputEnvelope = json.parseToJsonElement(
            json.parseToJsonElement(requestBodies[1]).jsonObject["input"]?.jsonArray?.single()?.jsonObject?.get("output")?.jsonPrimitive?.content
                ?: error("Missing tool output"),
        ).jsonObject
        assertEquals(false, outputEnvelope["ok"]?.jsonPrimitive?.content?.toBooleanStrict())
        assertEquals("notify_user", outputEnvelope["tool"]?.jsonPrimitive?.content)
        assertEquals("Notification Center unavailable", outputEnvelope["error"]?.jsonPrimitive?.content)
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
            privateToolExecutionService = RecordingPrivateToolExecutionService(
                toolResultsByName = mapOf(
                    "linear__search_issues" to Result.success(successfulPrivateToolResult("issue")),
                ),
            ),
        )

        val error = assertFailsWith<IllegalStateException> {
            dataSource.fetchAssistantReply(
                prompt = promptWithPrivateTools(),
                model = "gpt-4.1-mini",
            )
        }

        assertContains(error.message.orEmpty(), "more than 16 private")
        assertEquals(1, requestBodies.size)
    }

    private fun createDataSource(
        requestBodies: MutableList<String>,
        responses: List<String>,
        privateToolExecutionService: PrivateToolExecutionService = RecordingPrivateToolExecutionService(),
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
            privateToolExecutionService = privateToolExecutionService,
        )
    }

    private fun promptWithPrivateTools(
        privateTools: List<PrivateToolBinding> = listOf(privateToolBinding()),
    ): PromptRequestData {
        return PromptRequestData(
            systemPrompt = "System",
            messages = listOf(ConversationMessage.user("Find issues")),
            toolCapabilities = LlmToolCapabilities(
                privateTools = privateTools,
            ),
        )
    }

    private fun privateToolBinding(
        modelToolName: String = "linear__search_issues",
        sourceToolName: String = "search_issues",
    ): PrivateToolBinding {
        return PrivateToolBinding(
            modelToolName = modelToolName,
            target = PrivateToolTarget.Mcp(
                server = privateServer(),
                sourceToolName = sourceToolName,
            ),
            description = "Search Linear issues",
            parametersSchema = buildJsonObject {
                put("type", "object")
            },
        )
    }

    private fun notifyUserToolBinding(): PrivateToolBinding {
        return PrivateToolBinding(
            modelToolName = "notify_user",
            target = PrivateToolTarget.BuiltIn(toolId = "notify_user"),
            description = "Send a local macOS notification to the user.",
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

    private fun successfulPrivateToolResult(text: String): PrivateToolResult {
        return PrivateToolResult(
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

private class RecordingPrivateToolExecutionService(
    private val toolResultsByName: Map<String, Result<PrivateToolResult>> = emptyMap(),
) : PrivateToolExecutionService {
    val executeRequests = mutableListOf<RecordedPrivateToolExecution>()

    override suspend fun execute(binding: PrivateToolBinding, arguments: JsonObject): PrivateToolResult {
        executeRequests += RecordedPrivateToolExecution(
            binding = binding,
            arguments = arguments,
        )
        return toolResultsByName[binding.modelToolName]
            ?.getOrThrow()
            ?: error("No prepared private tool result for ${binding.modelToolName}")
    }
}

private data class RecordedPrivateToolExecution(
    val binding: PrivateToolBinding,
    val arguments: JsonObject,
)

private class RecordingToolCallTraceObserver(
    private val events: MutableList<ToolCallTraceEvent>,
) : ToolCallTraceObserver {
    override suspend fun onToolCallTrace(event: ToolCallTraceEvent) {
        events += event
    }
}

private fun runSuspendTest(block: suspend () -> Unit) {
    runBlocking {
        block()
    }
}
