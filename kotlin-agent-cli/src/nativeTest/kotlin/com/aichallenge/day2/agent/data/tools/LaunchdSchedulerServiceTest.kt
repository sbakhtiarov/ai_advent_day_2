@file:OptIn(kotlin.time.ExperimentalTime::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.core.config.AppRuntimeEnvironment
import com.aichallenge.day2.agent.domain.model.ScheduledAgentJob
import com.aichallenge.day2.agent.domain.model.ScheduledJobScheduleType
import com.aichallenge.day2.agent.domain.model.ScheduledJobStatus
import com.aichallenge.day2.agent.domain.repository.ScheduledJobStore
import kotlinx.coroutines.runBlocking
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.datetime.Instant
import platform.posix.EEXIST
import platform.posix.F_OK
import platform.posix.access
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.mkdir
import platform.posix.mode_t
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchdSchedulerServiceTest {
    @Test
    fun currentTimeUsesInjectedClockAndEffectiveTimezoneWithoutSideEffects() {
        val fixture = createSchedulerFixture(
            nowInstants = listOf(Instant.parse("2026-03-11T10:15:30Z")),
            timeZoneId = "Europe/Berlin",
        )

        val snapshot = fixture.service.currentTime()

        assertEquals("2026-03-11T11:15:30+01:00", snapshot.localTime)
        assertEquals("Europe/Berlin", snapshot.timezone)
        assertEquals("2026-03-11T10:15:30Z", snapshot.utcTime)
        assertEquals(1773224130L, snapshot.unixEpochSeconds)
        assertTrue(fixture.store.jobs.isEmpty())
        assertTrue(fixture.launchd.bootstrapPaths.isEmpty())
        assertTrue(fixture.launchd.bootoutRequests.isEmpty())
        assertTrue(fixture.notifications.deliveries.isEmpty())
        assertTrue(fixture.logs.entries.isEmpty())
    }

    @Test
    fun createOncePersistsMetadataWritesPlistAndBootstraps() {
        val fixture = createSchedulerFixture(
            nowInstants = listOf(Instant.parse("2026-03-11T09:00:00Z")),
        )

        val job = fixture.service.createJob(
            CreateScheduledJobRequest(
                prompt = "Summarize the repo",
                label = "Repo summary",
                scheduleType = ScheduledJobScheduleType.ONCE,
                runAt = Instant.parse("2026-03-11T10:05:00Z"),
            ),
        )

        assertEquals(ScheduledJobStatus.SCHEDULED, job.status)
        assertEquals(job, fixture.store.jobs.single())
        assertEquals(listOf(job.plistPath), fixture.launchd.bootstrapPaths)
        assertTrue(pathExists(job.plistPath))
        assertContains(readTextFile(job.plistPath), "--run-scheduled-job")
        assertContains(readTextFile(job.plistPath), job.scheduleId)
        assertTrue(fixture.logs.entries.any { entry -> entry.path == job.logPath })
    }

    @Test
    fun createRepeatStoresIntervalAndFirstNextRun() {
        val fixture = createSchedulerFixture(
            nowInstants = listOf(Instant.parse("2026-03-11T09:00:00Z")),
        )

        val job = fixture.service.createJob(
            CreateScheduledJobRequest(
                prompt = "Check CI",
                scheduleType = ScheduledJobScheduleType.REPEAT,
                startsAt = Instant.parse("2026-03-11T10:01:00Z"),
                intervalMinutes = 30,
            ),
        )

        assertEquals(ScheduledJobScheduleType.REPEAT, job.scheduleType)
        assertEquals(30, job.intervalMinutes)
        assertEquals(Instant.parse("2026-03-11T10:01:00Z"), job.nextRunAt)
    }

    @Test
    fun createDelayedOneShotConvertsMinutesAndHoursAndDelegatesToOneShotFlow() {
        val minuteFixture = createSchedulerFixture(
            nowInstants = listOf(Instant.parse("2026-03-11T09:00:00Z")),
        )
        val minuteJob = minuteFixture.service.createDelayedOneShotJob(
            prompt = "Notify me",
            label = "In five",
            delayAmount = 5,
            delayUnit = "minutes",
        )
        assertEquals(ScheduledJobScheduleType.ONCE, minuteJob.scheduleType)
        assertEquals(Instant.parse("2026-03-11T09:05:00Z"), minuteJob.runAt)
        assertEquals(listOf(minuteJob.plistPath), minuteFixture.launchd.bootstrapPaths)
        assertEquals(minuteJob, minuteFixture.store.jobs.single())

        val hourFixture = createSchedulerFixture(
            nowInstants = listOf(Instant.parse("2026-03-11T09:00:00Z")),
        )
        val hourJob = hourFixture.service.createDelayedOneShotJob(
            prompt = "Notify me",
            label = null,
            delayAmount = 2,
            delayUnit = "hour",
        )
        assertEquals(Instant.parse("2026-03-11T11:00:00Z"), hourJob.runAt)
    }

    @Test
    fun createDelayedOneShotRejectsInvalidAmountAndUnit() {
        val fixture = createSchedulerFixture(
            nowInstants = listOf(Instant.parse("2026-03-11T09:00:00Z")),
        )

        val invalidAmount = assertFailsWith<IllegalArgumentException> {
            fixture.service.createDelayedOneShotJob(
                prompt = "Notify me",
                label = null,
                delayAmount = 0,
                delayUnit = "minutes",
            )
        }
        assertContains(invalidAmount.message.orEmpty(), "delay_amount")

        val invalidUnit = assertFailsWith<IllegalArgumentException> {
            fixture.service.createDelayedOneShotJob(
                prompt = "Notify me",
                label = null,
                delayAmount = 5,
                delayUnit = "days",
            )
        }
        assertContains(invalidUnit.message.orEmpty(), "delay_unit")
    }

    @Test
    fun cancelMarksScheduledJobCancelledDeletesPlistAndIsIdempotent() {
        val fixture = createSchedulerFixture(
            nowInstants = listOf(
                Instant.parse("2026-03-11T09:00:00Z"),
                Instant.parse("2026-03-11T09:10:00Z"),
            ),
        )
        val job = fixture.service.createJob(
            CreateScheduledJobRequest(
                prompt = "Summarize",
                scheduleType = ScheduledJobScheduleType.ONCE,
                runAt = Instant.parse("2026-03-11T10:00:00Z"),
            ),
        )

        val cancelled = fixture.service.cancelJob(job.scheduleId)
        val repeatedCancel = fixture.service.cancelJob(job.scheduleId)

        assertTrue(cancelled.wasCancelled)
        assertEquals(ScheduledJobStatus.CANCELLED, cancelled.job?.status)
        assertFalse(pathExists(job.plistPath))
        assertTrue(fixture.launchd.bootoutRequests.any { request -> request.plistPath == job.plistPath })
        assertTrue(repeatedCancel.alreadyInactive)
        assertEquals(ScheduledJobStatus.CANCELLED, fixture.store.jobs.single().status)
    }

    @Test
    fun runScheduledJobCompletesOneShotDeletesPlistAndNotifies() = runBlocking {
        val fixture = createSchedulerFixture(
            nowInstants = listOf(
                Instant.parse("2026-03-11T09:00:00Z"),
                Instant.parse("2026-03-11T10:00:00Z"),
                Instant.parse("2026-03-11T10:00:00Z"),
                Instant.parse("2026-03-11T10:00:00Z"),
            ),
        )
        val job = fixture.service.createJob(
            CreateScheduledJobRequest(
                prompt = "Summarize",
                scheduleType = ScheduledJobScheduleType.ONCE,
                runAt = Instant.parse("2026-03-11T10:00:00Z"),
            ),
        )

        val result = fixture.service.runScheduledJob(job.scheduleId) {
            ScheduledJobRunnerResult(
                exitCode = 0,
                assistantResponse = "Current weather in Berlin is 10.8°C with light rain.",
            )
        }

        assertTrue(result.wasExecuted)
        assertEquals(0, result.exitCode)
        assertEquals(ScheduledJobStatus.COMPLETED, result.job.status)
        assertFalse(pathExists(job.plistPath))
        assertEquals(listOf(job.workingDirectory), fixture.runtime.changedDirectories)
        assertContains(fixture.notifications.deliveries.single().message, "Current weather in Berlin")
        assertEquals("Summarize", fixture.notifications.deliveries.single().title)
    }

    @Test
    fun runScheduledJobReschedulesRepeatingFailureWithoutBackfill() = runBlocking {
        val fixture = createSchedulerFixture(
            nowInstants = listOf(
                Instant.parse("2026-03-11T09:00:00Z"),
                Instant.parse("2026-03-11T10:50:00Z"),
                Instant.parse("2026-03-11T10:50:00Z"),
                Instant.parse("2026-03-11T10:50:00Z"),
            ),
        )
        val job = fixture.service.createJob(
            CreateScheduledJobRequest(
                prompt = "Check CI",
                scheduleType = ScheduledJobScheduleType.REPEAT,
                startsAt = Instant.parse("2026-03-11T10:01:00Z"),
                intervalMinutes = 15,
            ),
        )

        val result = fixture.service.runScheduledJob(job.scheduleId) {
            ScheduledJobRunnerResult(exitCode = 1)
        }

        assertEquals(1, result.exitCode)
        assertEquals(ScheduledJobStatus.SCHEDULED, result.job.status)
        assertEquals(Instant.parse("2026-03-11T11:01:00Z"), result.job.nextRunAt)
        assertEquals(2, fixture.launchd.bootstrapPaths.size)
        assertContains(fixture.notifications.deliveries.single().message, "failed with exit code 1")
    }

    @Test
    fun runScheduledJobCompletesOneShotEvenIfBootoutFails() = runBlocking {
        val fixture = createSchedulerFixture(
            nowInstants = listOf(
                Instant.parse("2026-03-11T09:00:00Z"),
                Instant.parse("2026-03-11T10:00:00Z"),
                Instant.parse("2026-03-11T10:00:00Z"),
                Instant.parse("2026-03-11T10:00:00Z"),
                Instant.parse("2026-03-11T10:00:00Z"),
            ),
            bootoutFailureMessage = "Boot-out failed: 5: Input/output error",
        )
        val job = fixture.service.createJob(
            CreateScheduledJobRequest(
                prompt = "Summarize",
                scheduleType = ScheduledJobScheduleType.ONCE,
                runAt = Instant.parse("2026-03-11T10:00:00Z"),
            ),
        )

        val result = fixture.service.runScheduledJob(job.scheduleId) {
            ScheduledJobRunnerResult(exitCode = 0, assistantResponse = "Job finished.")
        }

        assertEquals(ScheduledJobStatus.COMPLETED, result.job.status)
        assertFalse(pathExists(job.plistPath))
        assertTrue(fixture.logs.entries.any { entry -> entry.text.contains("cleanup warning") })
        assertContains(fixture.notifications.deliveries.single().message, "Job finished.")
    }

    @Test
    fun runScheduledJobCompletesOneShotEvenIfSchedulerNotificationFails() = runBlocking {
        val fixture = createSchedulerFixture(
            nowInstants = listOf(
                Instant.parse("2026-03-11T09:00:00Z"),
                Instant.parse("2026-03-11T10:00:00Z"),
                Instant.parse("2026-03-11T10:00:00Z"),
                Instant.parse("2026-03-11T10:00:00Z"),
            ),
            notificationFailureMessage = "Notification backend unavailable",
        )
        val job = fixture.service.createJob(
            CreateScheduledJobRequest(
                prompt = "Summarize",
                scheduleType = ScheduledJobScheduleType.ONCE,
                runAt = Instant.parse("2026-03-11T10:00:00Z"),
            ),
        )

        val result = fixture.service.runScheduledJob(job.scheduleId) {
            ScheduledJobRunnerResult(exitCode = 0, assistantResponse = "Job finished.")
        }

        assertEquals(ScheduledJobStatus.COMPLETED, result.job.status)
        assertFalse(pathExists(job.plistPath))
        assertTrue(fixture.notifications.deliveries.isEmpty())
        assertTrue(fixture.logs.entries.any { entry -> entry.text.contains("notification warning") })
    }

    @Test
    fun runScheduledJobSkipsSchedulerSuccessNotificationWhenAssistantAlreadySentOne() = runBlocking {
        val fixture = createSchedulerFixture(
            nowInstants = listOf(
                Instant.parse("2026-03-11T09:00:00Z"),
                Instant.parse("2026-03-11T10:00:00Z"),
                Instant.parse("2026-03-11T10:00:00Z"),
                Instant.parse("2026-03-11T10:00:00Z"),
            ),
        )
        val job = fixture.service.createJob(
            CreateScheduledJobRequest(
                prompt = "Test notification",
                scheduleType = ScheduledJobScheduleType.ONCE,
                runAt = Instant.parse("2026-03-11T10:00:00Z"),
            ),
        )

        fixture.service.runScheduledJob(job.scheduleId) {
            ScheduledJobRunnerResult(
                exitCode = 0,
                assistantResponse = "Notification sent: \"This is your test notification.\"",
            )
        }

        assertTrue(fixture.notifications.deliveries.isEmpty())
    }
}

private data class SchedulerFixture(
    val service: LaunchdSchedulerService,
    val store: RecordingScheduledJobStore,
    val launchd: RecordingLaunchdClient,
    val notifications: RecordingNotificationService,
    val runtime: FakeRuntimeEnvironment,
    val logs: RecordingSchedulerLogWriter,
)

private fun createSchedulerFixture(
    nowInstants: List<Instant>,
    timeZoneId: String = "UTC",
    bootoutFailureMessage: String? = null,
    notificationFailureMessage: String? = null,
): SchedulerFixture {
    val homeDirectory = uniqueSchedulerHomeDirectory()
    ensureDirectoryExists(homeDirectory)
    val store = RecordingScheduledJobStore()
    val launchd = RecordingLaunchdClient(bootoutFailureMessage = bootoutFailureMessage)
    val notifications = RecordingNotificationService(failureMessage = notificationFailureMessage)
    val runtime = FakeRuntimeEnvironment(
        homeDirectory = homeDirectory,
        currentWorkingDirectory = "/tmp/current-workdir",
        executablePath = "/tmp/bin/agent-cli.kexe",
        timeZoneId = timeZoneId,
    )
    val logs = RecordingSchedulerLogWriter()
    val clock = SequencedNowProvider(nowInstants)
    return SchedulerFixture(
        service = LaunchdSchedulerService(
            scheduledJobStore = store,
            launchdClient = launchd,
            notificationService = notifications,
            runtimeEnvironment = runtime,
            logWriter = logs,
            nowProvider = clock::next,
        ),
        store = store,
        launchd = launchd,
        notifications = notifications,
        runtime = runtime,
        logs = logs,
    )
}

private class RecordingScheduledJobStore : ScheduledJobStore {
    var jobs: List<ScheduledAgentJob> = emptyList()

    override fun load(): List<ScheduledAgentJob> = jobs.map { job -> job.copy() }

    override fun save(jobs: List<ScheduledAgentJob>) {
        this.jobs = jobs.map { job -> job.copy() }
    }
}

private class RecordingLaunchdClient(
    private val bootoutFailureMessage: String? = null,
) : LaunchdClient {
    val bootstrapPaths = mutableListOf<String>()
    val bootoutRequests = mutableListOf<BootoutRequest>()

    override fun bootstrap(plistPath: String) {
        bootstrapPaths += plistPath
    }

    override fun bootout(plistPath: String, label: String): Boolean {
        bootoutRequests += BootoutRequest(plistPath = plistPath, label = label)
        if (bootoutFailureMessage != null) {
            throw IllegalStateException(bootoutFailureMessage)
        }
        return true
    }
}

private data class BootoutRequest(
    val plistPath: String,
    val label: String,
)

private class RecordingNotificationService : NotificationService {
    constructor(failureMessage: String? = null) {
        this.failureMessage = failureMessage
    }

    private val failureMessage: String?
    val deliveries = mutableListOf<NotificationDelivery>()

    override fun send(message: String, title: String): NotificationDelivery {
        failureMessage?.let { errorMessage ->
            throw IllegalStateException(errorMessage)
        }
        return NotificationDelivery(title = title, message = message, backend = "fake").also { delivery ->
            deliveries += delivery
        }
    }
}

private class FakeRuntimeEnvironment(
    private val homeDirectory: String,
    private var currentWorkingDirectory: String,
    private val executablePath: String,
    private val timeZoneId: String,
) : AppRuntimeEnvironment {
    val changedDirectories = mutableListOf<String>()

    override fun homeDirectory(): String = homeDirectory

    override fun currentWorkingDirectory(): String = currentWorkingDirectory

    override fun currentExecutablePath(): String = executablePath

    override fun pathEnvironment(): String = ""

    override fun timeZoneId(): String = timeZoneId

    override fun changeWorkingDirectory(path: String) {
        changedDirectories += path
        currentWorkingDirectory = path
    }
}

private class RecordingSchedulerLogWriter : SchedulerLogWriter {
    val entries = mutableListOf<LogEntry>()

    override fun append(logPath: String, text: String) {
        entries += LogEntry(path = logPath, text = text)
    }
}

private data class LogEntry(
    val path: String,
    val text: String,
)

private class SequencedNowProvider(
    private val values: List<Instant>,
) {
    private var index = 0

    fun next(): Instant {
        require(values.isNotEmpty()) {
            "SequencedNowProvider requires at least one instant."
        }
        val boundedIndex = if (index <= values.lastIndex) index else values.lastIndex
        val value = values[boundedIndex]
        index += 1
        return value
    }
}

private fun uniqueSchedulerHomeDirectory(): String {
    val seed = Random.nextLong().toString().replace('-', '0')
    return "/tmp/kotlin-agent-cli-tests/$seed/home"
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

private fun parentDirectory(path: String): String {
    val normalized = path.trimEnd('/')
    val separatorIndex = normalized.lastIndexOf('/')
    return if (separatorIndex <= 0) "/" else normalized.substring(0, separatorIndex)
}

private fun pathExists(path: String): Boolean = access(path, F_OK.convert()) == 0

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
