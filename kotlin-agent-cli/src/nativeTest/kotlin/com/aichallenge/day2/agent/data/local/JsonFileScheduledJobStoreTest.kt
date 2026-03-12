@file:OptIn(kotlin.time.ExperimentalTime::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package com.aichallenge.day2.agent.data.local

import com.aichallenge.day2.agent.domain.model.ScheduledAgentJob
import com.aichallenge.day2.agent.domain.model.ScheduledJobRunStatus
import com.aichallenge.day2.agent.domain.model.ScheduledJobScheduleType
import com.aichallenge.day2.agent.domain.model.ScheduledJobStatus
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.datetime.Instant
import platform.posix.EEXIST
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.mkdir
import platform.posix.mode_t
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonFileScheduledJobStoreTest {
    @Test
    fun saveAndLoadRoundTripsScheduledJobs() {
        val filePath = uniqueScheduledJobsFilePath()
        val store = JsonFileScheduledJobStore(filePath)
        val jobs = listOf(
            ScheduledAgentJob(
                scheduleId = "abc123",
                label = "Nightly summary",
                prompt = "Summarize the repo",
                workingDirectory = "/tmp/work",
                scheduleType = ScheduledJobScheduleType.ONCE,
                runAt = Instant.parse("2026-03-12T09:00:00Z"),
                createdAt = Instant.parse("2026-03-11T09:00:00Z"),
                nextRunAt = Instant.parse("2026-03-12T09:00:00Z"),
                lastRunAt = null,
                lastRunStatus = null,
                status = ScheduledJobStatus.SCHEDULED,
                launchdLabel = "com.aichallenge.day2.agent.scheduler.abc123",
                plistPath = "/tmp/LaunchAgents/abc123.plist",
                logPath = "/tmp/logs/abc123.log",
            ),
            ScheduledAgentJob(
                scheduleId = "repeat1",
                label = "Hourly check",
                prompt = "Check CI",
                workingDirectory = "/tmp/work",
                scheduleType = ScheduledJobScheduleType.REPEAT,
                startsAt = Instant.parse("2026-03-11T10:00:00Z"),
                intervalMinutes = 60,
                createdAt = Instant.parse("2026-03-11T08:00:00Z"),
                nextRunAt = Instant.parse("2026-03-11T11:00:00Z"),
                lastRunAt = Instant.parse("2026-03-11T10:00:00Z"),
                lastRunStatus = ScheduledJobRunStatus.SUCCESS,
                status = ScheduledJobStatus.SCHEDULED,
                launchdLabel = "com.aichallenge.day2.agent.scheduler.repeat1",
                plistPath = "/tmp/LaunchAgents/repeat1.plist",
                logPath = "/tmp/logs/repeat1.log",
                lastErrorMessage = null,
            ),
        )

        store.save(jobs)

        assertEquals(jobs, store.load())
    }

    @Test
    fun loadReturnsEmptyForMalformedOrInvalidSnapshots() {
        val filePath = uniqueScheduledJobsFilePath()
        ensureDirectoryExists(parentDirectory(filePath))
        writeTextFile(filePath, """{"version":1,"jobs":[{"schedule_id":"bad"}]}""")

        assertEquals(emptyList(), JsonFileScheduledJobStore(filePath).load())

        writeTextFile(filePath, "{ malformed json")
        assertEquals(emptyList(), JsonFileScheduledJobStore(filePath).load())
    }
}

private fun uniqueScheduledJobsFilePath(): String {
    val seed = Random.nextLong().toString().replace('-', '0')
    return "/tmp/kotlin-agent-cli-tests/$seed/scheduled-jobs.json"
}

private fun parentDirectory(path: String): String {
    val normalized = path.trimEnd('/')
    val separatorIndex = normalized.lastIndexOf('/')
    return if (separatorIndex <= 0) "/" else normalized.substring(0, separatorIndex)
}

private fun ensureDirectoryExists(path: String) {
    if (path.isBlank() || path == "/") return

    val parent = parentDirectory(path)
    if (parent != path) {
        ensureDirectoryExists(parent)
    }

    val result = mkdir(path, 493.convert<mode_t>())
    if (result == 0 || errno == EEXIST) return
    error("Failed to create test directory '$path'.")
}

private fun writeTextFile(path: String, text: String) {
    ensureDirectoryExists(parentDirectory(path))
    val file = fopen(path, "w") ?: error("Unable to open test file '$path'.")
    try {
        if (fputs(text, file) < 0) {
            error("Unable to write test file '$path'.")
        }
    } finally {
        fclose(file)
    }
}

private fun readTextFile(path: String): String {
    val file = fopen(path, "r") ?: error("Unable to open test file '$path'.")
    return try {
        buildString {
            val bufferSize = 4096
            memScoped {
                val buffer = allocArray<ByteVar>(bufferSize)
                while (fgets(buffer, bufferSize, file) != null) {
                    append(buffer.toKString())
                }
            }
        }
    } finally {
        fclose(file)
    }
}
