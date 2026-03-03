package com.aichallenge.day2.agent.data.local

import com.aichallenge.day2.agent.domain.model.ProfilePreferenceState
import com.aichallenge.day2.agent.domain.repository.UserDefinedProfileStore
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.getenv

class JsonFileUserDefinedProfileStore(
    private val filePath: String,
    private val json: Json = defaultJson(),
) : UserDefinedProfileStore {
    override fun load(): ProfilePreferenceState? {
        val fileContents = readTextFile(filePath) ?: return null
        val snapshot = runCatching {
            json.decodeFromString<UserDefinedProfilePreferenceSnapshotDto>(fileContents)
        }.getOrNull() ?: return null

        return snapshot.toDomainModel()
    }

    companion object {
        private const val READ_BUFFER_SIZE = 4096

        fun fromDefaultLocation(json: Json = defaultJson()): JsonFileUserDefinedProfileStore? {
            val homeDirectory = readHomeDirectory() ?: return null
            val normalizedHome = homeDirectory.trimEnd('/')
            return JsonFileUserDefinedProfileStore(
                filePath = "$normalizedHome/.kotlin-agent-cli/user-profile-default.json",
                json = json,
            )
        }

        private fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
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
    }
}

@Serializable
private data class UserDefinedProfilePreferenceSnapshotDto(
    val writingStyle: String = "",
    val toolingPreferences: List<String> = emptyList(),
    val workflowDefaults: List<String> = emptyList(),
    val stableConstraints: List<String> = emptyList(),
    val name: String = "",
    val work: String = "",
    val profession: String = "",
    val otherFacts: List<String> = emptyList(),
)

private fun UserDefinedProfilePreferenceSnapshotDto.toDomainModel(): ProfilePreferenceState = ProfilePreferenceState(
    writingStyle = writingStyle,
    toolingPreferences = toolingPreferences,
    workflowDefaults = workflowDefaults,
    stableConstraints = stableConstraints,
    name = name,
    work = work,
    profession = profession,
    otherFacts = otherFacts,
)
