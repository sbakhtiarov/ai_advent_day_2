package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.ProfilePreferenceState
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfilePreferenceStateMergerTest {
    @Test
    fun mergeUsesUserDefinedNonEmptyFieldsAsHighestPriority() {
        val distilled = ProfilePreferenceState(
            writingStyle = "detailed",
            toolingPreferences = listOf("use grep"),
            workflowDefaults = listOf("ask before coding"),
            stableConstraints = listOf("do not edit tests"),
            name = "Alex",
            work = "Platform",
            profession = "Engineer",
            otherFacts = listOf("based in Berlin"),
        )
        val userDefined = ProfilePreferenceState(
            writingStyle = " concise bullets ",
            toolingPreferences = listOf(" use rg ", "use rg"),
            workflowDefaults = emptyList(),
            stableConstraints = listOf(" avoid destructive git commands "),
            name = "",
            work = " Mobile platform at Wire ",
            profession = "",
            otherFacts = listOf(" prefers Kotlin ", "prefers Kotlin"),
        )

        val merged = ProfilePreferenceStateMerger.merge(
            distilled = distilled,
            userDefined = userDefined,
        )

        assertEquals("concise bullets", merged.writingStyle)
        assertEquals(listOf("use rg"), merged.toolingPreferences)
        assertEquals(listOf("ask before coding"), merged.workflowDefaults)
        assertEquals(listOf("avoid destructive git commands"), merged.stableConstraints)
        assertEquals("Alex", merged.name)
        assertEquals("Mobile platform at Wire", merged.work)
        assertEquals("Engineer", merged.profession)
        assertEquals(listOf("prefers Kotlin"), merged.otherFacts)
    }

    @Test
    fun mergeReturnsNormalizedDistilledStateWhenUserDefinedIsNull() {
        val distilled = ProfilePreferenceState(
            writingStyle = " concise ",
            toolingPreferences = listOf(" use rg ", "", "use rg"),
            otherFacts = listOf(" fact ", "fact"),
        )

        val merged = ProfilePreferenceStateMerger.merge(
            distilled = distilled,
            userDefined = null,
        )

        assertEquals("concise", merged.writingStyle)
        assertEquals(listOf("use rg"), merged.toolingPreferences)
        assertEquals(listOf("fact"), merged.otherFacts)
    }
}
