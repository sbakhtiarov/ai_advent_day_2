package com.aichallenge.day2.agent.domain.repository

import com.aichallenge.day2.agent.domain.model.ScheduledAgentJob

interface ScheduledJobStore {
    fun load(): List<ScheduledAgentJob>
    fun save(jobs: List<ScheduledAgentJob>)
}
