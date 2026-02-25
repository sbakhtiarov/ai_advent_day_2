package com.aichallenge.day2.agent.domain.repository

import com.aichallenge.day2.agent.domain.model.SessionMemoryState

interface SessionMemoryStore {
    fun load(): SessionMemoryState?

    fun save(state: SessionMemoryState)

    fun clear()
}
