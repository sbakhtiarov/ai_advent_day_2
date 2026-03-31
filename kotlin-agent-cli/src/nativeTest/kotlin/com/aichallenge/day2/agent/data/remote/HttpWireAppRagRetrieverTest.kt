package com.aichallenge.day2.agent.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpWireAppRagRetrieverTest {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Test
    fun retrieveDecodesReturnedChunks() = runBlocking {
        val requestBodies = mutableListOf<String>()
        val retriever = createRetriever(
            requestBodies = requestBodies,
            statusCode = HttpStatusCode.OK,
            responseBody = """
                {
                  "retrieved_chunks": [
                    {
                      "chunk_id": "architecture-1",
                      "section_name": "Navigation Architecture",
                      "heading_path": "Architecture > Navigation Architecture",
                      "source_path": "architecture.md",
                      "score": 0.91,
                      "content": "Navigation is coordinated by the app module."
                    }
                  ]
                }
            """.trimIndent(),
        )

        val chunks = retriever.retrieve("How does navigation work?")

        assertEquals(1, chunks.size)
        assertEquals("architecture-1", chunks.single().chunkId)
        assertEquals("Navigation Architecture", chunks.single().sectionName)
        val requestJson = json.parseToJsonElement(requestBodies.single()).jsonObject
        assertEquals("How does navigation work?", requestJson["question"]?.jsonPrimitive?.content)
    }

    @Test
    fun retrieveFailsOnNonSuccessStatus() = runBlocking {
        val retriever = createRetriever(
            requestBodies = mutableListOf(),
            statusCode = HttpStatusCode.BadGateway,
            responseBody = """{"detail":"upstream unavailable"}""",
        )

        val error = assertFailsWith<IllegalStateException> {
            retriever.retrieve("How does navigation work?")
        }

        assertContains(error.message.orEmpty(), "HTTP 502")
    }

    @Test
    fun retrieveFailsOnMalformedBody() = runBlocking {
        val retriever = createRetriever(
            requestBodies = mutableListOf(),
            statusCode = HttpStatusCode.OK,
            responseBody = """{"retrieved_chunks":"oops"}""",
        )

        assertFailsWith<Throwable> {
            retriever.retrieve("How does navigation work?")
        }
        Unit
    }

    private fun createRetriever(
        requestBodies: MutableList<String>,
        statusCode: HttpStatusCode,
        responseBody: String,
    ): HttpWireAppRagRetriever {
        val httpClient = HttpClient(
            MockEngine { request ->
                requestBodies += request.body.toByteArray().decodeToString()
                respond(
                    content = responseBody,
                    status = statusCode,
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

        return HttpWireAppRagRetriever(
            httpClient = httpClient,
            baseUrl = "http://localhost:8000",
            json = json,
        )
    }
}
