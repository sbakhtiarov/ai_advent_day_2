package com.aichallenge.day2.agent.domain.model

data class WorkingMemoryState(
    val taskState: WorkingTaskState,
)

data class WorkingTaskState(
    val goal: String = "",
    val constraints: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val assumptions: List<String> = emptyList(),
    val openQuestions: List<String> = emptyList(),
    val nextSteps: List<String> = emptyList(),
    val artifacts: List<String> = emptyList(),
)
