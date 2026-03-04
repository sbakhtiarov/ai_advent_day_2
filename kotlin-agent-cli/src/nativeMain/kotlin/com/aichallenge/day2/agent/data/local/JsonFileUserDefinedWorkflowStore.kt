package com.aichallenge.day2.agent.data.local

import com.aichallenge.day2.agent.domain.model.UserWorkflowDefinition
import com.aichallenge.day2.agent.domain.model.UserWorkflowOption
import com.aichallenge.day2.agent.domain.repository.UserDefinedWorkflowStore
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

class JsonFileUserDefinedWorkflowStore(
    private val directoryPath: String,
    private val json: Json = defaultJson(),
) : UserDefinedWorkflowStore {
    override fun listWorkflows(): List<UserWorkflowOption> {
        return discoverWorkflows().map { workflow ->
            UserWorkflowOption(
                fileName = workflow.fileName,
                displayName = workflow.snapshot.name.trim(),
            )
        }
    }

    override fun loadActiveWorkflow(): UserWorkflowDefinition? {
        val workflows = discoverWorkflows()
        if (workflows.isEmpty()) {
            return null
        }

        val selectedFileName = activeWorkflowFileName()
            ?.takeIf { fileName -> workflows.any { workflow -> workflow.fileName == fileName } }
            ?: workflows.first().fileName
        val selectedWorkflow = workflows.firstOrNull { workflow -> workflow.fileName == selectedFileName } ?: return null
        return selectedWorkflow.toDomainModel()
    }

    override fun activeWorkflowFileName(): String? {
        val stateContents = readTextFile(activeWorkflowStatePath()) ?: return null
        val state = runCatching {
            json.decodeFromString<ActiveWorkflowSnapshotDto>(stateContents)
        }.getOrNull() ?: return null
        return state.activeFileName.trim().takeIf { value -> value.isNotEmpty() }
    }

    override fun setActiveWorkflow(fileName: String): Boolean {
        val normalizedFileName = fileName.trim()
        if (normalizedFileName.isEmpty()) {
            return false
        }
        if (discoverWorkflows().none { workflow -> workflow.fileName == normalizedFileName }) {
            return false
        }

        val payload = json.encodeToString(
            ActiveWorkflowSnapshotDto(activeFileName = normalizedFileName),
        )
        return runCatching {
            ensureDirectoryExists(directoryPath)
            writeTextFile(activeWorkflowStatePath(), payload)
            true
        }.getOrDefault(false)
    }

    private fun activeWorkflowStatePath(): String {
        return "${directoryPath.trimEnd('/')}/$ACTIVE_WORKFLOW_FILE_NAME"
    }

    private fun discoverWorkflows(): List<DiscoveredWorkflow> {
        val normalizedDirectory = directoryPath.trimEnd('/')
        val fileNames = listDirectoryFileNames(normalizedDirectory)
        return fileNames.mapNotNull { fileName ->
            WORKFLOW_FILE_NAME_REGEX.matchEntire(fileName) ?: return@mapNotNull null
            val fileContents = readTextFile("$normalizedDirectory/$fileName") ?: return@mapNotNull null
            val snapshot = runCatching {
                json.decodeFromString<UserDefinedWorkflowSnapshotDto>(fileContents)
            }.getOrNull() ?: return@mapNotNull null
            if (!snapshot.isValid()) {
                return@mapNotNull null
            }
            DiscoveredWorkflow(
                fileName = fileName,
                snapshot = snapshot,
            )
        }.sortedBy { workflow ->
            workflow.fileName.lowercase()
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
        private const val ACTIVE_WORKFLOW_FILE_NAME = "active-workflow.json"
        private const val DIRECTORY_MODE = 493 // 0755
        private const val READ_BUFFER_SIZE = 4096
        private val WORKFLOW_FILE_NAME_REGEX = Regex("^workflow-(.+)\\.json$")

        fun fromDefaultLocation(json: Json = defaultJson()): JsonFileUserDefinedWorkflowStore? {
            val homeDirectory = readHomeDirectory() ?: return null
            val normalizedHome = homeDirectory.trimEnd('/')
            return JsonFileUserDefinedWorkflowStore(
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
private data class UserDefinedWorkflowSnapshotDto(
    val name: String = "",
    val basePrompt: String? = null,
    val planning: String = "",
    val execution: String = "",
    val validation: String = "",
) {
    fun isValid(): Boolean {
        return name.isNotBlank() &&
            planning.isNotBlank() &&
            execution.isNotBlank() &&
            validation.isNotBlank()
    }
}

@Serializable
private data class ActiveWorkflowSnapshotDto(
    @SerialName("active_file_name")
    val activeFileName: String = "",
)

private data class DiscoveredWorkflow(
    val fileName: String,
    val snapshot: UserDefinedWorkflowSnapshotDto,
) {
    fun toDomainModel(): UserWorkflowDefinition {
        return UserWorkflowDefinition(
            fileName = fileName,
            name = snapshot.name.trim(),
            basePrompt = snapshot.basePrompt?.trim()?.takeIf { value -> value.isNotEmpty() },
            planning = snapshot.planning.trim(),
            execution = snapshot.execution.trim(),
            validation = snapshot.validation.trim(),
        )
    }
}
