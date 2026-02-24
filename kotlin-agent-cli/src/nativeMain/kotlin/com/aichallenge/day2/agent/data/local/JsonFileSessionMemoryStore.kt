package com.aichallenge.day2.agent.data.local

import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.repository.SessionMemoryStore
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import platform.posix.EEXIST
import platform.posix.ENOENT
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.mode_t
import platform.posix.unlink

class JsonFileSessionMemoryStore(
    private val filePath: String,
    private val json: Json = defaultJson(),
) : SessionMemoryStore {
    override fun load(): List<ConversationMessage>? {
        val fileContents = readTextFile(filePath) ?: return null
        val snapshot = runCatching {
            json.decodeFromString<SessionMemorySnapshotDto>(fileContents)
        }.getOrNull() ?: return null

        if (snapshot.version != SNAPSHOT_VERSION) {
            return null
        }

        return snapshot.messages.map { it.toDomainModel() }
    }

    override fun save(messages: List<ConversationMessage>) {
        val snapshot = SessionMemorySnapshotDto(
            version = SNAPSHOT_VERSION,
            messages = messages.map { it.toPersistedDto() },
        )
        val payload = json.encodeToString(snapshot)
        ensureParentDirectoryExists(filePath)
        writeTextFile(filePath, payload)
    }

    override fun clear() {
        val deleteResult = unlink(filePath)
        if (deleteResult == 0 || errno == ENOENT) {
            return
        }

        throw IllegalStateException("Unable to remove session memory file '$filePath'.")
    }

    private fun ensureParentDirectoryExists(path: String) {
        val parent = parentDirectory(path) ?: return
        ensureDirectoryExists(parent)
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
        private const val SNAPSHOT_VERSION = 1
        private const val DIRECTORY_MODE = 493 // 0755
        private const val READ_BUFFER_SIZE = 4096

        fun fromDefaultLocation(json: Json = defaultJson()): JsonFileSessionMemoryStore? {
            val homeDirectory = readHomeDirectory() ?: return null
            val normalizedHome = homeDirectory.trimEnd('/')
            val defaultPath = "$normalizedHome/.kotlin-agent-cli/session-memory.json"
            return JsonFileSessionMemoryStore(filePath = defaultPath, json = json)
        }

        private fun defaultJson(): Json = Json {
            prettyPrint = true
            encodeDefaults = true
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
                    throw IllegalStateException("Unable to write session memory file '$path'.")
                }
            } finally {
                fclose(file)
            }
        }
    }
}
