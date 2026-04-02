package com.aichallenge.day2.agent.server

import com.aichallenge.day2.agent.review.PullRequestReviewService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.serialization.json.Json
import kotlin.system.exitProcess

fun main() {
    val config = runCatching { HttpApiServerConfig.fromEnvironment() }
        .getOrElse { error ->
            System.err.println("Configuration error: ${error.message}")
            exitProcess(1)
        }
    val json = Json {
        ignoreUnknownKeys = true
    }
    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }
    val apiTrafficLogger = config.apiLogFilePath?.let(::JvmApiTrafficFileLogger)
    val reviewService = PullRequestReviewService(
        pullRequestFetcher = JvmGithubPullRequestFetcher(
            httpClient = httpClient,
            json = json,
        ),
        ragRetriever = JvmWireAppRagRetriever(
            httpClient = httpClient,
            baseUrl = config.wireAppRagBaseUrl,
            json = json,
        ),
        modelClient = OpenAiPullRequestReviewModelClient(
            httpClient = httpClient,
            json = json,
            config = config,
            apiTrafficLogger = apiTrafficLogger,
        ),
    )

    embeddedServer(
        factory = Netty,
        host = "0.0.0.0",
        port = config.port,
    ) {
        reviewApi { prUrl ->
            reviewService.review(prUrl)
        }
    }.start(wait = true)
}
