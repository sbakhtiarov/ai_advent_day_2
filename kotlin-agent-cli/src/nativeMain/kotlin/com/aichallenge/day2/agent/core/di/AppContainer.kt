package com.aichallenge.day2.agent.core.di

import com.aichallenge.day2.agent.core.config.AppConfig
import com.aichallenge.day2.agent.core.logging.ApiTrafficFileLogger
import com.aichallenge.day2.agent.data.remote.OpenAiRemoteDataSource
import com.aichallenge.day2.agent.data.repository.OpenAiAgentRepository
import com.aichallenge.day2.agent.domain.usecase.BuildPromptUseCase
import com.aichallenge.day2.agent.domain.usecase.SendPromptUseCase
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class AppContainer(
    config: AppConfig,
) {
    private val json = Json {
        ignoreUnknownKeys = true
    }
    private val apiTrafficLogger = config.apiTrafficLogFilePath?.let(::ApiTrafficFileLogger)
    private val httpClient = HttpClient(Curl) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 120_000
            socketTimeoutMillis = 120_000
        }
    }

    private val remoteDataSource = OpenAiRemoteDataSource(
        httpClient = httpClient,
        config = config,
        json = json,
        apiTrafficLogger = apiTrafficLogger,
    )

    private val repository = OpenAiAgentRepository(remoteDataSource)

    val buildPromptUseCase = BuildPromptUseCase()
    val sendPromptUseCase = SendPromptUseCase(repository)

    fun close() {
        httpClient.close()
    }
}
