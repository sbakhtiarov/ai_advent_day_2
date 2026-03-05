package com.aichallenge.day2.agent.data.local

import com.aichallenge.day2.agent.domain.repository.InvariantConstraintStore
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.posix.EEXIST
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.mode_t

class JsonFileInvariantConstraintStore(
    private val filePath: String,
    private val json: Json = defaultJson(),
) : InvariantConstraintStore {
    override fun load(): List<String> {
        val fileContents = readTextFile(filePath) ?: return emptyList()
        val snapshot = runCatching {
            json.decodeFromString<InvariantConstraintSnapshotDto>(fileContents)
        }.getOrNull() ?: return emptyList()

        if (snapshot.version != SNAPSHOT_VERSION) {
            return emptyList()
        }

        return normalizeConstraints(snapshot.constraints)
    }

    override fun save(constraints: List<String>) {
        val normalizedConstraints = normalizeConstraints(constraints)
        val payload = json.encodeToString(
            InvariantConstraintSnapshotDto(
                version = SNAPSHOT_VERSION,
                constraints = normalizedConstraints,
            ),
        )
        ensureParentDirectoryExists(filePath)
        writeTextFile(filePath, payload)
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
        if (createResult == 0 || platform.posix.errno == EEXIST) {
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

    private fun normalizeConstraints(values: List<String>): List<String> {
        val normalized = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        values.forEach { rawValue ->
            val trimmed = rawValue.trim()
            if (trimmed.isEmpty()) {
                return@forEach
            }

            val dedupeKey = trimmed.lowercase()
            if (seen.add(dedupeKey)) {
                normalized += trimmed
            }
        }
        return normalized
    }

    companion object {
        private const val SNAPSHOT_VERSION = 1
        private const val DIRECTORY_MODE = 493 // 0755
        private const val READ_BUFFER_SIZE = 4096

        fun fromDefaultLocation(json: Json = defaultJson()): JsonFileInvariantConstraintStore? {
            val homeDirectory = readHomeDirectory() ?: return null
            val normalizedHome = homeDirectory.trimEnd('/')
            return JsonFileInvariantConstraintStore(
                filePath = "$normalizedHome/.kotlin-agent-cli/invariant-constraints.json",
                json = json,
            )
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
                    throw IllegalStateException("Unable to write invariant constraints file '$path'.")
                }
            } finally {
                fclose(file)
            }
        }
    }
}

@Serializable
private data class InvariantConstraintSnapshotDto(
    val version: Int = 1,
    val constraints: List<String> = emptyList(),
)
