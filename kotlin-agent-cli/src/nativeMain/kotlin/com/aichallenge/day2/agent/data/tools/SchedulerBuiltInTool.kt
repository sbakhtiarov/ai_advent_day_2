@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.domain.model.PrivateToolResult
import com.aichallenge.day2.agent.domain.model.ScheduledAgentJob
import com.aichallenge.day2.agent.domain.model.ScheduledJobScheduleType
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val SCHEDULER_TOOL_ID = "scheduler"

fun schedulerToolRegistration(
    schedulerService: LaunchdSchedulerService,
): BuiltInToolRegistration {
    return BuiltInToolRegistration(
        definition = BuiltInToolDefinition(
            toolId = SCHEDULER_TOOL_ID,
            modelToolName = SCHEDULER_TOOL_ID,
            description = "Query the user's current local time with timezone-aware output, schedule one-shot prompts for an explicit future timestamp, schedule one-shot prompts by a relative delay from now, schedule repeating prompts, list scheduled jobs, and cancel scheduled jobs. Use action 'current_time' when the user asks what time it is, asks for their local time, or gives a local clock time without a timezone/date/offset such as 'at 07:55' and you need the user's local date/timezone to schedule correctly. Use action 'delay' for prompts like 'in 5 minutes' or 'in 2 hours', and omit schedule_type for delay requests. For relative delay requests, do not calculate local clock time yourself and do not reject as past time; use 'delay'. Do not ask the user for timezone if 'current_time' can provide it.",
            parametersSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "action",
                            buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray {
                                    add(JsonPrimitive("create"))
                                    add(JsonPrimitive("delay"))
                                    add(JsonPrimitive("list"))
                                    add(JsonPrimitive("cancel"))
                                    add(JsonPrimitive("current_time"))
                                })
                                put("description", "Scheduler operation to perform. Use 'current_time' when the user asks for the current time, local time, or user time, or when you need the user's current local date/timezone to interpret a local schedule request like 'at 07:55'. Use 'delay' for relative one-shot scheduling such as 'in 5 minutes' or 'in 2 hours'; omit schedule_type and do not treat those requests as past local clock times. Use 'create', 'list', or 'cancel' for explicit schedule management.")
                            },
                        )
                        put(
                            "prompt",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Prompt to run when action is 'create' or 'delay'.")
                            },
                        )
                        put(
                            "label",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Optional human-readable label for the schedule.")
                            },
                        )
                        put(
                            "schedule_type",
                            buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray {
                                    add(JsonPrimitive("once"))
                                    add(JsonPrimitive("repeat"))
                                })
                                put("description", "Schedule mode for action 'create'.")
                            },
                        )
                        put(
                            "run_at",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "RFC3339 timestamp with explicit offset for one-shot schedules. If the user gave only a local clock time like 'at 07:55', use 'current_time' first to resolve the user's local date and timezone before setting run_at.")
                            },
                        )
                        put(
                            "starts_at",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "RFC3339 timestamp with explicit offset for the first repeating run. If the user gave only a local clock time, use 'current_time' first to resolve the user's local date and timezone before setting starts_at.")
                            },
                        )
                        put(
                            "interval_minutes",
                            buildJsonObject {
                                put("type", "integer")
                                put("description", "Whole-minute interval for repeating schedules.")
                            },
                        )
                        put(
                            "delay_amount",
                            buildJsonObject {
                                put("type", "integer")
                                put("description", "Positive integer delay from current time when action is 'delay'.")
                            },
                        )
                        put(
                            "delay_unit",
                            buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray {
                                    add(JsonPrimitive("minute"))
                                    add(JsonPrimitive("minutes"))
                                    add(JsonPrimitive("hour"))
                                    add(JsonPrimitive("hours"))
                                })
                                put("description", "Delay unit for action 'delay'. Accepted values: minute, minutes, hour, hours.")
                            },
                        )
                        put(
                            "schedule_id",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Schedule id for action 'cancel'.")
                            },
                        )
                    },
                )
                put("required", buildJsonArray { add(JsonPrimitive("action")) })
                put("additionalProperties", false)
            },
        ),
        executor = SchedulerBuiltInToolExecutor(schedulerService),
    )
}

class SchedulerBuiltInToolExecutor(
    private val schedulerService: LaunchdSchedulerService,
) : BuiltInToolExecutor {
    override suspend fun execute(arguments: JsonObject): PrivateToolResult {
        return when (requireStringArgument(arguments, "action").lowercase()) {
            "create" -> handleCreate(arguments)
            "delay" -> handleDelay(arguments)
            "list" -> handleList()
            "cancel" -> handleCancel(arguments)
            "current_time" -> handleCurrentTime()
            else -> throw IllegalArgumentException("action must be one of: create, delay, list, cancel, current_time.")
        }
    }

    private fun handleCreate(arguments: JsonObject): PrivateToolResult {
        val scheduleType = when (requireStringArgument(arguments, "schedule_type").lowercase()) {
            "once" -> ScheduledJobScheduleType.ONCE
            "repeat" -> ScheduledJobScheduleType.REPEAT
            else -> throw IllegalArgumentException("schedule_type must be 'once' or 'repeat'.")
        }
        val prompt = requireStringArgument(arguments, "prompt")
        val label = optionalStringArgument(arguments, "label")
        val runAt = optionalTimestampArgument(arguments, "run_at")
        val startsAt = optionalTimestampArgument(arguments, "starts_at")
        val intervalMinutes = optionalIntArgument(arguments, "interval_minutes")
        val job = schedulerService.createJob(
            when (scheduleType) {
                ScheduledJobScheduleType.ONCE -> {
                    if (startsAt != null || (intervalMinutes != null && intervalMinutes > 0)) {
                        throw IllegalArgumentException("One-shot schedules accept only run_at.")
                    }
                    CreateScheduledJobRequest(
                        prompt = prompt,
                        label = label,
                        scheduleType = scheduleType,
                        runAt = runAt,
                        startsAt = null,
                        intervalMinutes = null,
                    )
                }

                ScheduledJobScheduleType.REPEAT -> CreateScheduledJobRequest(
                    prompt = prompt,
                    label = label,
                    scheduleType = scheduleType,
                    runAt = runAt,
                    startsAt = startsAt,
                    intervalMinutes = intervalMinutes,
                )
            },
        )
        return PrivateToolResult(
            isError = false,
            content = textContent("Scheduled '${job.label}' with id ${job.scheduleId}."),
            structuredContent = buildJsonObject {
                put("action", "create")
                put("job", job.toStructuredJson())
            },
        )
    }

    private fun handleDelay(arguments: JsonObject): PrivateToolResult {
        val prompt = requireStringArgument(arguments, "prompt")
        val label = optionalStringArgument(arguments, "label")
        val delayAmount = requirePositiveIntArgument(arguments, "delay_amount")
        val delayUnit = requireDelayUnitArgument(arguments, "delay_unit")

        val scheduleType = optionalStringArgument(arguments, "schedule_type")?.lowercase()
        if (scheduleType != null && scheduleType != "once") {
            throw IllegalArgumentException("Action 'delay' is one-shot only; if 'schedule_type' is provided it must be 'once'.")
        }
        if (optionalTimestampArgument(arguments, "run_at") != null) {
            throw IllegalArgumentException("Action 'delay' does not accept 'run_at'.")
        }
        if (optionalTimestampArgument(arguments, "starts_at") != null) {
            throw IllegalArgumentException("Action 'delay' does not accept 'starts_at'.")
        }
        val intervalMinutes = optionalIntArgument(arguments, "interval_minutes")
        if (intervalMinutes != null && intervalMinutes != 0) {
            throw IllegalArgumentException("Action 'delay' does not accept non-zero 'interval_minutes'.")
        }
        if (optionalStringArgument(arguments, "schedule_id") != null) {
            throw IllegalArgumentException("Action 'delay' does not accept 'schedule_id'.")
        }

        val job = schedulerService.createDelayedOneShotJob(
            prompt = prompt,
            label = label,
            delayAmount = delayAmount,
            delayUnit = delayUnit,
        )
        return PrivateToolResult(
            isError = false,
            content = textContent("Scheduled '${job.label}' with id ${job.scheduleId}."),
            structuredContent = buildJsonObject {
                put("action", "delay")
                put("job", job.toStructuredJson())
            },
        )
    }

    private fun handleList(): PrivateToolResult {
        val jobs = schedulerService.listJobs()
        return PrivateToolResult(
            isError = false,
            content = textContent(
                if (jobs.isEmpty()) {
                    "No scheduled jobs found."
                } else {
                    "Found ${jobs.size} scheduled job(s)."
                },
            ),
            structuredContent = buildJsonObject {
                put("action", "list")
                put("count", jobs.size)
                put(
                    "jobs",
                    buildJsonArray {
                        jobs.forEach { job ->
                            add(job.toStructuredJson())
                        }
                    },
                )
            },
        )
    }

    private fun handleCancel(arguments: JsonObject): PrivateToolResult {
        val result = schedulerService.cancelJob(
            scheduleId = requireStringArgument(arguments, "schedule_id"),
        )
        val text = when {
            result.job == null -> "Schedule not found."
            result.wasCancelled -> "Cancelled schedule '${result.job.label}'."
            else -> "Schedule '${result.job.label}' is already inactive."
        }
        return PrivateToolResult(
            isError = false,
            content = textContent(text),
            structuredContent = buildJsonObject {
                put("action", "cancel")
                put("was_cancelled", result.wasCancelled)
                put("already_inactive", result.alreadyInactive)
                result.job?.let { job ->
                    put("job", job.toStructuredJson())
                }
            },
        )
    }

    private fun handleCurrentTime(): PrivateToolResult {
        val currentTime = schedulerService.currentTime()
        return PrivateToolResult(
            isError = false,
            content = textContent(
                "Current local time is ${currentTime.localTime} in ${currentTime.timezone} (UTC: ${currentTime.utcTime}).",
            ),
            structuredContent = buildJsonObject {
                put("action", "current_time")
                put(
                    "current_time",
                    buildJsonObject {
                        put("local_time", currentTime.localTime)
                        put("timezone", currentTime.timezone)
                        put("utc_time", currentTime.utcTime)
                        put("unix_epoch_seconds", currentTime.unixEpochSeconds)
                    },
                )
            },
        )
    }

    private fun requireStringArgument(arguments: JsonObject, name: String): String {
        return optionalStringArgument(arguments, name)
            ?: throw IllegalArgumentException("Argument '$name' must be a non-blank string.")
    }

    private fun optionalStringArgument(arguments: JsonObject, name: String): String? {
        val value = arguments[name]?.jsonPrimitive?.contentOrNull ?: return null
        return value.trim().takeIf { normalized -> normalized.isNotEmpty() }
    }

    private fun optionalIntArgument(arguments: JsonObject, name: String): Int? {
        val value = arguments[name]?.jsonPrimitive?.contentOrNull?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        return value.toIntOrNull() ?: throw IllegalArgumentException("Argument '$name' must be an integer.")
    }

    private fun requirePositiveIntArgument(arguments: JsonObject, name: String): Int {
        val value = optionalIntArgument(arguments, name)
            ?: throw IllegalArgumentException("Argument '$name' must be a positive integer.")
        if (value <= 0) {
            throw IllegalArgumentException("Argument '$name' must be a positive integer.")
        }
        return value
    }

    private fun requireDelayUnitArgument(arguments: JsonObject, name: String): String {
        val normalized = requireStringArgument(arguments, name).lowercase()
        return when (normalized) {
            "minute",
            "minutes",
            "hour",
            "hours",
            -> normalized
            else -> throw IllegalArgumentException("Argument '$name' must be one of: minute, minutes, hour, hours.")
        }
    }

    private fun optionalTimestampArgument(arguments: JsonObject, name: String): Instant? {
        val value = optionalStringArgument(arguments, name) ?: return null
        return runCatching { Instant.parse(value) }.getOrElse {
            throw IllegalArgumentException("Argument '$name' must be an RFC3339 timestamp with explicit UTC offset.")
        }
    }

    private fun textContent(text: String): JsonArray {
        return buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                },
            )
        }
    }
}

private fun ScheduledAgentJob.toStructuredJson(): JsonObject {
    return buildJsonObject {
        put("schedule_id", scheduleId)
        put("label", label)
        put("prompt", prompt)
        put("working_directory", workingDirectory)
        put("schedule_type", scheduleType.name.lowercase())
        runAt?.let { value -> put("run_at", value.toString()) }
        startsAt?.let { value -> put("starts_at", value.toString()) }
        intervalMinutes?.let { value -> put("interval_minutes", value) }
        put("created_at", createdAt.toString())
        nextRunAt?.let { value -> put("next_run_at", value.toString()) }
        lastRunAt?.let { value -> put("last_run_at", value.toString()) }
        lastRunStatus?.let { value -> put("last_run_status", value.name.lowercase()) }
        put("status", status.name.lowercase())
        put("launchd_label", launchdLabel)
        put("plist_path", plistPath)
        put("log_path", logPath)
        lastErrorMessage?.let { value -> put("last_error_message", value) }
    }
}
