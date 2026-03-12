@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.aichallenge.day2.agent.domain.model

import kotlinx.datetime.Instant

enum class ScheduledJobScheduleType {
    ONCE,
    REPEAT,
}

enum class ScheduledJobStatus {
    SCHEDULED,
    COMPLETED,
    CANCELLED,
    ERROR,
}

enum class ScheduledJobRunStatus {
    SUCCESS,
    FAILURE,
}

data class ScheduledAgentJob(
    val scheduleId: String,
    val label: String,
    val prompt: String,
    val workingDirectory: String,
    val scheduleType: ScheduledJobScheduleType,
    val runAt: Instant? = null,
    val startsAt: Instant? = null,
    val intervalMinutes: Int? = null,
    val createdAt: Instant,
    val nextRunAt: Instant? = null,
    val lastRunAt: Instant? = null,
    val lastRunStatus: ScheduledJobRunStatus? = null,
    val status: ScheduledJobStatus,
    val launchdLabel: String,
    val plistPath: String,
    val logPath: String,
    val lastErrorMessage: String? = null,
) {
    init {
        require(scheduleId.isNotBlank()) {
            "scheduleId must not be blank."
        }
        require(label.isNotBlank()) {
            "label must not be blank."
        }
        require(prompt.isNotBlank()) {
            "prompt must not be blank."
        }
        require(workingDirectory.isNotBlank()) {
            "workingDirectory must not be blank."
        }
        require(launchdLabel.isNotBlank()) {
            "launchdLabel must not be blank."
        }
        require(plistPath.isNotBlank()) {
            "plistPath must not be blank."
        }
        require(logPath.isNotBlank()) {
            "logPath must not be blank."
        }

        when (scheduleType) {
            ScheduledJobScheduleType.ONCE -> {
                require(runAt != null) {
                    "runAt is required for one-shot schedules."
                }
                require(startsAt == null) {
                    "startsAt must be null for one-shot schedules."
                }
                require(intervalMinutes == null) {
                    "intervalMinutes must be null for one-shot schedules."
                }
            }

            ScheduledJobScheduleType.REPEAT -> {
                require(runAt == null) {
                    "runAt must be null for repeating schedules."
                }
                require(startsAt != null) {
                    "startsAt is required for repeating schedules."
                }
                require(intervalMinutes != null && intervalMinutes > 0) {
                    "intervalMinutes must be positive for repeating schedules."
                }
            }
        }
    }
}
