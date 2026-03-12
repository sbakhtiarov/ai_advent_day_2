@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.aichallenge.day2.agent.core.config

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.datetime.TimeZone
import platform.Foundation.NSBundle
import platform.Foundation.NSProcessInfo
import platform.posix.F_OK
import platform.posix.X_OK
import platform.posix.access
import platform.posix.chdir
import platform.posix.getenv
import platform.posix.getcwd
import platform.posix.realpath

interface AppRuntimeEnvironment {
    fun homeDirectory(): String?
    fun currentWorkingDirectory(): String?
    fun currentExecutablePath(): String?
    fun pathEnvironment(): String?
    fun timeZoneId(): String?
    fun changeWorkingDirectory(path: String)
}

open class DefaultAppRuntimeEnvironment : AppRuntimeEnvironment {
    override fun homeDirectory(): String? = readEnvironmentVariable("HOME")?.trim()?.takeIf { it.isNotEmpty() }

    override fun currentWorkingDirectory(): String? = memScoped {
        val buffer = allocArray<ByteVar>(READ_BUFFER_SIZE)
        getcwd(buffer, READ_BUFFER_SIZE.toULong())?.toKString()
    }?.trim()?.takeIf { it.isNotEmpty() }

    override fun currentExecutablePath(): String? {
        val bundlePath = NSBundle.mainBundle.executablePath?.trim().orEmpty()
        if (bundlePath.isNotEmpty()) {
            return canonicalizePath(bundlePath) ?: bundlePath
        }

        val arguments = NSProcessInfo.processInfo.arguments
        val argv0 = (arguments.firstOrNull() as? String)?.trim().orEmpty()
        if (argv0.isEmpty()) {
            return null
        }
        if (argv0.startsWith("/")) {
            return canonicalizePath(argv0) ?: argv0
        }
        if (argv0.contains('/')) {
            val cwd = currentWorkingDirectory() ?: return canonicalizePath(argv0) ?: argv0
            return canonicalizePath("$cwd/$argv0") ?: "$cwd/$argv0"
        }

        val path = pathEnvironment().orEmpty()
            .split(':')
            .firstNotNullOfOrNull { directory ->
                val normalized = directory.trim().ifEmpty { return@firstNotNullOfOrNull null }
                val candidate = "${normalized.trimEnd('/')}/$argv0"
                if (pathExists(candidate) && isExecutable(candidate)) {
                    canonicalizePath(candidate) ?: candidate
                } else {
                    null
                }
            }
        return path ?: argv0
    }

    override fun pathEnvironment(): String? = readEnvironmentVariable("PATH")

    override fun timeZoneId(): String? {
        val configuredTimeZone = readEnvironmentVariable("TZ")
            ?.trim()
            ?.takeIf { value -> value.isNotEmpty() }
        if (configuredTimeZone != null) {
            return configuredTimeZone
        }
        return systemTimeZoneId()
            ?.trim()
            ?.takeIf { value -> value.isNotEmpty() }
    }

    override fun changeWorkingDirectory(path: String) {
        require(path.isNotBlank()) {
            "Working directory must not be blank."
        }
        val result = chdir(path)
        if (result != 0) {
            throw IllegalStateException("Unable to change working directory to '$path'.")
        }
    }

    protected open fun readEnvironmentVariable(name: String): String? = getenv(name)?.toKString()

    protected open fun systemTimeZoneId(): String? = runCatching { TimeZone.currentSystemDefault().id }.getOrNull()

    private fun canonicalizePath(path: String): String? = memScoped {
        val resolved = realpath(path, null) ?: return null
        try {
            resolved.toKString()
        } finally {
            platform.posix.free(resolved)
        }
    }

    private fun pathExists(path: String): Boolean = access(path, F_OK.convert()) == 0

    private fun isExecutable(path: String): Boolean = access(path, X_OK.convert()) == 0

    companion object {
        private const val READ_BUFFER_SIZE = 4096
    }
}
