package com.aichallenge.day2.agent.core.config

import kotlin.test.Test
import kotlin.test.assertEquals

class AppRuntimeEnvironmentTest {
    @Test
    fun timeZoneIdPrefersTzEnvironmentVariable() {
        val environment = TestAppRuntimeEnvironment(
            values = mapOf("TZ" to "Europe/Berlin"),
            fallbackTimeZoneId = "UTC",
        )

        assertEquals("Europe/Berlin", environment.timeZoneId())
    }

    @Test
    fun timeZoneIdFallsBackToSystemTimeZoneWhenTzIsMissingOrBlank() {
        val missingTz = TestAppRuntimeEnvironment(
            values = emptyMap(),
            fallbackTimeZoneId = "America/New_York",
        )
        val blankTz = TestAppRuntimeEnvironment(
            values = mapOf("TZ" to "   "),
            fallbackTimeZoneId = "America/Los_Angeles",
        )

        assertEquals("America/New_York", missingTz.timeZoneId())
        assertEquals("America/Los_Angeles", blankTz.timeZoneId())
    }
}

private class TestAppRuntimeEnvironment(
    private val values: Map<String, String>,
    private val fallbackTimeZoneId: String?,
) : DefaultAppRuntimeEnvironment() {
    override fun readEnvironmentVariable(name: String): String? = values[name]

    override fun systemTimeZoneId(): String? = fallbackTimeZoneId
}
