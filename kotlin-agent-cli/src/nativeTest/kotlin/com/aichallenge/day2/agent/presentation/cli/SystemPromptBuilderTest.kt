package com.aichallenge.day2.agent.presentation.cli

import com.aichallenge.day2.agent.domain.model.ProfilePreferenceState
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SystemPromptBuilderTest {
    private val builder = SystemPromptBuilder()

    @Test
    fun buildReturnsBasePromptWhenNoUserDefinedProfileIsProvided() {
        val prompt = builder.build(
            basePrompt = "Base system prompt",
        )

        assertEquals("Base system prompt", prompt)
    }

    @Test
    fun buildIncludesUserDefinedProfileBlockWhenProvided() {
        val prompt = builder.build(
            basePrompt = "Base system prompt",
            userDefinedProfile = ProfilePreferenceState(
                writingStyle = " concise bullets ",
                toolingPreferences = listOf("use rg", " use rg "),
                name = " Alex ",
            ),
        )

        assertContains(prompt, "User-defined profile defaults (highest priority):")
        assertContains(prompt, "\"writing_style\":\"concise bullets\"")
        assertContains(prompt, "\"tooling_preferences\":[\"use rg\"]")
        assertContains(prompt, "\"name\":\"Alex\"")
        assertContains(prompt, "override inferred and distilled profile memory")
    }

    @Test
    fun buildOmitsUserDefinedProfileBlockWhenProvidedProfileIsEmpty() {
        val prompt = builder.build(
            basePrompt = "Base system prompt",
            userDefinedProfile = ProfilePreferenceState(),
        )

        assertFalse(prompt.contains("User-defined profile defaults (highest priority):"))
    }
}
