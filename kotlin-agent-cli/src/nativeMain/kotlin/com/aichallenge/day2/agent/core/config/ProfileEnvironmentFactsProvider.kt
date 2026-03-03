package com.aichallenge.day2.agent.core.config

import com.aichallenge.day2.agent.domain.model.ProfileEnvironmentFacts
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import platform.posix.getcwd
import platform.posix.getenv

open class ProfileEnvironmentFactsProvider {
    @OptIn(ExperimentalNativeApi::class)
    open fun read(): ProfileEnvironmentFacts {
        val timezone = readEnvironmentVariable("TZ").orEmpty().trim().ifEmpty { "unknown" }
        val os = Platform.osFamily.name.trim().ifEmpty { "unknown" }
        val repoPath = currentDirectory().orEmpty().trim().ifEmpty { "unknown" }
        return ProfileEnvironmentFacts(
            timezone = timezone,
            os = os,
            repoPath = repoPath,
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readEnvironmentVariable(name: String): String? = getenv(name)?.toKString()

    @OptIn(ExperimentalForeignApi::class)
    private fun currentDirectory(): String? = memScoped {
        val buffer = allocArray<ByteVar>(READ_BUFFER_SIZE)
        getcwd(buffer, READ_BUFFER_SIZE.toULong())?.toKString()
    }

    companion object {
        private const val READ_BUFFER_SIZE = 4096
    }
}
