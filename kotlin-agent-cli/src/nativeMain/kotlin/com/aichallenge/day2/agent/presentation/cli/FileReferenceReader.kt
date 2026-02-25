package com.aichallenge.day2.agent.presentation.cli

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.getenv

interface FileReferenceReader {
    fun read(path: String): String
}

object PosixFileReferenceReader : FileReferenceReader {
    override fun read(path: String): String {
        val normalizedPath = path.trim()
        if (normalizedPath.isEmpty()) {
            throw IllegalArgumentException("Path must not be empty.")
        }

        val resolvedPath = resolvePath(normalizedPath)
        return readTextFile(resolvedPath)
            ?: throw IllegalStateException("Unable to read file '$resolvedPath'.")
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun resolvePath(path: String): String {
        if (!path.startsWith("~/")) {
            return path
        }

        val homeDirectory = getenv("HOME")?.toKString()
            ?: throw IllegalStateException("HOME is not set; cannot resolve '~' in paths.")
        return "${homeDirectory.trimEnd('/')}/${path.removePrefix("~/")}"
    }

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

    private const val READ_BUFFER_SIZE = 4096
}
