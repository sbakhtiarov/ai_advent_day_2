@file:OptIn(kotlin.time.ExperimentalTime::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.core.config.AppRuntimeEnvironment
import com.aichallenge.day2.agent.core.config.AppRuntimeInfo
import com.aichallenge.day2.agent.core.config.DefaultAppRuntimeEnvironment
import com.aichallenge.day2.agent.data.local.JsonFileScheduledJobStore
import com.aichallenge.day2.agent.domain.model.ScheduledAgentJob
import com.aichallenge.day2.agent.domain.model.ScheduledJobRunStatus
import com.aichallenge.day2.agent.domain.model.ScheduledJobScheduleType
import com.aichallenge.day2.agent.domain.model.ScheduledJobStatus
import com.aichallenge.day2.agent.domain.repository.ScheduledJobStore
import kotlinx.cinterop.convert
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import platform.posix.EEXIST
import platform.posix.ENOENT
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getuid
import platform.posix.mkdir
import platform.posix.mode_t
import platform.posix.time
import platform.posix.unlink
import kotlin.math.min
import kotlin.random.Random

data class CreateScheduledJobRequest(
    val prompt: String,
    val label: String? = null,
    val scheduleType: ScheduledJobScheduleType,
    val runAt: Instant? = null,
    val startsAt: Instant? = null,
    val intervalMinutes: Int? = null,
)

data class CancelScheduledJobResult(
    val job: ScheduledAgentJob?,
    val wasCancelled: Boolean,
    val alreadyInactive: Boolean,
)

data class ScheduledJobExecutionResult(
    val job: ScheduledAgentJob,
    val exitCode: Int,
    val wasExecuted: Boolean,
    val assistantResponse: String? = null,
)

data class ScheduledJobRunnerResult(
    val exitCode: Int,
    val assistantResponse: String? = null,
)

data class CurrentUserTimeSnapshot(
    val localTime: String,
    val timezone: String,
    val utcTime: String,
    val unixEpochSeconds: Long,
)

interface LaunchdClient {
    fun bootstrap(plistPath: String)
    fun bootout(plistPath: String, label: String): Boolean
}

interface SchedulerLogWriter {
    fun append(logPath: String, text: String)
}

class FileSchedulerLogWriter : SchedulerLogWriter {
    override fun append(logPath: String, text: String) {
        ensureParentDirectoryExists(logPath)
        appendTextFile(logPath, text)
    }
}

class LaunchdCommandClient(
    private val commandExecutor: CommandExecutor,
) : LaunchdClient {
    override fun bootstrap(plistPath: String) {
        val result = commandExecutor.execute(
            command = LAUNCHCTL_COMMAND,
            args = listOf("bootstrap", userDomainTarget(), plistPath),
        )
        if (result.exitCode != 0) {
            val details = result.stderr.trim().ifEmpty { result.stdout.trim() }
            throw IllegalStateException(
                "launchctl bootstrap failed for '$plistPath' with exit code ${result.exitCode}${details.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()}",
            )
        }
    }

    override fun bootout(plistPath: String, label: String): Boolean {
        val candidates = listOf(
            listOf("bootout", userDomainTarget(), plistPath),
            listOf("bootout", "${userDomainTarget()}/$label"),
        )
        var lastFailure: String? = null
        candidates.forEach { args ->
            val result = commandExecutor.execute(
                command = LAUNCHCTL_COMMAND,
                args = args,
            )
            if (result.exitCode == 0) {
                return true
            }
            val details = result.stderr.trim().ifEmpty { result.stdout.trim() }
            if (details.contains("Could not find service", ignoreCase = true) ||
                details.contains("No such process", ignoreCase = true) ||
                details.contains("not loaded", ignoreCase = true)
            ) {
                lastFailure = details
                return@forEach
            }
            throw IllegalStateException(
                "launchctl bootout failed for '$label' with exit code ${result.exitCode}${details.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()}",
            )
        }
        return lastFailure == null || false
    }

    private fun userDomainTarget(): String = "gui/${getuid().toInt()}"

    companion object {
        private const val LAUNCHCTL_COMMAND = "/bin/launchctl"
    }
}

class LaunchdSchedulerService(
    private val scheduledJobStore: ScheduledJobStore?,
    private val launchdClient: LaunchdClient,
    private val notificationService: NotificationService,
    private val runtimeEnvironment: AppRuntimeEnvironment,
    private val logWriter: SchedulerLogWriter = FileSchedulerLogWriter(),
    private val nowProvider: () -> Instant = { currentInstant() },
) {
    fun currentTime(): CurrentUserTimeSnapshot {
        val instant = nowProvider()
        val timeZone = resolveUserTimeZone()
        return CurrentUserTimeSnapshot(
            localTime = formatInstantInTimeZone(instant, timeZone),
            timezone = timeZone.id,
            utcTime = instant.toString(),
            unixEpochSeconds = instant.epochSeconds,
        )
    }

    fun createJob(request: CreateScheduledJobRequest): ScheduledAgentJob {
        val store = requireScheduledJobStore()
        val now = nowProvider()
        val prompt = request.prompt.trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("prompt must be a non-blank string.")
        val workingDirectory = runtimeEnvironment.currentWorkingDirectory()?.trim().takeIf { !it.isNullOrEmpty() }
            ?: throw IllegalStateException("Unable to determine current working directory for scheduler job creation.")
        val executablePath = runtimeEnvironment.currentExecutablePath()?.trim().takeIf { !it.isNullOrEmpty() }
            ?: throw IllegalStateException("Unable to determine current executable path for scheduler job creation.")
        val homeDirectory = runtimeEnvironment.homeDirectory()?.trim().takeIf { !it.isNullOrEmpty() }
            ?: throw IllegalStateException("HOME is not set; scheduler storage cannot be created.")
        val label = normalizeLabel(request.label, prompt)
        val scheduleId = generateScheduleId()
        val launchdLabel = "$SCHEDULER_LABEL_PREFIX.$scheduleId"
        val plistPath = "${homeDirectory.trimEnd('/')}/Library/LaunchAgents/$launchdLabel.plist"
        val logPath = "${homeDirectory.trimEnd('/')}/.kotlin-agent-cli/scheduler-logs/$scheduleId.log"

        val normalizedRunAt = request.runAt?.let { normalizeToFutureMinute(it, now) }
        val normalizedStartsAt = request.startsAt?.let { normalizeToFutureMinute(it, now) }
        if (request.runAt != null && request.runAt <= now) {
            throw IllegalArgumentException("run_at must be in the future.")
        }
        if (request.startsAt != null && request.startsAt <= now) {
            throw IllegalArgumentException("starts_at must be in the future.")
        }

        val nextRunAt = when (request.scheduleType) {
            ScheduledJobScheduleType.ONCE -> normalizedRunAt
                ?: throw IllegalArgumentException("run_at is required for one-shot schedules.")

            ScheduledJobScheduleType.REPEAT -> {
                val startsAt = normalizedStartsAt
                    ?: throw IllegalArgumentException("starts_at is required for repeating schedules.")
                val intervalMinutes = request.intervalMinutes
                    ?: throw IllegalArgumentException("interval_minutes is required for repeating schedules.")
                if (intervalMinutes <= 0) {
                    throw IllegalArgumentException("interval_minutes must be positive.")
                }
                startsAt
            }
        }

        if (request.scheduleType == ScheduledJobScheduleType.ONCE && (request.startsAt != null || request.intervalMinutes != null)) {
            throw IllegalArgumentException("One-shot schedules accept only run_at.")
        }
        if (request.scheduleType == ScheduledJobScheduleType.REPEAT && request.runAt != null) {
            throw IllegalArgumentException("Repeating schedules accept starts_at and interval_minutes, not run_at.")
        }

        val job = ScheduledAgentJob(
            scheduleId = scheduleId,
            label = label,
            prompt = prompt,
            workingDirectory = workingDirectory,
            scheduleType = request.scheduleType,
            runAt = normalizedRunAt,
            startsAt = normalizedStartsAt,
            intervalMinutes = request.intervalMinutes,
            createdAt = now,
            nextRunAt = nextRunAt,
            status = ScheduledJobStatus.SCHEDULED,
            launchdLabel = launchdLabel,
            plistPath = plistPath,
            logPath = logPath,
        )

        writeSchedulerPlist(
            job = job,
            executablePath = executablePath,
            nextRunAt = nextRunAt,
        )
        try {
            launchdClient.bootstrap(plistPath)
        } catch (throwable: Throwable) {
            deleteFileIfExists(plistPath)
            throw throwable
        }

        val updatedJobs = store.load().filterNot { existing -> existing.scheduleId == scheduleId } + job
        store.save(updatedJobs.sortedByCreatedAt())
        logWriter.append(
            job.logPath,
            "[${now}] scheduled '${job.label}' for ${job.nextRunAt}\n",
        )
        return job
    }

    fun listJobs(): List<ScheduledAgentJob> {
        return requireScheduledJobStore()
            .load()
            .sortedWith(
                compareBy<ScheduledAgentJob> { job -> statusSortRank(job.status) }
                    .thenBy { job -> job.nextRunAt?.toEpochMilliseconds() ?: Long.MAX_VALUE }
                    .thenByDescending { job -> job.createdAt.toEpochMilliseconds() },
            )
    }

    fun cancelJob(scheduleId: String): CancelScheduledJobResult {
        val normalizedId = scheduleId.trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("schedule_id must be a non-blank string.")
        val store = requireScheduledJobStore()
        val jobs = store.load()
        val job = jobs.firstOrNull { existing -> existing.scheduleId == normalizedId }
            ?: return CancelScheduledJobResult(job = null, wasCancelled = false, alreadyInactive = true)

        if (job.status == ScheduledJobStatus.CANCELLED) {
            return CancelScheduledJobResult(job = job, wasCancelled = false, alreadyInactive = true)
        }
        if (job.status == ScheduledJobStatus.COMPLETED) {
            return CancelScheduledJobResult(job = job, wasCancelled = false, alreadyInactive = true)
        }

        launchdClient.bootout(job.plistPath, job.launchdLabel)
        deleteFileIfExists(job.plistPath)
        val updated = job.copy(
            status = ScheduledJobStatus.CANCELLED,
            nextRunAt = null,
            lastErrorMessage = null,
        )
        store.save(jobs.replace(updated).sortedByCreatedAt())
        logWriter.append(
            job.logPath,
            "[${nowProvider()}] cancelled '${job.label}'\n",
        )
        return CancelScheduledJobResult(job = updated, wasCancelled = true, alreadyInactive = false)
    }

    suspend fun runScheduledJob(
        scheduleId: String,
        runner: suspend (ScheduledAgentJob) -> ScheduledJobRunnerResult,
    ): ScheduledJobExecutionResult {
        val normalizedId = scheduleId.trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("schedule_id must be a non-blank string.")
        val store = requireScheduledJobStore()
        val jobs = store.load()
        val job = jobs.firstOrNull { existing -> existing.scheduleId == normalizedId }
            ?: throw IllegalStateException("Scheduled job '$normalizedId' was not found.")
        if (job.status != ScheduledJobStatus.SCHEDULED) {
            logWriter.append(
                job.logPath,
                "[${nowProvider()}] skipped scheduled run for '${job.label}' because status is ${job.status.name.lowercase()}\n",
            )
            return ScheduledJobExecutionResult(
                job = job,
                exitCode = 0,
                wasExecuted = false,
            )
        }

        val startedAt = nowProvider()
        logWriter.append(
            job.logPath,
            "[${startedAt}] starting scheduled run for '${job.label}'\n",
        )

        val runnerResult = runCatching {
            runtimeEnvironment.changeWorkingDirectory(job.workingDirectory)
            runner(job)
        }.getOrElse { throwable ->
            logWriter.append(
                job.logPath,
                "[${nowProvider()}] scheduler runtime failure: ${throwable.message ?: "Unexpected error"}\n",
            )
            ScheduledJobRunnerResult(exitCode = 1)
        }
        val exitCode = runnerResult.exitCode

        val completedAt = nowProvider()
        val lastRunStatus = if (exitCode == 0) {
            ScheduledJobRunStatus.SUCCESS
        } else {
            ScheduledJobRunStatus.FAILURE
        }

        val updated = when (job.scheduleType) {
            ScheduledJobScheduleType.ONCE -> {
                job.copy(
                    nextRunAt = null,
                    lastRunAt = completedAt,
                    lastRunStatus = lastRunStatus,
                    status = ScheduledJobStatus.COMPLETED,
                    lastErrorMessage = if (lastRunStatus == ScheduledJobRunStatus.FAILURE) {
                        "Scheduled run failed with exit code $exitCode."
                    } else {
                        null
                    },
                )
            }

            ScheduledJobScheduleType.REPEAT -> {
                val nextRunAt = computeNextFutureRun(
                    startsAt = job.startsAt ?: error("startsAt is required for repeating schedules."),
                    intervalMinutes = job.intervalMinutes ?: error("intervalMinutes is required for repeating schedules."),
                    now = completedAt,
                )
                val rescheduled = job.copy(
                    nextRunAt = nextRunAt,
                    lastRunAt = completedAt,
                    lastRunStatus = lastRunStatus,
                    status = ScheduledJobStatus.SCHEDULED,
                    lastErrorMessage = if (lastRunStatus == ScheduledJobRunStatus.FAILURE) {
                        "Scheduled run failed with exit code $exitCode."
                    } else {
                        null
                    },
                )
                rescheduleJob(rescheduled)
                rescheduled
            }
        }

        store.save(jobs.replace(updated).sortedByCreatedAt())
        logWriter.append(
            updated.logPath,
            "[${completedAt}] finished scheduled run for '${updated.label}' with status ${lastRunStatus.name.lowercase()} (exit_code=$exitCode)\n",
        )
        sendRunNotificationSafely(
            job = updated,
            exitCode = exitCode,
            assistantResponse = runnerResult.assistantResponse,
        )
        if (updated.scheduleType == ScheduledJobScheduleType.ONCE) {
            cleanupCompletedOneShot(updated)
        }
        return ScheduledJobExecutionResult(
            job = updated,
            exitCode = exitCode,
            wasExecuted = true,
            assistantResponse = runnerResult.assistantResponse,
        )
    }

    private fun cleanupCompletedOneShot(job: ScheduledAgentJob) {
        runCatching {
            deleteFileIfExists(job.plistPath)
        }.onFailure { throwable ->
            logWriter.append(
                job.logPath,
                "[${nowProvider()}] cleanup warning for '${job.label}': ${throwable.message ?: "Unexpected error"}\n",
            )
        }
        runCatching {
            launchdClient.bootout(job.plistPath, job.launchdLabel)
        }.onFailure { throwable ->
            logWriter.append(
                job.logPath,
                "[${nowProvider()}] cleanup warning for '${job.label}': ${throwable.message ?: "Unexpected error"}\n",
            )
        }
    }

    private fun rescheduleJob(job: ScheduledAgentJob) {
        val executablePath = runtimeEnvironment.currentExecutablePath()?.trim().takeIf { !it.isNullOrEmpty() }
            ?: throw IllegalStateException("Unable to determine current executable path for scheduler reschedule.")
        launchdClient.bootout(job.plistPath, job.launchdLabel)
        writeSchedulerPlist(job, executablePath, job.nextRunAt ?: error("nextRunAt is required for rescheduling."))
        try {
            launchdClient.bootstrap(job.plistPath)
        } catch (throwable: Throwable) {
            throw IllegalStateException("Failed to reschedule repeating job '${job.label}': ${throwable.message}", throwable)
        }
    }

    private fun sendRunNotificationSafely(job: ScheduledAgentJob, exitCode: Int, assistantResponse: String?) {
        runCatching {
            sendRunNotification(
                job = job,
                exitCode = exitCode,
                assistantResponse = assistantResponse,
            )
        }.onFailure { throwable ->
            logWriter.append(
                job.logPath,
                "[${nowProvider()}] notification warning for '${job.label}': ${throwable.message ?: "Unexpected error"}\n",
            )
        }
    }

    private fun sendRunNotification(job: ScheduledAgentJob, exitCode: Int, assistantResponse: String?) {
        val responsePreview = assistantResponse
            ?.let(::normalizeNotificationPreview)
            ?.takeIf { preview -> preview.isNotEmpty() }
        if (job.lastRunStatus == ScheduledJobRunStatus.SUCCESS &&
            responsePreview != null &&
            isNotificationConfirmation(responsePreview)
        ) {
            return
        }
        val message = when (job.lastRunStatus) {
            ScheduledJobRunStatus.SUCCESS -> {
                if (responsePreview != null) {
                    responsePreview
                } else {
                    val suffix = job.nextRunAt?.let { value -> " Next run: $value." }.orEmpty()
                    "${job.label} completed successfully.$suffix"
                }
            }

            ScheduledJobRunStatus.FAILURE -> {
                val suffix = job.nextRunAt?.let { value -> " Next run: $value." }.orEmpty()
                "${job.label} failed with exit code $exitCode.$suffix"
            }

            null -> return
        }
        val title = if (job.lastRunStatus == ScheduledJobRunStatus.SUCCESS && responsePreview != null) {
            job.label
        } else {
            "$AppRuntimeInfo.APP_NAME Scheduler"
        }
        notificationService.send(
            message = message,
            title = title,
        )
    }

    private fun writeSchedulerPlist(
        job: ScheduledAgentJob,
        executablePath: String,
        nextRunAt: Instant,
    ) {
        ensureParentDirectoryExists(job.plistPath)
        ensureParentDirectoryExists(job.logPath)
        val localDateTime = nextRunAt.toLocalDateTime(resolveUserTimeZone())
        val plist = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">""")
            appendLine("""<plist version="1.0">""")
            appendLine("<dict>")
            appendLine("  <key>Label</key>")
            appendLine("  <string>${escapeXml(job.launchdLabel)}</string>")
            appendLine("  <key>ProgramArguments</key>")
            appendLine("  <array>")
            appendLine("    <string>${escapeXml(executablePath)}</string>")
            appendLine("    <string>--run-scheduled-job</string>")
            appendLine("    <string>${escapeXml(job.scheduleId)}</string>")
            appendLine("  </array>")
            appendLine("  <key>WorkingDirectory</key>")
            appendLine("  <string>${escapeXml(job.workingDirectory)}</string>")
            appendLine("  <key>StandardOutPath</key>")
            appendLine("  <string>${escapeXml(job.logPath)}</string>")
            appendLine("  <key>StandardErrorPath</key>")
            appendLine("  <string>${escapeXml(job.logPath)}</string>")
            appendLine("  <key>StartCalendarInterval</key>")
            appendLine("  <dict>")
            appendLine("    <key>Year</key>")
            appendLine("    <integer>${localDateTime.year}</integer>")
            appendLine("    <key>Month</key>")
            appendLine("    <integer>${localDateTime.monthNumber}</integer>")
            appendLine("    <key>Day</key>")
            appendLine("    <integer>${localDateTime.dayOfMonth}</integer>")
            appendLine("    <key>Hour</key>")
            appendLine("    <integer>${localDateTime.hour}</integer>")
            appendLine("    <key>Minute</key>")
            appendLine("    <integer>${localDateTime.minute}</integer>")
            appendLine("  </dict>")
            appendLine("</dict>")
            appendLine("</plist>")
        }
        writeTextFile(job.plistPath, plist)
    }

    private fun requireScheduledJobStore(): ScheduledJobStore {
        return scheduledJobStore ?: throw IllegalStateException("Scheduler storage is unavailable in this environment.")
    }

    private fun normalizeLabel(rawLabel: String?, prompt: String): String {
        val candidate = rawLabel?.trim().takeIf { !it.isNullOrEmpty() }
            ?: prompt.replace(Regex("\\s+"), " ").trim()
        return candidate.take(min(candidate.length, DEFAULT_LABEL_MAX_LENGTH))
            .trim()
            .ifEmpty { "Scheduled prompt" }
    }

    private fun generateScheduleId(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return buildString {
            repeat(SCHEDULE_ID_LENGTH) {
                append(alphabet[Random.nextInt(alphabet.length)])
            }
        }
    }

    private fun normalizeToFutureMinute(value: Instant, now: Instant): Instant {
        if (value <= now) {
            return value
        }
        val epochMillis = value.toEpochMilliseconds()
        val roundedEpochMillis = if (epochMillis % ONE_MINUTE_MS == 0L) {
            epochMillis
        } else {
            ((epochMillis / ONE_MINUTE_MS) + 1L) * ONE_MINUTE_MS
        }
        return Instant.fromEpochMilliseconds(roundedEpochMillis)
    }

    private fun computeNextFutureRun(
        startsAt: Instant,
        intervalMinutes: Int,
        now: Instant,
    ): Instant {
        if (startsAt > now) {
            return startsAt
        }
        val intervalMillis = intervalMinutes.toLong() * ONE_MINUTE_MS
        val deltaMillis = now.toEpochMilliseconds() - startsAt.toEpochMilliseconds()
        val completedIntervals = deltaMillis / intervalMillis
        return Instant.fromEpochMilliseconds(
            startsAt.toEpochMilliseconds() + ((completedIntervals + 1L) * intervalMillis),
        )
    }

    private fun statusSortRank(status: ScheduledJobStatus): Int {
        return when (status) {
            ScheduledJobStatus.SCHEDULED -> 0
            ScheduledJobStatus.ERROR -> 1
            ScheduledJobStatus.COMPLETED -> 2
            ScheduledJobStatus.CANCELLED -> 3
        }
    }

    private fun resolveUserTimeZone(): TimeZone {
        val configuredTimeZoneId = runtimeEnvironment.timeZoneId()
            ?.trim()
            ?.takeIf { value -> value.isNotEmpty() }
        if (configuredTimeZoneId != null) {
            runCatching { TimeZone.of(configuredTimeZoneId) }
                .getOrNull()
                ?.let { timeZone -> return timeZone }
        }
        return runCatching { TimeZone.currentSystemDefault() }.getOrDefault(TimeZone.UTC)
    }

    companion object {
        private const val SCHEDULER_LABEL_PREFIX = "com.aichallenge.day2.agent.scheduler"
        private const val SCHEDULE_ID_LENGTH = 12
        private const val DEFAULT_LABEL_MAX_LENGTH = 64
        private const val ONE_MINUTE_MS = 60_000L

        fun createDefault(
            commandExecutor: CommandExecutor = PosixCommandExecutor(),
            notificationService: NotificationService = MacOsNotificationService(commandExecutor),
            runtimeEnvironment: AppRuntimeEnvironment = DefaultAppRuntimeEnvironment(),
            scheduledJobStore: ScheduledJobStore? = JsonFileScheduledJobStore.fromDefaultLocation(),
            logWriter: SchedulerLogWriter = FileSchedulerLogWriter(),
            nowProvider: () -> Instant = { currentInstant() },
        ): LaunchdSchedulerService {
            return LaunchdSchedulerService(
                scheduledJobStore = scheduledJobStore,
                launchdClient = LaunchdCommandClient(commandExecutor),
                notificationService = notificationService,
                runtimeEnvironment = runtimeEnvironment,
                logWriter = logWriter,
                nowProvider = nowProvider,
            )
        }
    }
}

private fun List<ScheduledAgentJob>.replace(updated: ScheduledAgentJob): List<ScheduledAgentJob> {
    return map { job ->
        if (job.scheduleId == updated.scheduleId) {
            updated
        } else {
            job
        }
    }
}

private fun List<ScheduledAgentJob>.sortedByCreatedAt(): List<ScheduledAgentJob> {
    return sortedByDescending { job -> job.createdAt.toEpochMilliseconds() }
}

private fun escapeXml(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

private fun ensureParentDirectoryExists(path: String) {
    val parent = parentDirectory(path) ?: return
    ensureDirectoryExists(parent)
}

private fun ensureDirectoryExists(path: String) {
    if (path.isBlank() || path == "/") return

    val parent = parentDirectory(path)
    if (parent != null && parent != path) {
        ensureDirectoryExists(parent)
    }

    val createResult = mkdir(path, 493.convert<mode_t>())
    if (createResult == 0 || errno == EEXIST) {
        return
    }
    throw IllegalStateException("Unable to create directory '$path'.")
}

private fun parentDirectory(path: String): String? {
    if (path.isBlank() || path == "/") return null
    val normalized = path.trimEnd('/')
    val separatorIndex = normalized.lastIndexOf('/')
    if (separatorIndex < 0) return null
    return if (separatorIndex == 0) "/" else normalized.substring(0, separatorIndex)
}

private fun writeTextFile(path: String, text: String) {
    val file = fopen(path, "w")
        ?: throw IllegalStateException("Unable to open '$path' for writing.")
    try {
        if (fputs(text, file) < 0) {
            throw IllegalStateException("Unable to write '$path'.")
        }
    } finally {
        fclose(file)
    }
}

private fun appendTextFile(path: String, text: String) {
    val file = fopen(path, "a")
        ?: throw IllegalStateException("Unable to open '$path' for appending.")
    try {
        if (fputs(text, file) < 0) {
            throw IllegalStateException("Unable to append '$path'.")
        }
    } finally {
        fclose(file)
    }
}

private fun deleteFileIfExists(path: String) {
    val deleteResult = unlink(path)
    if (deleteResult == 0 || errno == ENOENT) {
        return
    }
    throw IllegalStateException("Unable to remove '$path'.")
}

private fun currentInstant(): Instant {
    return Instant.fromEpochSeconds(time(null).toLong())
}

private fun formatInstantInTimeZone(
    instant: Instant,
    timeZone: TimeZone,
): String {
    val offset = timeZone.offsetAt(instant).toString()
        .let { value -> if (value == "Z") "+00:00" else value }
    return "${instant.toLocalDateTime(timeZone)}$offset"
}

private fun normalizeNotificationPreview(text: String): String {
    val flattened = text.lineSequence()
        .map { line -> line.trim() }
        .filter { line -> line.isNotEmpty() }
        .joinToString(separator = " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (flattened.length <= 220) {
        return flattened
    }
    return flattened.take(217).trimEnd() + "..."
}

private fun isNotificationConfirmation(text: String): Boolean {
    val normalized = text.trim().lowercase()
    return normalized.startsWith("notification sent") ||
        normalized.startsWith("notification with") ||
        normalized.contains("notification was just shown") ||
        normalized.contains("notification has been shown")
}
