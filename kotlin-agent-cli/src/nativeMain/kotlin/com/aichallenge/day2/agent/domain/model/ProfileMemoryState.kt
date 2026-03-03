package com.aichallenge.day2.agent.domain.model

data class ProfileMemoryState(
    val preferences: ProfilePreferenceState = ProfilePreferenceState(),
    val environmentFacts: ProfileEnvironmentFacts = ProfileEnvironmentFacts(),
)

data class ProfilePreferenceState(
    val writingStyle: String = "",
    val toolingPreferences: List<String> = emptyList(),
    val workflowDefaults: List<String> = emptyList(),
    val stableConstraints: List<String> = emptyList(),
    val name: String = "",
    val work: String = "",
    val profession: String = "",
    val otherFacts: List<String> = emptyList(),
)

data class ProfileEnvironmentFacts(
    val timezone: String = "",
    val os: String = "",
    val repoPath: String = "",
)
