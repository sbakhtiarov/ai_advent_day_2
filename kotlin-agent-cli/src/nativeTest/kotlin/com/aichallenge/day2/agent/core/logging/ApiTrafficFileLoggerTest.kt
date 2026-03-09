package com.aichallenge.day2.agent.core.logging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains

class ApiTrafficFileLoggerTest {
    @Test
    fun logsRequestAndResponseBodiesToFile() {
        val logFilePath = uniqueLogFilePath()
        val logger = ApiTrafficFileLogger(logFilePath)

        logger.logRequest(
            exchangeId = logger.reserveExchangeId(),
            method = "POST",
            url = "https://api.openai.com/v1/responses",
            headers = listOf(
                "Authorization" to "Bearer secret-token",
                "Content-Type" to "application/json",
            ),
            body = """{"model":"gpt-4.1-mini","input":[{"role":"user","content":[{"type":"input_text","text":"Hello"}]}]}""",
        )
        logger.logResponse(
            exchangeId = 1L,
            statusCode = 200,
            statusDescription = "OK",
            headers = listOf("Content-Type" to "application/json"),
            body = """{"output_text":"Hi"}""",
        )

        val loggedPayload = logger.readAll().orEmpty()

        assertContains(loggedPayload, "POST https://api.openai.com/v1/responses")
        assertContains(loggedPayload, """"model":"gpt-4.1-mini"""")
        assertContains(loggedPayload, "HTTP 200 OK")
        assertContains(loggedPayload, """"output_text":"Hi"""")
        assertContains(loggedPayload, "Authorization: Bearer <redacted>")
    }
}

private fun uniqueLogFilePath(): String {
    val seed = Random.nextLong().toString().replace('-', '0')
    return "/tmp/kotlin-agent-cli-tests/$seed/openai-api-traffic.log"
}
