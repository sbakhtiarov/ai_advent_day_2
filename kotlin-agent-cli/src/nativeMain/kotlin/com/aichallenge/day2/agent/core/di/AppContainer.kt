package com.aichallenge.day2.agent.core.di

import com.aichallenge.day2.agent.core.config.AppConfig
import com.aichallenge.day2.agent.core.config.ApiSettingsService
import com.aichallenge.day2.agent.core.logging.ApiTrafficFileLogger
import com.aichallenge.day2.agent.data.remote.HttpWireAppRagRetriever
import com.aichallenge.day2.agent.data.mcp.SdkMcpRuntimeService
import com.aichallenge.day2.agent.data.tools.BuiltInToolRegistry
import com.aichallenge.day2.agent.data.tools.DefaultPrivateToolExecutionService
import com.aichallenge.day2.agent.data.remote.OpenAiRemoteDataSource
import com.aichallenge.day2.agent.data.repository.OpenAiAgentRepository
import com.aichallenge.day2.agent.domain.service.McpRuntimeService
import com.aichallenge.day2.agent.domain.service.WireAppRagRetriever
import com.aichallenge.day2.agent.domain.usecase.BuildPromptUseCase
import com.aichallenge.day2.agent.domain.usecase.SendPromptUseCase
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class AppContainer(
    config: AppConfig,
    apiSettingsService: ApiSettingsService,
    startupWorkingDirectory: String?,
) {
    private val json = Json {
        ignoreUnknownKeys = true
    }
    private val apiTrafficLogger = config.apiTrafficLogFilePath?.let(::ApiTrafficFileLogger)
    private val openAiHttpClient = HttpClient(Curl) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 120_000
            socketTimeoutMillis = 120_000
        }
    }
    private val mcpHttpClient = HttpClient(Curl) {
        install(SSE)
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }
    }
    val mcpRuntimeService: McpRuntimeService = SdkMcpRuntimeService.create(
        httpClient = mcpHttpClient,
        startupWorkingDirectory = startupWorkingDirectory,
    )
    val builtInToolRegistry = BuiltInToolRegistry.createDefault(
        httpClient = openAiHttpClient,
        json = json,
    )
    private val privateToolExecutionService = DefaultPrivateToolExecutionService(
        mcpRuntimeService = mcpRuntimeService,
        builtInToolRegistry = builtInToolRegistry,
    )

    private val remoteDataSource = OpenAiRemoteDataSource(
        httpClient = openAiHttpClient,
        apiSettingsService = apiSettingsService,
        json = json,
        apiTrafficLogger = apiTrafficLogger,
        privateToolExecutionService = privateToolExecutionService,
    )

    private val repository = OpenAiAgentRepository(remoteDataSource)

    val buildPromptUseCase = BuildPromptUseCase()
    val sendPromptUseCase = SendPromptUseCase(repository)
    val wireAppRagRetriever: WireAppRagRetriever = HttpWireAppRagRetriever(
        httpClient = openAiHttpClient,
        baseUrl = config.wireAppRagBaseUrl,
        json = json,
    )

    suspend fun close() {
        mcpRuntimeService.close()
        openAiHttpClient.close()
        mcpHttpClient.close()
    }
}
