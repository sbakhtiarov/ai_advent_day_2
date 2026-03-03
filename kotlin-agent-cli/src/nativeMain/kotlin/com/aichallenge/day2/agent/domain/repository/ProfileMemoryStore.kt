package com.aichallenge.day2.agent.domain.repository

import com.aichallenge.day2.agent.domain.model.ProfileMemoryState

interface ProfileMemoryStore {
    fun load(): ProfileMemoryState?

    fun save(state: ProfileMemoryState)

    fun clear()
}
