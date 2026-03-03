package com.aichallenge.day2.agent.presentation.cli

import com.aichallenge.day2.agent.domain.model.ProfilePreferenceState
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class SystemPromptBuilderTest {
    private val builder = SystemPromptBuilder()

    @Test
    fun buildIncludesConfiguredOutputRulesAndStopBehavior() {
        val prompt = builder.build(
            basePrompt = "Base system prompt",
            selection = ConfigMenuSelection(
                format = OutputFormatOption.MARKDOWN,
                maxOutputTokens = 256,
                stopSequence = "DONE",
            ),
        )

        assertContains(prompt, "Base system prompt")
        assertContains(prompt, "Format: Markdown")
        assertContains(prompt, "Max output tokens: 256")
        assertContains(prompt, "Stop sequence: DONE")
        assertContains(prompt, """When user sends "DONE" stop generating questions and provide short summary""")
    }

    @Test
    fun buildUsesDefaultStopBehaviorWhenNoStopSequenceIsProvided() {
        val prompt = builder.build(
            basePrompt = "Base system prompt",
            selection = ConfigMenuSelection(
                format = OutputFormatOption.PLAIN_TEXT,
                maxOutputTokens = null,
                stopSequence = "",
            ),
        )

        assertContains(prompt, "Stop sequence: (none)")
        assertContains(prompt, "Stop behavior: No explicit stop sequence behavior.")
    }

    @Test
    fun buildIncludesUserDefinedProfileBlockWhenProvided() {
        val prompt = builder.build(
            basePrompt = "Base system prompt",
            selection = ConfigMenuSelection.default(),
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
            selection = ConfigMenuSelection.default(),
            userDefinedProfile = ProfilePreferenceState(),
        )

        assertFalse(prompt.contains("User-defined profile defaults (highest priority):"))
    }
}
