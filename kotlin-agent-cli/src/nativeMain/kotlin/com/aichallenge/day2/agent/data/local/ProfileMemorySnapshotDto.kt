package com.aichallenge.day2.agent.data.local

import com.aichallenge.day2.agent.domain.model.ProfileEnvironmentFacts
import com.aichallenge.day2.agent.domain.model.ProfileMemoryState
import com.aichallenge.day2.agent.domain.model.ProfilePreferenceState
import kotlinx.serialization.Serializable

@Serializable
data class ProfileMemorySnapshotDto(
    val version: Int,
    val preferences: PersistedProfilePreferenceStateDto,
    val environmentFacts: PersistedProfileEnvironmentFactsDto,
)

@Serializable
data class PersistedProfilePreferenceStateDto(
    val writingStyle: String = "",
    val toolingPreferences: List<String> = emptyList(),
    val workflowDefaults: List<String> = emptyList(),
    val stableConstraints: List<String> = emptyList(),
    val name: String = "",
    val work: String = "",
    val profession: String = "",
    val otherFacts: List<String> = emptyList(),
)

@Serializable
data class PersistedProfileEnvironmentFactsDto(
    val timezone: String = "",
    val os: String = "",
    val repoPath: String = "",
)

fun ProfileMemoryState.toPersistedDto(version: Int): ProfileMemorySnapshotDto = ProfileMemorySnapshotDto(
    version = version,
    preferences = preferences.toPersistedDto(),
    environmentFacts = environmentFacts.toPersistedDto(),
)

fun ProfileMemorySnapshotDto.toDomainModel(): ProfileMemoryState = ProfileMemoryState(
    preferences = preferences.toDomainModel(),
    environmentFacts = environmentFacts.toDomainModel(),
)

private fun ProfilePreferenceState.toPersistedDto(): PersistedProfilePreferenceStateDto = PersistedProfilePreferenceStateDto(
    writingStyle = writingStyle,
    toolingPreferences = toolingPreferences,
    workflowDefaults = workflowDefaults,
    stableConstraints = stableConstraints,
    name = name,
    work = work,
    profession = profession,
    otherFacts = otherFacts,
)

private fun PersistedProfilePreferenceStateDto.toDomainModel(): ProfilePreferenceState = ProfilePreferenceState(
    writingStyle = writingStyle,
    toolingPreferences = toolingPreferences,
    workflowDefaults = workflowDefaults,
    stableConstraints = stableConstraints,
    name = name,
    work = work,
    profession = profession,
    otherFacts = otherFacts,
)

private fun ProfileEnvironmentFacts.toPersistedDto(): PersistedProfileEnvironmentFactsDto = PersistedProfileEnvironmentFactsDto(
    timezone = timezone,
    os = os,
    repoPath = repoPath,
)

private fun PersistedProfileEnvironmentFactsDto.toDomainModel(): ProfileEnvironmentFacts = ProfileEnvironmentFacts(
    timezone = timezone,
    os = os,
    repoPath = repoPath,
)
