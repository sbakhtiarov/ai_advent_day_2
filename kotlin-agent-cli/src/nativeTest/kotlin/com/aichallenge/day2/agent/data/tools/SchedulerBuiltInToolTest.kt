@file:OptIn(kotlin.time.ExperimentalTime::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.core.config.AppRuntimeEnvironment
import com.aichallenge.day2.agent.domain.model.ScheduledAgentJob
import com.aichallenge.day2.agent.domain.repository.ScheduledJobStore
import kotlinx.coroutines.runBlocking
import kotlinx.cinterop.convert
import kotlinx.datetime.Instant
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.posix.EEXIST
import platform.posix.errno
import platform.posix.mkdir
import platform.posix.mode_t
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SchedulerBuiltInToolTest {
    @Test
    fun schedulerSchemaIncludesCurrentTimeAndDelayActions() {
        val fixture = createToolFixture(
            nowInstants = listOf(Instant.parse("2026-03-11T09:00:00Z")),
        )

        val registration = schedulerToolRegistration(fixture.service)
        assertContains(registration.definition.description, "current local time")
        assertContains(registration.definition.description, "current_time")
        assertContains(registration.definition.description, "delay")
        assertContains(registration.definition.description, "in 5 minutes")
        assertContains(registration.definition.description, "at 07:55")
        assertContains(registration.definition.description, "Do not ask the user for timezone")
        val enumValues = registration.definition.parametersSchema["properties"]
            ?.jsonObject
            ?.get("action")
            ?.jsonObject
            ?.get("enum")
            ?.jsonArray
            ?.map { value -> value.jsonPrimitive.content }
        val actionDescription = registration.definition.parametersSchema["properties"]
            ?.jsonObject
            ?.get("action")
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            .orEmpty()
        val delayUnitValues = registration.definition.parametersSchema["properties"]
            ?.jsonObject
            ?.get("delay_unit")
            ?.jsonObject
            ?.get("enum")
            ?.jsonArray
            ?.map { value -> value.jsonPrimitive.content }
        val delayAmountDescription = registration.definition.parametersSchema["properties"]
            ?.jsonObject
            ?.get("delay_amount")
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            .orEmpty()

        assertEquals(listOf("create", "delay", "list", "cancel", "current_time"), enumValues)
        assertEquals(listOf("minute", "minutes", "hour", "hours"), delayUnitValues)
        assertContains(actionDescription, "current time")
        assertContains(actionDescription, "current_time")
        assertContains(actionDescription, "delay")
        assertContains(actionDescription, "at 07:55")
        assertContains(delayAmountDescription, "Positive integer")
    }

    @Test
    fun createActionCreatesOneShotJobAndReturnsStructuredContent() = runBlocking {
        val fixture = createToolFixture(
            nowInstants = listOf(Instant.parse("2026-03-11T09:00:00Z")),
        )
        val executor = SchedulerBuiltInToolExecutor(fixture.service)

        val result = executor.execute(
            buildJsonObject {
                put("action", "create")
                put("prompt", "Summarize the repo")
                put("label", "Repo summary")
                put("schedule_type", "once")
                put("run_at", "2026-03-11T10:00:00Z")
            },
        )

        assertContains(result.content.single().jsonObject["text"]?.jsonPrimitive?.content.orEmpty(), "Scheduled")
        val job = result.structuredContent?.get("job")?.jsonObject ?: error("Missing job payload")
        assertEquals("Repo summary", job["label"]?.jsonPrimitive?.content)
        assertEquals("scheduled", job["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun createActionRejectsPastTimestamp() = runBlocking {
        val fixture = createToolFixture(
            nowInstants = listOf(Instant.parse("2026-03-11T09:00:00Z")),
        )
        val executor = SchedulerBuiltInToolExecutor(fixture.service)

        val error = assertFailsWith<IllegalArgumentException> {
            executor.execute(
                buildJsonObject {
                    put("action", "create")
                    put("prompt", "Summarize the repo")
                    put("schedule_type", "once")
                    put("run_at", "2026-03-11T08:59:00Z")
                },
            )
        }

        assertContains(error.message.orEmpty(), "future")
    }

    @Test
    fun createActionRejectsMixedFieldsAndNonPositiveInterval() = runBlocking {
        val fixture = createToolFixture(
            nowInstants = listOf(Instant.parse("2026-03-11T09:00:00Z")),
        )
        val executor = SchedulerBuiltInToolExecutor(fixture.service)

        val mixedFields = assertFailsWith<IllegalArgumentException> {
            executor.execute(
                buildJsonObject {
                    put("action", "create")
                    put("prompt", "Summarize the repo")
                    put("schedule_type", "once")
                    put("run_at", "2026-03-11T10:00:00Z")
                    put("starts_at", "2026-03-11T10:00:00Z")
                    put("interval_minutes", 15)
                },
            )
        }
        assertContains(mixedFields.message.orEmpty(), "One-shot")

        val invalidInterval = assertFailsWith<IllegalArgumentException> {
            executor.execute(
                buildJsonObject {
                    put("action", "create")
                    put("prompt", "Summarize the repo")
                    put("schedule_type", "repeat")
                    put("starts_at", "2026-03-11T10:00:00Z")
                    put("interval_minutes", 0)
                },
            )
        }
        assertContains(invalidInterval.message.orEmpty(), "positive")
    }

    @Test
    fun createActionIgnoresPlaceholderRepeatFieldsForOneShotSchedules() = runBlocking {
        val fixture = createToolFixture(
            nowInstants = listOf(Instant.parse("2026-03-11T09:00:00Z")),
        )
        val executor = SchedulerBuiltInToolExecutor(fixture.service)

        val result = executor.execute(
            buildJsonObject {
                put("action", "create")
                put("prompt", "Summarize the repo")
                put("label", "Repo summary")
                put("schedule_type", "once")
                put("run_at", "2026-03-11T10:00:00Z")
                put("starts_at", "")
                put("interval_minutes", 0)
                put("schedule_id", "")
            },
        )

        assertContains(result.content.single().jsonObject["text"]?.jsonPrimitive?.content.orEmpty(), "Scheduled")
        val job = result.structuredContent?.get("job")?.jsonObject ?: error("Missing job payload")
        assertEquals("once", job["schedule_type"]?.jsonPrimitive?.content)
        assertEquals("2026-03-11T10:00:00Z", job["run_at"]?.jsonPrimitive?.content)
    }

    @Test
    fun delayActionCreatesOneShotJobForMinuteDelayAndReturnsStructuredContent() = runBlocking {
        val fixture = createToolFixture(
            nowInstants = listOf(Instant.parse("2026-03-11T09:00:00Z")),
        )
        val executor = SchedulerBuiltInToolExecutor(fixture.service)

        val result = executor.execute(
            buildJsonObject {
                put("action", "delay")
                put("prompt", "Send me a reminder")
                put("label", "Reminder")
                put("delay_amount", 5)
                put("delay_unit", "minutes")
            },
        )

        assertContains(result.content.single().jsonObject["text"]?.jsonPrimitive?.content.orEmpty(), "Scheduled")
        assertEquals("delay", result.structuredContent?.get("action")?.jsonPrimitive?.content)
        val job = result.structuredContent?.get("job")?.jsonObject ?: error("Missing job payload")
        assertEquals("once", job["schedule_type"]?.jsonPrimitive?.content)
        assertEquals("2026-03-11T09:05:00Z", job["run_at"]?.jsonPrimitive?.content)
    }

    @Test
    fun delayActionCreatesOneShotJobForHourDelay() = runBlocking {
        val fixture = createToolFixture(
            nowInstants = listOf(Instant.parse("2026-03-11T09:00:00Z")),
        )
        val executor = SchedulerBuiltInToolExecutor(fixture.service)

        val result = executor.execute(
            buildJsonObject {
                put("action", "delay")
                put("prompt", "Send me a reminder")
                put("delay_amount", 2)
                put("delay_unit", "hour")
            },
        )

        val job = result.structuredContent?.get("job")?.jsonObject ?: error("Missing job payload")
        assertEquals("2026-03-11T11:00:00Z", job["run_at"]?.jsonPrimitive?.content)
    }

    @Test
    fun delayActionAllowsScheduleTypeOncePlaceholder() = runBlocking {
        val fixture = createToolFixture(
            nowInstants = listOf(Instant.parse("2026-03-11T09:00:00Z")),
        )
        val executor = SchedulerBuiltInToolExecutor(fixture.service)

        val result = executor.execute(
            buildJsonObject {
                put("action", "delay")
                put("prompt", "Send me a reminder")
                put("schedule_type", "once")
                put("delay_amount", 5)
                put("delay_unit", "minutes")
            },
        )

        val job = result.structuredContent?.get("job")?.jsonObject ?: error("Missing job payload")
        assertEquals("2026-03-11T09:05:00Z", job["run_at"]?.jsonPrimitive?.content)
    }

    @Test
    fun delayActionRejectsInvalidAmountUnitAndMixedFields() = runBlocking {
        val fixture = createToolFixture(
            nowInstants = listOf(Instant.parse("2026-03-11T09:00:00Z")),
        )
        val executor = SchedulerBuiltInToolExecutor(fixture.service)

        val nonPositiveAmount = assertFailsWith<IllegalArgumentException> {
            executor.execute(
                buildJsonObject {
                    put("action", "delay")
                    put("prompt", "Send me a reminder")
                    put("delay_amount", 0)
                    put("delay_unit", "minutes")
                },
            )
        }
        assertContains(nonPositiveAmount.message.orEmpty(), "positive")

        val invalidUnit = assertFailsWith<IllegalArgumentException> {
            executor.execute(
                buildJsonObject {
                    put("action", "delay")
                    put("prompt", "Send me a reminder")
                    put("delay_amount", 5)
                    put("delay_unit", "day")
                },
            )
        }
        assertContains(invalidUnit.message.orEmpty(), "minute")

        val invalidScheduleType = assertFailsWith<IllegalArgumentException> {
            executor.execute(
                buildJsonObject {
                    put("action", "delay")
                    put("prompt", "Send me a reminder")
                    put("schedule_type", "repeat")
                    put("delay_amount", 5)
                    put("delay_unit", "minutes")
                },
            )
        }
        assertContains(invalidScheduleType.message.orEmpty(), "one-shot")

        val mixedFields = assertFailsWith<IllegalArgumentException> {
            executor.execute(
                buildJsonObject {
                    put("action", "delay")
                    put("prompt", "Send me a reminder")
                    put("delay_amount", 5)
                    put("delay_unit", "minutes")
                    put("run_at", "2026-03-11T10:00:00Z")
                    put("interval_minutes", 15)
                    put("schedule_id", "abc")
                },
            )
        }
        assertContains(mixedFields.message.orEmpty(), "does not accept")
    }

    @Test
    fun currentTimeActionReturnsExpectedStructuredSnapshotWithoutMutatingSchedules() = runBlocking {
        val fixture = createToolFixture(
            nowInstants = listOf(Instant.parse("2026-03-11T10:15:30Z")),
            timeZoneId = "Europe/Berlin",
        )
        val executor = SchedulerBuiltInToolExecutor(fixture.service)

        val result = executor.execute(
            buildJsonObject {
                put("action", "current_time")
            },
        )

        val currentTime = result.structuredContent?.get("current_time")?.jsonObject ?: error("Missing current_time payload")
        assertEquals("current_time", result.structuredContent?.get("action")?.jsonPrimitive?.content)
        assertEquals("2026-03-11T11:15:30+01:00", currentTime["local_time"]?.jsonPrimitive?.content)
        assertEquals("Europe/Berlin", currentTime["timezone"]?.jsonPrimitive?.content)
        assertEquals("2026-03-11T10:15:30Z", currentTime["utc_time"]?.jsonPrimitive?.content)
        assertEquals("1773224130", currentTime["unix_epoch_seconds"]?.jsonPrimitive?.content)
        assertContains(result.content.single().jsonObject["text"]?.jsonPrimitive?.content.orEmpty(), "Europe/Berlin")
        assertTrue(fixture.store.jobs.isEmpty())
    }

    @Test
    fun listAndCancelActionsReturnStoredSchedules() = runBlocking {
        val fixture = createToolFixture(
            nowInstants = listOf(
                Instant.parse("2026-03-11T09:00:00Z"),
                Instant.parse("2026-03-11T09:01:00Z"),
                Instant.parse("2026-03-11T09:02:00Z"),
            ),
        )
        val executor = SchedulerBuiltInToolExecutor(fixture.service)
        executor.execute(
            buildJsonObject {
                put("action", "create")
                put("prompt", "Summarize the repo")
                put("schedule_type", "once")
                put("run_at", "2026-03-11T10:00:00Z")
            },
        )

        val listResult = executor.execute(
            buildJsonObject {
                put("action", "list")
            },
        )
        assertTrue(listResult.structuredContent?.get("count")?.jsonPrimitive?.content == "1")
        assertContains(listResult.content.single().jsonObject["text"]?.jsonPrimitive?.content.orEmpty(), "Found 1")

        val scheduleId = fixture.store.jobs.single().scheduleId
        val cancelResult = executor.execute(
            buildJsonObject {
                put("action", "cancel")
                put("schedule_id", scheduleId)
            },
        )

        assertTrue(cancelResult.structuredContent?.get("was_cancelled")?.jsonPrimitive?.content == "true")
        assertEquals("cancelled", fixture.store.jobs.single().status.name.lowercase())
    }
}

private data class ToolFixture(
    val service: LaunchdSchedulerService,
    val store: RecordingToolScheduledJobStore,
)

private fun createToolFixture(
    nowInstants: List<Instant>,
    timeZoneId: String = "UTC",
): ToolFixture {
    val homeDirectory = uniqueToolSchedulerHomeDirectory()
    ensureDirectoryExists(homeDirectory)
    val store = RecordingToolScheduledJobStore()
    val runtime = ToolRuntimeEnvironment(
        homeDirectory = homeDirectory,
        currentWorkingDirectory = "/tmp/tool-workdir",
        executablePath = "/tmp/bin/agent-cli.kexe",
        timeZoneId = timeZoneId,
    )
    val clock = ToolSequencedNowProvider(nowInstants)
    return ToolFixture(
        service = LaunchdSchedulerService(
            scheduledJobStore = store,
            launchdClient = ToolRecordingLaunchdClient(),
            notificationService = ToolRecordingNotificationService(),
            runtimeEnvironment = runtime,
            logWriter = ToolRecordingLogWriter(),
            nowProvider = clock::next,
        ),
        store = store,
    )
}

private class RecordingToolScheduledJobStore : ScheduledJobStore {
    var jobs: List<ScheduledAgentJob> = emptyList()

    override fun load(): List<ScheduledAgentJob> = jobs.map { job -> job.copy() }

    override fun save(jobs: List<ScheduledAgentJob>) {
        this.jobs = jobs.map { job -> job.copy() }
    }
}

private class ToolRecordingLaunchdClient : LaunchdClient {
    override fun bootstrap(plistPath: String) = Unit

    override fun bootout(plistPath: String, label: String): Boolean = true
}

private class ToolRecordingNotificationService : NotificationService {
    override fun send(message: String, title: String): NotificationDelivery {
        return NotificationDelivery(title = title, message = message, backend = "fake")
    }
}

private class ToolRuntimeEnvironment(
    private val homeDirectory: String,
    private val currentWorkingDirectory: String,
    private val executablePath: String,
    private val timeZoneId: String,
) : AppRuntimeEnvironment {
    override fun homeDirectory(): String = homeDirectory
    override fun currentWorkingDirectory(): String = currentWorkingDirectory
    override fun currentExecutablePath(): String = executablePath
    override fun pathEnvironment(): String = ""
    override fun timeZoneId(): String = timeZoneId
    override fun changeWorkingDirectory(path: String) = Unit
}

private class ToolRecordingLogWriter : SchedulerLogWriter {
    override fun append(logPath: String, text: String) = Unit
}

private class ToolSequencedNowProvider(
    private val values: List<Instant>,
) {
    private var index = 0

    fun next(): Instant {
        val boundedIndex = if (index <= values.lastIndex) index else values.lastIndex
        val value = values[boundedIndex]
        index += 1
        return value
    }
}

private fun uniqueToolSchedulerHomeDirectory(): String {
    val seed = Random.nextLong().toString().replace('-', '0')
    return "/tmp/kotlin-agent-cli-tests/$seed/tool-home"
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
