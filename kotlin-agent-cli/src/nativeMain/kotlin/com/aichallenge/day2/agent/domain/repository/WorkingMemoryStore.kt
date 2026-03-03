package com.aichallenge.day2.agent.domain.repository

import com.aichallenge.day2.agent.domain.model.WorkingMemoryState

interface WorkingMemoryStore {
    fun load(): WorkingMemoryState?

    fun save(state: WorkingMemoryState)

    fun clear()
}
