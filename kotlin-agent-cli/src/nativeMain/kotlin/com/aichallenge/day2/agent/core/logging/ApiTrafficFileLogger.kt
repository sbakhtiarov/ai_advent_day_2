package com.aichallenge.day2.agent.core.logging

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.EEXIST
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.mkdir
import platform.posix.mode_t

class ApiTrafficFileLogger(
    private val filePath: String,
) {
    private var nextExchangeId = 1L

    fun reserveExchangeId(): Long {
        val exchangeId = nextExchangeId
        nextExchangeId += 1
        return exchangeId
    }

    fun logRequest(
        exchangeId: Long,
        method: String,
        url: String,
        headers: List<Pair<String, String>>,
        body: String,
    ) {
        appendEntry(
            buildString {
                appendLine("===== OpenAI API Exchange #$exchangeId REQUEST =====")
                appendLine("$method $url")
                appendLine("Headers:")
                appendLine(formatHeaders(headers))
                appendLine("Body:")
                appendLine(if (body.isBlank()) "(empty)" else body)
                appendLine()
            },
        )
    }

    fun logResponse(
        exchangeId: Long,
        statusCode: Int,
        statusDescription: String,
        headers: List<Pair<String, String>>,
        body: String,
    ) {
        appendEntry(
            buildString {
                appendLine("===== OpenAI API Exchange #$exchangeId RESPONSE =====")
                appendLine("HTTP $statusCode $statusDescription")
                appendLine("Headers:")
                appendLine(formatHeaders(headers))
                appendLine("Body:")
                appendLine(if (body.isBlank()) "(empty)" else body)
                appendLine("===== End Exchange #$exchangeId =====")
                appendLine()
            },
        )
    }

    fun logFailure(
        exchangeId: Long,
        throwable: Throwable,
    ) {
        appendEntry(
            buildString {
                appendLine("===== OpenAI API Exchange #$exchangeId FAILURE =====")
                appendLine(throwable::class.simpleName ?: "Throwable")
                appendLine(throwable.message ?: "(no message)")
                appendLine("===== End Exchange #$exchangeId =====")
                appendLine()
            },
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    fun readAll(): String? {
        val file = fopen(filePath, "r") ?: return null
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

    private fun formatHeaders(headers: List<Pair<String, String>>): String {
        if (headers.isEmpty()) {
            return "(none)"
        }

        return headers.joinToString(separator = "\n") { (name, value) ->
            "$name: ${sanitizeHeaderValue(name, value)}"
        }
    }

    private fun sanitizeHeaderValue(name: String, value: String): String {
        return if (name.equals("Authorization", ignoreCase = true)) {
            "Bearer <redacted>"
        } else {
            value
        }
    }

    private fun appendEntry(entry: String) {
        ensureParentDirectoryExists(filePath)
        writeTextFile(path = filePath, text = entry)
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

        val result = mkdir(path, DIRECTORY_MODE.convert<mode_t>())
        if (result == 0 || errno == EEXIST) {
            return
        }

        throw IllegalStateException("Unable to create directory '$path' for API traffic logging.")
    }

    private fun parentDirectory(path: String): String? {
        if (path.isBlank() || path == "/") return null
        val normalized = path.trimEnd('/')
        val separatorIndex = normalized.lastIndexOf('/')
        if (separatorIndex < 0) return null
        return if (separatorIndex == 0) "/" else normalized.substring(0, separatorIndex)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun writeTextFile(path: String, text: String) {
        val file = fopen(path, "a")
            ?: throw IllegalStateException("Unable to open API traffic log '$path' for writing.")

        try {
            if (fputs(text, file) < 0) {
                throw IllegalStateException("Unable to append API traffic log '$path'.")
            }
        } finally {
            fclose(file)
        }
    }

    companion object {
        private const val DIRECTORY_MODE = 493 // 0755
        private const val READ_BUFFER_SIZE = 4096
    }
}
