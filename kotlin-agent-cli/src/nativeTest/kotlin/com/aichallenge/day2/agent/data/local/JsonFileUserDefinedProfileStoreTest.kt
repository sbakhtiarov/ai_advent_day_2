package com.aichallenge.day2.agent.data.local

import com.aichallenge.day2.agent.domain.model.ProfilePreferenceState
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

class JsonFileUserDefinedProfileStoreTest {
    @Test
    fun loadParsesPartialJsonAndKeepsDefaultsForMissingKeys() {
        val directory = uniqueUserProfileDirectoryPath()
        ensureDirectoryExists(directory)
        writeTextFile(
            path = "$directory/user-profile-default.json",
            text = """
                {
                  "display_name": "Default profile",
                  "writingStyle": "concise bullets",
                  "toolingPreferences": ["use rg", "use rg"],
                  "name": "Alex",
                  "unknown": "ignored"
                }
            """.trimIndent(),
        )
        val store = JsonFileUserDefinedProfileStore(directory)
        assertTrue(store.setActiveProfile("user-profile-default.json"))

        val loaded = store.load()

        assertEquals(
            ProfilePreferenceState(
                writingStyle = "concise bullets",
                toolingPreferences = listOf("use rg", "use rg"),
                workflowDefaults = emptyList(),
                stableConstraints = emptyList(),
                name = "Alex",
                work = "",
                profession = "",
                otherFacts = emptyList(),
            ),
            loaded,
        )
    }

    @Test
    fun listProfilesUsesDisplayNameOrFilenameFallback() {
        val directory = uniqueUserProfileDirectoryPath()
        ensureDirectoryExists(directory)
        writeTextFile(
            "$directory/user-profile-personal.json",
            """
                {
                  "writingStyle": "casual"
                }
            """.trimIndent(),
        )
        writeTextFile(
            "$directory/user-profile-work.json",
            """
                {
                  "display_name": "Work profile",
                  "writingStyle": "formal"
                }
            """.trimIndent(),
        )
        val store = JsonFileUserDefinedProfileStore(directory)

        val profiles = store.listProfiles()

        assertEquals(
            listOf(
                "user-profile-personal.json" to "personal",
                "user-profile-work.json" to "Work profile",
            ),
            profiles.map { profile -> profile.fileName to profile.displayName },
        )
    }

    @Test
    fun listProfilesSkipsFilesWithInvalidNamePatternOrMalformedJson() {
        val directory = uniqueUserProfileDirectoryPath()
        ensureDirectoryExists(directory)
        writeTextFile(
            "$directory/user-profile-good.json",
            """
                {
                  "display_name": "Good profile",
                  "writingStyle": "concise"
                }
            """.trimIndent(),
        )
        writeTextFile(
            "$directory/user-profile-bad.json",
            "{ malformed json",
        )
        writeTextFile(
            "$directory/not-a-profile.json",
            """
                {
                  "display_name": "Ignored"
                }
            """.trimIndent(),
        )
        val store = JsonFileUserDefinedProfileStore(directory)

        val profiles = store.listProfiles()

        assertEquals(
            listOf("user-profile-good.json"),
            profiles.map { profile -> profile.fileName },
        )
    }

    @Test
    fun setActiveProfilePersistsSelectionAcrossStoreInstances() {
        val directory = uniqueUserProfileDirectoryPath()
        ensureDirectoryExists(directory)
        writeTextFile(
            "$directory/user-profile-default.json",
            """
                {
                  "display_name": "Default",
                  "writingStyle": "default style"
                }
            """.trimIndent(),
        )
        writeTextFile(
            "$directory/user-profile-work.json",
            """
                {
                  "display_name": "Work",
                  "writingStyle": "work style"
                }
            """.trimIndent(),
        )
        val firstStore = JsonFileUserDefinedProfileStore(directory)
        assertTrue(firstStore.setActiveProfile("user-profile-work.json"))

        val secondStore = JsonFileUserDefinedProfileStore(directory)

        assertEquals("user-profile-work.json", secondStore.activeProfileFileName())
        assertEquals("work style", secondStore.load()?.writingStyle)
    }

    @Test
    fun loadReturnsNullWhenNoValidProfilesExist() {
        val directory = uniqueUserProfileDirectoryPath()
        ensureDirectoryExists(directory)
        val store = JsonFileUserDefinedProfileStore(directory)

        assertEquals(null, store.load())
        assertEquals(emptyList(), store.listProfiles())
        assertEquals(null, store.activeProfileFileName())
        assertFalse(store.setActiveProfile("user-profile-default.json"))
    }
}

private fun uniqueUserProfileDirectoryPath(): String {
    val seed = Random.nextLong().toString().replace('-', '0')
    return "/tmp/kotlin-agent-cli-tests/$seed/profiles"
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
