package com.aichallenge.day2.agent.domain.repository

import com.aichallenge.day2.agent.domain.model.ProfilePreferenceState

interface UserDefinedProfileStore {
    fun load(): ProfilePreferenceState?
}
