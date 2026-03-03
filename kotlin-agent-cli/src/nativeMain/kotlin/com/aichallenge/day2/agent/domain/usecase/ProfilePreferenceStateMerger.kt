package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.ProfilePreferenceState

object ProfilePreferenceStateMerger {
    fun merge(
        distilled: ProfilePreferenceState,
        userDefined: ProfilePreferenceState?,
    ): ProfilePreferenceState {
        if (userDefined == null) {
            return normalize(distilled)
        }

        val normalizedDistilled = normalize(distilled)
        val normalizedUserDefined = normalize(userDefined)
        return ProfilePreferenceState(
            writingStyle = normalizedUserDefined.writingStyle.ifBlank { normalizedDistilled.writingStyle },
            toolingPreferences = normalizedUserDefined.toolingPreferences.ifEmpty { normalizedDistilled.toolingPreferences },
            workflowDefaults = normalizedUserDefined.workflowDefaults.ifEmpty { normalizedDistilled.workflowDefaults },
            stableConstraints = normalizedUserDefined.stableConstraints.ifEmpty { normalizedDistilled.stableConstraints },
            name = normalizedUserDefined.name.ifBlank { normalizedDistilled.name },
            work = normalizedUserDefined.work.ifBlank { normalizedDistilled.work },
            profession = normalizedUserDefined.profession.ifBlank { normalizedDistilled.profession },
            otherFacts = normalizedUserDefined.otherFacts.ifEmpty { normalizedDistilled.otherFacts },
        )
    }

    private fun normalize(state: ProfilePreferenceState): ProfilePreferenceState = ProfilePreferenceState(
        writingStyle = state.writingStyle.trim(),
        toolingPreferences = normalizeList(state.toolingPreferences),
        workflowDefaults = normalizeList(state.workflowDefaults),
        stableConstraints = normalizeList(state.stableConstraints),
        name = state.name.trim(),
        work = state.work.trim(),
        profession = state.profession.trim(),
        otherFacts = normalizeList(state.otherFacts),
    )

    private fun normalizeList(values: List<String>): List<String> {
        return values.map { value -> value.trim() }
            .filter { value -> value.isNotEmpty() }
            .distinct()
    }
}
