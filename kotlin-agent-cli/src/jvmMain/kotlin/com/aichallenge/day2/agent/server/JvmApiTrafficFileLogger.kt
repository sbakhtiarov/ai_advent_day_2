package com.aichallenge.day2.agent.server

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

class JvmApiTrafficFileLogger(
    filePath: String,
) {
    private val path = Path.of(filePath)
    private val exchangeCounter = AtomicLong(1)

    init {
        path.parent?.let(Files::createDirectories)
        if (!Files.exists(path)) {
            Files.createFile(path)
        }
    }

    fun reserveExchangeId(): Long = exchangeCounter.getAndIncrement()

    fun logRequest(
        exchangeId: Long,
        method: String,
        url: String,
        headers: List<Pair<String, String>>,
        body: String,
    ) {
        append(
            buildString {
                appendLine("[$exchangeId] ${Instant.now()} REQUEST $method $url")
                headers.forEach { (name, value) ->
                    val normalizedValue = if (name.equals("Authorization", ignoreCase = true)) {
                        "<redacted>"
                    } else {
                        value
                    }
                    appendLine("$name: $normalizedValue")
                }
                appendLine()
                appendLine(body)
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
        append(
            buildString {
                appendLine("[$exchangeId] ${Instant.now()} RESPONSE $statusCode $statusDescription")
                headers.forEach { (name, value) ->
                    appendLine("$name: $value")
                }
                appendLine()
                appendLine(body)
                appendLine()
            },
        )
    }

    fun logFailure(exchangeId: Long, throwable: Throwable) {
        append(
            "[$exchangeId] ${Instant.now()} FAILURE ${throwable::class.simpleName}: ${throwable.message.orEmpty()}\n\n",
        )
    }

    private fun append(text: String) {
        Files.writeString(
            path,
            text,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }
}
