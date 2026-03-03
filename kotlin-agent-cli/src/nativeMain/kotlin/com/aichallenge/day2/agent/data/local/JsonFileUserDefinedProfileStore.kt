package com.aichallenge.day2.agent.data.local

import com.aichallenge.day2.agent.domain.model.ProfilePreferenceState
import com.aichallenge.day2.agent.domain.model.UserProfileOption
import com.aichallenge.day2.agent.domain.repository.UserDefinedProfileStore
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.posix.EEXIST
import platform.posix.closedir
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.mode_t
import platform.posix.opendir
import platform.posix.readdir

class JsonFileUserDefinedProfileStore(
    private val directoryPath: String,
    private val json: Json = defaultJson(),
) : UserDefinedProfileStore {
    override fun load(): ProfilePreferenceState? {
        val profiles = discoverProfiles()
        if (profiles.isEmpty()) {
            return null
        }

        val selectedFileName = activeProfileFileName()
            ?.takeIf { fileName -> profiles.any { profile -> profile.fileName == fileName } }
            ?: profiles.first().fileName
        val selectedProfile = profiles.firstOrNull { profile -> profile.fileName == selectedFileName } ?: return null
        return selectedProfile.snapshot.toDomainModel()
    }

    override fun listProfiles(): List<UserProfileOption> {
        return discoverProfiles().map { profile ->
            UserProfileOption(
                fileName = profile.fileName,
                displayName = profile.displayName,
            )
        }
    }

    override fun activeProfileFileName(): String? {
        val stateContents = readTextFile(activeProfileStatePath()) ?: return null
        val state = runCatching {
            json.decodeFromString<ActiveUserProfileSnapshotDto>(stateContents)
        }.getOrNull() ?: return null
        return state.activeFileName.trim().takeIf { value -> value.isNotEmpty() }
    }

    override fun setActiveProfile(fileName: String): Boolean {
        val normalizedFileName = fileName.trim()
        if (normalizedFileName.isEmpty()) {
            return false
        }
        if (discoverProfiles().none { profile -> profile.fileName == normalizedFileName }) {
            return false
        }

        val payload = json.encodeToString(
            ActiveUserProfileSnapshotDto(activeFileName = normalizedFileName),
        )
        return runCatching {
            ensureDirectoryExists(directoryPath)
            writeTextFile(activeProfileStatePath(), payload)
            true
        }.getOrDefault(false)
    }

    private fun activeProfileStatePath(): String {
        return "${directoryPath.trimEnd('/')}/$ACTIVE_PROFILE_FILE_NAME"
    }

    private fun discoverProfiles(): List<DiscoveredUserProfile> {
        val normalizedDirectory = directoryPath.trimEnd('/')
        val fileNames = listDirectoryFileNames(normalizedDirectory)
        return fileNames.mapNotNull { fileName ->
            val match = PROFILE_FILE_NAME_REGEX.matchEntire(fileName) ?: return@mapNotNull null
            val fileContents = readTextFile("$normalizedDirectory/$fileName") ?: return@mapNotNull null
            val snapshot = runCatching {
                json.decodeFromString<UserDefinedProfilePreferenceSnapshotDto>(fileContents)
            }.getOrNull() ?: return@mapNotNull null
            val displayName = snapshot.displayName.trim().ifEmpty { match.groupValues[1] }
            DiscoveredUserProfile(
                fileName = fileName,
                displayName = displayName,
                snapshot = snapshot,
            )
        }.sortedBy { profile ->
            profile.fileName.lowercase()
        }
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
        private const val PROFILE_DIRECTORY_NAME = ".kotlin-agent-cli"
        private const val ACTIVE_PROFILE_FILE_NAME = "active-user-profile.json"
        private const val DIRECTORY_MODE = 493 // 0755
        private const val READ_BUFFER_SIZE = 4096
        private val PROFILE_FILE_NAME_REGEX = Regex("^user-profile-(.+)\\.json$")

        fun fromDefaultLocation(json: Json = defaultJson()): JsonFileUserDefinedProfileStore? {
            val homeDirectory = readHomeDirectory() ?: return null
            val normalizedHome = homeDirectory.trimEnd('/')
            return JsonFileUserDefinedProfileStore(
                directoryPath = "$normalizedHome/$PROFILE_DIRECTORY_NAME",
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

        @OptIn(ExperimentalForeignApi::class)
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

        @OptIn(ExperimentalForeignApi::class)
        private fun listDirectoryFileNames(path: String): List<String> {
            val directory = opendir(path) ?: return emptyList()
            return try {
                val names = mutableListOf<String>()
                while (true) {
                    val entry = readdir(directory) ?: break
                    val name = entry.pointed.d_name.toKString()
                    if (name == "." || name == "..") {
                        continue
                    }
                    names += name
                }
                names
            } finally {
                closedir(directory)
            }
        }
    }
}

@Serializable
private data class UserDefinedProfilePreferenceSnapshotDto(
    @SerialName("display_name")
    val displayName: String = "",
    val writingStyle: String = "",
    val toolingPreferences: List<String> = emptyList(),
    val workflowDefaults: List<String> = emptyList(),
    val stableConstraints: List<String> = emptyList(),
    val name: String = "",
    val work: String = "",
    val profession: String = "",
    val otherFacts: List<String> = emptyList(),
)

@Serializable
private data class ActiveUserProfileSnapshotDto(
    @SerialName("active_file_name")
    val activeFileName: String = "",
)

private data class DiscoveredUserProfile(
    val fileName: String,
    val displayName: String,
    val snapshot: UserDefinedProfilePreferenceSnapshotDto,
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
