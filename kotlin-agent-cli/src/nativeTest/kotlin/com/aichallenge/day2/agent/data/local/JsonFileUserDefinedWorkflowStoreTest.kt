package com.aichallenge.day2.agent.data.local

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.posix.EEXIST
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.mkdir
import platform.posix.mode_t

class JsonFileUserDefinedWorkflowStoreTest {
    @Test
    fun listWorkflowsAndLoadActiveWorkflowParseValidFiles() {
        val directory = uniqueWorkflowDirectoryPath()
        ensureDirectoryExists(directory)
        writeTextFile(
            "$directory/workflow-default.json",
            """
                {
                  "name": "Default workflow",
                  "planning": "Create a plan first",
                  "execution": "Implement after plan",
                  "validation": "Run validation checks"
                }
            """.trimIndent(),
        )
        writeTextFile(
            "$directory/workflow-review.json",
            """
                {
                  "name": "Review workflow",
                  "basePrompt": "You are in review workflow mode.",
                  "planning": "Define review scope",
                  "execution": "Inspect changed files",
                  "validation": "Report findings"
                }
            """.trimIndent(),
        )
        val store = JsonFileUserDefinedWorkflowStore(directory)
        assertTrue(store.setActiveWorkflow("workflow-review.json"))

        val workflows = store.listWorkflows()
        val active = store.loadActiveWorkflow()

        assertEquals(
            listOf(
                "workflow-default.json" to "Default workflow",
                "workflow-review.json" to "Review workflow",
            ),
            workflows.map { workflow -> workflow.fileName to workflow.displayName },
        )
        assertEquals("workflow-review.json", active?.fileName)
        assertEquals("Review workflow", active?.name)
        assertEquals("You are in review workflow mode.", active?.basePrompt)
    }

    @Test
    fun listWorkflowsSkipsFilesWithInvalidNameMalformedJsonOrMissingRequiredKeys() {
        val directory = uniqueWorkflowDirectoryPath()
        ensureDirectoryExists(directory)
        writeTextFile(
            "$directory/workflow-good.json",
            """
                {
                  "name": "Good workflow",
                  "planning": "plan",
                  "execution": "execute",
                  "validation": "validate"
                }
            """.trimIndent(),
        )
        writeTextFile(
            "$directory/workflow-malformed.json",
            "{ malformed json",
        )
        writeTextFile(
            "$directory/workflow-missing.json",
            """
                {
                  "name": "Missing validation",
                  "planning": "plan",
                  "execution": "execute"
                }
            """.trimIndent(),
        )
        writeTextFile(
            "$directory/workflow-blank.json",
            """
                {
                  "name": "Blank planning",
                  "planning": "   ",
                  "execution": "execute",
                  "validation": "validate"
                }
            """.trimIndent(),
        )
        writeTextFile(
            "$directory/not-a-workflow.json",
            """
                {
                  "name": "Ignored workflow",
                  "planning": "plan",
                  "execution": "execute",
                  "validation": "validate"
                }
            """.trimIndent(),
        )
        val store = JsonFileUserDefinedWorkflowStore(directory)

        val workflows = store.listWorkflows()

        assertEquals(
            listOf("workflow-good.json"),
            workflows.map { workflow -> workflow.fileName },
        )
    }

    @Test
    fun setActiveWorkflowPersistsSelectionAcrossStoreInstances() {
        val directory = uniqueWorkflowDirectoryPath()
        ensureDirectoryExists(directory)
        writeTextFile(
            "$directory/workflow-default.json",
            """
                {
                  "name": "Default",
                  "planning": "plan default",
                  "execution": "execute default",
                  "validation": "validate default"
                }
            """.trimIndent(),
        )
        writeTextFile(
            "$directory/workflow-review.json",
            """
                {
                  "name": "Review",
                  "planning": "plan review",
                  "execution": "execute review",
                  "validation": "validate review"
                }
            """.trimIndent(),
        )
        val firstStore = JsonFileUserDefinedWorkflowStore(directory)
        assertTrue(firstStore.setActiveWorkflow("workflow-review.json"))

        val secondStore = JsonFileUserDefinedWorkflowStore(directory)

        assertEquals("workflow-review.json", secondStore.activeWorkflowFileName())
        assertEquals("Review", secondStore.loadActiveWorkflow()?.name)
    }

    @Test
    fun loadActiveWorkflowFallsBackToFirstWhenActiveSelectionIsMissing() {
        val directory = uniqueWorkflowDirectoryPath()
        ensureDirectoryExists(directory)
        writeTextFile(
            "$directory/workflow-alpha.json",
            """
                {
                  "name": "Alpha",
                  "planning": "plan alpha",
                  "execution": "execute alpha",
                  "validation": "validate alpha"
                }
            """.trimIndent(),
        )
        writeTextFile(
            "$directory/workflow-beta.json",
            """
                {
                  "name": "Beta",
                  "planning": "plan beta",
                  "execution": "execute beta",
                  "validation": "validate beta"
                }
            """.trimIndent(),
        )
        val store = JsonFileUserDefinedWorkflowStore(directory)

        assertEquals("workflow-alpha.json", store.loadActiveWorkflow()?.fileName)
    }

    @Test
    fun loadActiveWorkflowReturnsNullWhenNoValidWorkflowsExist() {
        val directory = uniqueWorkflowDirectoryPath()
        ensureDirectoryExists(directory)
        val store = JsonFileUserDefinedWorkflowStore(directory)

        assertEquals(emptyList(), store.listWorkflows())
        assertEquals(null, store.activeWorkflowFileName())
        assertEquals(null, store.loadActiveWorkflow())
        assertFalse(store.setActiveWorkflow("workflow-default.json"))
    }
}

private fun uniqueWorkflowDirectoryPath(): String {
    val seed = Random.nextLong().toString().replace('-', '0')
    return "/tmp/kotlin-agent-cli-tests/$seed/workflows"
}

private fun parentDirectory(path: String): String {
    val normalized = path.trimEnd('/')
    val separatorIndex = normalized.lastIndexOf('/')
    return if (separatorIndex <= 0) "/" else normalized.substring(0, separatorIndex)
}

@OptIn(ExperimentalForeignApi::class)
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

@OptIn(ExperimentalForeignApi::class)
private fun writeTextFile(path: String, text: String) {
    val file = fopen(path, "w") ?: error("Unable to open test file '$path'.")
    try {
        if (fputs(text, file) < 0) {
            error("Unable to write test file '$path'.")
        }
    } finally {
        fclose(file)
    }
}
