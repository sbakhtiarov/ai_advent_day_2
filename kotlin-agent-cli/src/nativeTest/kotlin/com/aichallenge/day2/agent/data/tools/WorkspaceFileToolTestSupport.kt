package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.core.config.AppRuntimeEnvironment
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.EEXIST
import platform.posix.F_OK
import platform.posix.access
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.mkdir
import platform.posix.mode_t
import kotlin.random.Random

internal class TestRuntimeEnvironment(
    private val currentWorkingDirectory: String,
) : AppRuntimeEnvironment {
    override fun homeDirectory(): String? = "/tmp"
    override fun currentWorkingDirectory(): String = currentWorkingDirectory
    override fun currentExecutablePath(): String? = "/tmp/agent-cli.kexe"
    override fun pathEnvironment(): String? = ""
    override fun timeZoneId(): String? = "UTC"
    override fun changeWorkingDirectory(path: String) = Unit
}

internal fun uniqueWorkspaceRoot(name: String): String {
    val seed = Random.nextLong().toString().replace('-', '0')
    return "/tmp/kotlin-agent-cli-tests/$seed/$name"
}

@OptIn(ExperimentalForeignApi::class)
internal fun ensureTestDirectoryExists(path: String) {
    if (path.isBlank() || path == "/") return

    val parent = testParentDirectory(path)
    if (parent != null && parent != path) {
        ensureTestDirectoryExists(parent)
    }

    val createResult = mkdir(path, DIRECTORY_MODE.convert<mode_t>())
    if (createResult == 0 || errno == EEXIST) {
        return
    }

    error("Unable to create directory '$path'.")
}

internal fun testParentDirectory(path: String): String? {
    if (path.isBlank() || path == "/") return null
    val normalized = path.trimEnd('/')
    val separatorIndex = normalized.lastIndexOf('/')
    if (separatorIndex < 0) return null
    return if (separatorIndex == 0) "/" else normalized.substring(0, separatorIndex)
}

@OptIn(ExperimentalForeignApi::class)
internal fun writeTestTextFile(path: String, text: String) {
    ensureTestDirectoryExists(testParentDirectory(path) ?: "/")
    val file = fopen(path, "w")
        ?: error("Unable to open '$path' for writing.")
    try {
        if (fputs(text, file) < 0) {
            error("Unable to write '$path'.")
        }
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun readTestTextFile(path: String): String {
    val file = fopen(path, "r")
        ?: error("Unable to open '$path' for reading.")
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
internal fun testFileExists(path: String): Boolean {
    return access(path, F_OK.convert()) == 0
}

private const val READ_BUFFER_SIZE = 4096
private const val DIRECTORY_MODE = 493
