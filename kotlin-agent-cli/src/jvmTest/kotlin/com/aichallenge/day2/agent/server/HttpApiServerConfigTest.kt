package com.aichallenge.day2.agent.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HttpApiServerConfigTest {
    @Test
    fun loadsConfigFromEnvironment() {
        val config = HttpApiServerConfig.fromEnvironment(
            mapOf(
                "PORT" to "9090",
                "AGENT_API_BASE_URL" to "https://api.openai.com/v1/",
                "AGENT_API_KEY" to "sk-test",
                "AGENT_API_MODEL" to "gpt-5.2-codex",
                "AGENT_API_TEMPERATURE" to "0.4",
                "WIRE_APP_RAG_BASE_URL" to "http://rag.internal:8000/",
                "OPENAI_API_LOG_FILE" to "",
            ),
        )

        assertEquals(9090, config.port)
        assertEquals("https://api.openai.com/v1", config.apiBaseUrl)
        assertEquals("sk-test", config.apiKey)
        assertEquals("gpt-5.2-codex", config.apiModel)
        assertEquals(0.4, config.apiTemperature)
        assertEquals("http://rag.internal:8000", config.wireAppRagBaseUrl)
        assertNull(config.apiLogFilePath)
    }

    @Test
    fun missingRequiredValueFailsFast() {
        val error = assertFailsWith<IllegalStateException> {
            HttpApiServerConfig.fromEnvironment(
                mapOf(
                    "AGENT_API_KEY" to "sk-test",
                    "AGENT_API_MODEL" to "gpt-5.2-codex",
                    "WIRE_APP_RAG_BASE_URL" to "http://rag.internal:8000",
                ),
            )
        }

        assertEquals("AGENT_API_BASE_URL must be set.", error.message)
    }
}
