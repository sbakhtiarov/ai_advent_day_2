@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.aichallenge.day2.agent.data.tools

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuiltInToolRegistryTest {
    @Test
    fun createDefaultIncludesNotifyUserAndSchedulerBuiltIns() {
        val bindings = BuiltInToolRegistry.createDefault(
            httpClient = HttpClient(
                MockEngine {
                    error("Unexpected HTTP request in registry test.")
                },
            ),
            json = Json { ignoreUnknownKeys = true },
        ).listPrivateToolBindings()

        assertEquals(
            listOf(
                "notify_user",
                "scheduler",
                "create_file",
                "list_files",
                "read_file",
                "find_file_by_name",
                "search_file_content",
                "edit_file",
                "delete_file",
                "diff_files",
                "convert_to_pdf",
                "fetch_github_pull_request",
            ),
            bindings.map { binding -> binding.modelToolName },
        )
        assertEquals(bindings.size, bindings.map { binding -> binding.target }.distinct().size)
        assertTrue(bindings.all { binding -> binding.parametersSchema.isNotEmpty() })
    }
}
