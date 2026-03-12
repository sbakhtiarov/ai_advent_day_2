@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.aichallenge.day2.agent.data.local

import com.aichallenge.day2.agent.domain.model.ScheduledAgentJob
import com.aichallenge.day2.agent.domain.model.ScheduledJobRunStatus
import com.aichallenge.day2.agent.domain.model.ScheduledJobScheduleType
import com.aichallenge.day2.agent.domain.model.ScheduledJobStatus
import com.aichallenge.day2.agent.domain.repository.ScheduledJobStore
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.posix.EEXIST
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.mode_t

class JsonFileScheduledJobStore(
    private val filePath: String,
    private val json: Json = defaultJson(),
) : ScheduledJobStore {
    override fun load(): List<ScheduledAgentJob> {
        val fileContents = readTextFile(filePath) ?: return emptyList()
        val snapshot = runCatching {
            json.decodeFromString<ScheduledJobsSnapshotDto>(fileContents)
        }.getOrNull() ?: return emptyList()
        if (snapshot.version != SNAPSHOT_VERSION) {
            return emptyList()
        }

        return snapshot.jobs.mapNotNull { job -> job.toDomainModelOrNull() }
    }

    override fun save(jobs: List<ScheduledAgentJob>) {
        val payload = json.encodeToString(
            ScheduledJobsSnapshotDto(
                version = SNAPSHOT_VERSION,
                jobs = jobs.map { job -> job.toSnapshotDto() },
            ),
        )
        ensureParentDirectoryExists(filePath)
        writeTextFile(filePath, payload)
    }

    private fun ensureParentDirectoryExists(path: String) {
        val parent = parentDirectory(path) ?: return
        ensureDirectoryExists(parent)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun ensureDirectoryExists(path: String) {
        if (path.isBlank() || path == "/") return

        val parent = parentDirectory(path)
        if (parent != null && parent != path) {
            ensureDirectoryExists(parent)
        }

        val createResult = mkdir(path, DIRECTORY_MODE.convert<mode_t>())
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

    companion object {
        private const val SNAPSHOT_VERSION = 1
        private const val DIRECTORY_MODE = 493 // 0755
        private const val READ_BUFFER_SIZE = 4096
        private const val DEFAULT_DIRECTORY_NAME = ".kotlin-agent-cli"
        private const val DEFAULT_FILE_NAME = "scheduled-jobs.json"

        fun fromDefaultLocation(json: Json = defaultJson()): JsonFileScheduledJobStore? {
            val homeDirectory = readHomeDirectory() ?: return null
            val normalizedHome = homeDirectory.trimEnd('/')
            return JsonFileScheduledJobStore(
                filePath = "$normalizedHome/$DEFAULT_DIRECTORY_NAME/$DEFAULT_FILE_NAME",
                json = json,
            )
        }

        private fun defaultJson(): Json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
            allowTrailingComma = true
        }

        @OptIn(ExperimentalForeignApi::class)
        private fun readHomeDirectory(): String? = getenv("HOME")?.toKString()?.takeIf { it.isNotBlank() }

        @OptIn(ExperimentalForeignApi::class)
        private fun readTextFile(path: String): String? {
            val file = fopen(path, "r") ?: return null
            return try {
                buildString {
                    memScoped {
                        val buffer = allocArray<ByteVar>(READ_BUFFER_SIZE)
                        while (fgets(buffer, READ_BUFFER_SIZE, file) != null) {
                            append(buffer.toKString())
                        }
                    }
                }
            } finally {
                fclose(file)
            }
        }

        @OptIn(ExperimentalForeignApi::class)
        private fun writeTextFile(path: String, text: String) {
            val file = fopen(path, "w")
                ?: throw IllegalStateException("Unable to open '$path' for writing.")

            try {
                if (fputs(text, file) < 0) {
                    throw IllegalStateException("Unable to write scheduled jobs file '$path'.")
                }
            } finally {
                fclose(file)
            }
        }
    }
}

@Serializable
private data class ScheduledJobsSnapshotDto(
    val version: Int = 1,
    val jobs: List<ScheduledJobSnapshotDto> = emptyList(),
)

@Serializable
private data class ScheduledJobSnapshotDto(
    @SerialName("schedule_id")
    val scheduleId: String = "",
    val label: String = "",
    val prompt: String = "",
    @SerialName("working_directory")
    val workingDirectory: String = "",
    @SerialName("schedule_type")
    val scheduleType: String = "",
    @SerialName("run_at")
    val runAt: String? = null,
    @SerialName("starts_at")
    val startsAt: String? = null,
    @SerialName("interval_minutes")
    val intervalMinutes: Int? = null,
    @SerialName("created_at")
    val createdAt: String = "",
    @SerialName("next_run_at")
    val nextRunAt: String? = null,
    @SerialName("last_run_at")
    val lastRunAt: String? = null,
    @SerialName("last_run_status")
    val lastRunStatus: String? = null,
    val status: String = "",
    @SerialName("launchd_label")
    val launchdLabel: String = "",
    @SerialName("plist_path")
    val plistPath: String = "",
    @SerialName("log_path")
    val logPath: String = "",
    @SerialName("last_error_message")
    val lastErrorMessage: String? = null,
) {
    fun toDomainModelOrNull(): ScheduledAgentJob? {
        val normalizedScheduleId = scheduleId.trim()
        val normalizedLabel = label.trim()
        val normalizedPrompt = prompt.trim()
        val normalizedWorkingDirectory = workingDirectory.trim()
        val normalizedLaunchdLabel = launchdLabel.trim()
        val normalizedPlistPath = plistPath.trim()
        val normalizedLogPath = logPath.trim()
        if (
            normalizedScheduleId.isEmpty() ||
            normalizedLabel.isEmpty() ||
            normalizedPrompt.isEmpty() ||
            normalizedWorkingDirectory.isEmpty() ||
            normalizedLaunchdLabel.isEmpty() ||
            normalizedPlistPath.isEmpty() ||
            normalizedLogPath.isEmpty()
        ) {
            return null
        }

        val scheduleType = runCatching { ScheduledJobScheduleType.valueOf(scheduleType.trim().uppercase()) }.getOrNull()
            ?: return null
        val status = runCatching { ScheduledJobStatus.valueOf(status.trim().uppercase()) }.getOrNull()
            ?: return null
        val createdAt = parseInstant(createdAt) ?: return null

        return runCatching {
            ScheduledAgentJob(
                scheduleId = normalizedScheduleId,
                label = normalizedLabel,
                prompt = normalizedPrompt,
                workingDirectory = normalizedWorkingDirectory,
                scheduleType = scheduleType,
                runAt = parseInstant(runAt),
                startsAt = parseInstant(startsAt),
                intervalMinutes = intervalMinutes,
                createdAt = createdAt,
                nextRunAt = parseInstant(nextRunAt),
                lastRunAt = parseInstant(lastRunAt),
                lastRunStatus = lastRunStatus
                    ?.trim()
                    ?.takeIf { value -> value.isNotEmpty() }
                    ?.let { value -> ScheduledJobRunStatus.valueOf(value.uppercase()) },
                status = status,
                launchdLabel = normalizedLaunchdLabel,
                plistPath = normalizedPlistPath,
                logPath = normalizedLogPath,
                lastErrorMessage = lastErrorMessage?.trim()?.takeIf { value -> value.isNotEmpty() },
            )
        }.getOrNull()
    }

    private fun parseInstant(value: String?): Instant? {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { Instant.parse(normalized) }.getOrNull()
    }
}

private fun ScheduledAgentJob.toSnapshotDto(): ScheduledJobSnapshotDto {
    return ScheduledJobSnapshotDto(
        scheduleId = scheduleId,
        label = label,
        prompt = prompt,
        workingDirectory = workingDirectory,
        scheduleType = scheduleType.name,
        runAt = runAt?.toString(),
        startsAt = startsAt?.toString(),
        intervalMinutes = intervalMinutes,
        createdAt = createdAt.toString(),
        nextRunAt = nextRunAt?.toString(),
        lastRunAt = lastRunAt?.toString(),
        lastRunStatus = lastRunStatus?.name,
        status = status.name,
        launchdLabel = launchdLabel,
        plistPath = plistPath,
        logPath = logPath,
        lastErrorMessage = lastErrorMessage,
    )
}
