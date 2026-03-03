package com.aichallenge.day2.agent.domain.repository

import com.aichallenge.day2.agent.domain.model.ProfilePreferenceState
import com.aichallenge.day2.agent.domain.model.UserProfileOption

interface UserDefinedProfileStore {
    fun load(): ProfilePreferenceState?
    fun listProfiles(): List<UserProfileOption>
    fun activeProfileFileName(): String?
    fun setActiveProfile(fileName: String): Boolean
}
