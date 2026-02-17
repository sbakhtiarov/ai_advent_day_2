package com.aichallenge.day2.agent.core.di

import com.aichallenge.day2.agent.core.config.AppConfig
import com.aichallenge.day2.agent.data.remote.OpenAiRemoteDataSource
import com.aichallenge.day2.agent.data.repository.OpenAiAgentRepository
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
    private val httpClient = HttpClient(Curl) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                },
            )
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
    )

    private val repository = OpenAiAgentRepository(remoteDataSource)

    val sendPromptUseCase = SendPromptUseCase(repository)

    fun close() {
        httpClient.close()
    }
}
