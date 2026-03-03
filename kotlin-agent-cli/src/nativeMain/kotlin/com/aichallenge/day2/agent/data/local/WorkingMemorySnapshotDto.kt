package com.aichallenge.day2.agent.data.local

import com.aichallenge.day2.agent.domain.model.WorkingMemoryState
import com.aichallenge.day2.agent.domain.model.WorkingTaskState
import kotlinx.serialization.Serializable

@Serializable
data class WorkingMemorySnapshotDto(
    val version: Int,
    val taskState: PersistedWorkingTaskStateDto,
)

@Serializable
data class PersistedWorkingTaskStateDto(
    val goal: String = "",
    val constraints: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val assumptions: List<String> = emptyList(),
    val openQuestions: List<String> = emptyList(),
    val nextSteps: List<String> = emptyList(),
    val artifacts: List<String> = emptyList(),
)

fun WorkingMemoryState.toPersistedDto(version: Int): WorkingMemorySnapshotDto = WorkingMemorySnapshotDto(
    version = version,
    taskState = taskState.toPersistedDto(),
)

fun WorkingMemorySnapshotDto.toDomainModel(): WorkingMemoryState = WorkingMemoryState(
    taskState = taskState.toDomainModel(),
)

private fun WorkingTaskState.toPersistedDto(): PersistedWorkingTaskStateDto = PersistedWorkingTaskStateDto(
    goal = goal,
    constraints = constraints,
    decisions = decisions,
    assumptions = assumptions,
    openQuestions = openQuestions,
    nextSteps = nextSteps,
    artifacts = artifacts,
)

private fun PersistedWorkingTaskStateDto.toDomainModel(): WorkingTaskState = WorkingTaskState(
    goal = goal,
    constraints = constraints,
    decisions = decisions,
    assumptions = assumptions,
    openQuestions = openQuestions,
    nextSteps = nextSteps,
    artifacts = artifacts,
)
