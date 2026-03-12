@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.aichallenge.day2.agent.data.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuiltInToolRegistryTest {
    @Test
    fun createDefaultIncludesNotifyUserAndSchedulerBuiltIns() {
        val bindings = BuiltInToolRegistry.createDefault().listPrivateToolBindings()

        assertEquals(
            listOf("notify_user", "scheduler", "save_to_file", "convert_to_pdf"),
            bindings.map { binding -> binding.modelToolName },
        )
        assertEquals(bindings.size, bindings.map { binding -> binding.target }.distinct().size)
        assertTrue(bindings.all { binding -> binding.parametersSchema.isNotEmpty() })
    }
}
