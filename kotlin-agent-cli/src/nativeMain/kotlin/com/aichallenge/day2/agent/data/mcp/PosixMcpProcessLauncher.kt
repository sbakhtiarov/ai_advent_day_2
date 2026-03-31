package com.aichallenge.day2.agent.data.mcp

import kotlinx.cinterop.*
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import platform.posix.*

internal interface McpProcessLauncher {
    fun launch(command: String, args: List<String>): ManagedMcpProcess
}

internal interface ManagedMcpProcess {
    val stdin: Sink
    val stdout: Source
    val stderr: Source
    fun close()
}

@OptIn(ExperimentalForeignApi::class)
internal class PosixMcpProcessLauncher(
    private val workingDirectory: String? = null,
) : McpProcessLauncher {
    override fun launch(command: String, args: List<String>): ManagedMcpProcess {
        val stdinPipe = createPipe("stdin")
        val stdoutPipe = createPipe("stdout")
        val stderrPipe = createPipe("stderr")
        val errorPipe = createPipe("error")

        try {
            val pid = fork()
            if (pid < 0) {
                throw IOException("Unable to fork MCP server '$command': ${currentErrorMessage()}")
            }

            if (pid == 0) {
                launchChildProcess(
                    command = command,
                    args = args,
                    stdinPipe = stdinPipe,
                    stdoutPipe = stdoutPipe,
                    stderrPipe = stderrPipe,
                    errorPipe = errorPipe,
                )
            }

            closeFdQuietly(stdinPipe.readEnd)
            closeFdQuietly(stdoutPipe.writeEnd)
            closeFdQuietly(stderrPipe.writeEnd)
            closeFdQuietly(errorPipe.writeEnd)

            val childErrorCode = readChildErrorCode(errorPipe.readEnd)
            closeFdQuietly(errorPipe.readEnd)
            if (childErrorCode != null) {
                closeFdQuietly(stdinPipe.writeEnd)
                closeFdQuietly(stdoutPipe.readEnd)
                closeFdQuietly(stderrPipe.readEnd)
                waitpid(pid, null, 0)
                throw IOException("Unable to launch MCP server '$command': ${errorMessage(childErrorCode)}")
            }

            return PosixManagedMcpProcess(
                pid = pid,
                stdinFd = stdinPipe.writeEnd,
                stdoutFd = stdoutPipe.readEnd,
                stderrFd = stderrPipe.readEnd,
            )
        } catch (throwable: Throwable) {
            closePipeQuietly(stdinPipe)
            closePipeQuietly(stdoutPipe)
            closePipeQuietly(stderrPipe)
            closePipeQuietly(errorPipe)
            throw throwable
        }
    }

    private fun launchChildProcess(
        command: String,
        args: List<String>,
        stdinPipe: PipeEnds,
        stdoutPipe: PipeEnds,
        stderrPipe: PipeEnds,
        errorPipe: PipeEnds,
    ): Nothing {
        closeFdQuietly(stdinPipe.writeEnd)
        closeFdQuietly(stdoutPipe.readEnd)
        closeFdQuietly(stderrPipe.readEnd)
        closeFdQuietly(errorPipe.readEnd)

        if (fcntl(errorPipe.writeEnd, F_SETFD, FD_CLOEXEC) == -1) {
            writeChildErrorAndExit(errorPipe.writeEnd, errno)
        }
        if (dup2(stdinPipe.readEnd, STDIN_FILENO) == -1) {
            writeChildErrorAndExit(errorPipe.writeEnd, errno)
        }
        if (dup2(stdoutPipe.writeEnd, STDOUT_FILENO) == -1) {
            writeChildErrorAndExit(errorPipe.writeEnd, errno)
        }
        if (dup2(stderrPipe.writeEnd, STDERR_FILENO) == -1) {
            writeChildErrorAndExit(errorPipe.writeEnd, errno)
        }

        closeFdQuietly(stdinPipe.readEnd)
        closeFdQuietly(stdoutPipe.writeEnd)
        closeFdQuietly(stderrPipe.writeEnd)

        workingDirectory?.let { targetDirectory ->
            if (chdir(targetDirectory) != 0) {
                writeChildErrorAndExit(errorPipe.writeEnd, errno)
            }
        }

        memScoped {
            val allArgs = listOf(command) + args
            val argv = allocArray<CPointerVar<ByteVar>>(allArgs.size + 1)
            allArgs.forEachIndexed { index, value ->
                argv[index] = value.cstr.getPointer(this)
            }
            argv[allArgs.size] = null

            execvp(command, argv)
            writeChildErrorAndExit(errorPipe.writeEnd, errno)
        }
    }
}

internal class PosixManagedMcpProcess(
    private val pid: Int,
    stdinFd: Int,
    stdoutFd: Int,
    stderrFd: Int,
) : ManagedMcpProcess {
    override val stdin: Sink = FileDescriptorRawSink(stdinFd).buffered()
    override val stdout: Source = FileDescriptorRawSource(stdoutFd).buffered()
    override val stderr: Source = FileDescriptorRawSource(stderrFd).buffered()

    private var closed = false

    override fun close() {
        if (closed) {
            return
        }
        closed = true

        runCatching { stdin.close() }
        runCatching { stdout.close() }
        runCatching { stderr.close() }

        if (waitForExit(timeoutMillis = CLOSE_GRACE_PERIOD_MS)) {
            return
        }

        if (kill(pid, SIGTERM) != 0 && errno != ESRCH) {
            return
        }
        if (waitForExit(timeoutMillis = TERMINATE_GRACE_PERIOD_MS)) {
            return
        }

        if (kill(pid, SIGKILL) != 0 && errno != ESRCH) {
            return
        }
        waitForExit(timeoutMillis = TERMINATE_GRACE_PERIOD_MS)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun waitForExit(timeoutMillis: Int): Boolean {
        memScoped {
            val status = alloc<IntVar>()
            repeat((timeoutMillis / POLL_INTERVAL_MS).coerceAtLeast(1)) {
                when (waitpid(pid, status.ptr, WNOHANG)) {
                    pid -> return true
                    0 -> usleep((POLL_INTERVAL_MS * 1_000).convert())
                    -1 -> return errno == ECHILD
                }
            }
        }
        return false
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class FileDescriptorRawSource(
    private var fd: Int,
) : RawSource {
    private var closed = false

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        checkNotClosed()
        require(byteCount >= 0L) { "byteCount: $byteCount" }
        if (byteCount == 0L) {
            return 0L
        }

        val buffer = ByteArray(minOf(byteCount, FD_IO_CHUNK_SIZE.toLong()).toInt())
        while (true) {
            val bytesRead = buffer.usePinned { pinned ->
                read(fd, pinned.addressOf(0), buffer.size.convert())
            }
            when {
                bytesRead > 0 -> {
                    sink.write(buffer, 0, bytesRead.toInt())
                    return bytesRead
                }

                bytesRead == 0L -> return -1L
                errno == EINTR -> continue
                else -> throw IOException("Failed to read from file descriptor $fd: ${currentErrorMessage()}")
            }
        }
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        closeFdQuietly(fd)
        fd = -1
    }

    private fun checkNotClosed() {
        check(!closed) { "Source is closed." }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class FileDescriptorRawSink(
    private var fd: Int,
) : RawSink {
    private var closed = false

    override fun write(source: Buffer, byteCount: Long) {
        checkNotClosed()
        require(byteCount >= 0L) { "byteCount: $byteCount" }
        var remaining = byteCount
        val buffer = ByteArray(FD_IO_CHUNK_SIZE)

        while (remaining > 0L) {
            val bytesRead = source.readAtMostTo(
                sink = buffer,
                startIndex = 0,
                endIndex = minOf(buffer.size.toLong(), remaining).toInt(),
            )
            if (bytesRead <= 0) {
                throw IOException("Unexpected end of buffer while writing to file descriptor $fd.")
            }

            var offset = 0
            while (offset < bytesRead) {
                val bytesWritten = buffer.usePinned { pinned ->
                    write(
                        fd,
                        pinned.addressOf(offset),
                        (bytesRead - offset).convert(),
                    )
                }
                when {
                    bytesWritten > 0 -> offset += bytesWritten.toInt()
                    errno == EINTR -> continue
                    else -> throw IOException("Failed to write to file descriptor $fd: ${currentErrorMessage()}")
                }
            }

            remaining -= bytesRead.toLong()
        }
    }

    override fun flush() = Unit

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        closeFdQuietly(fd)
        fd = -1
    }

    private fun checkNotClosed() {
        check(!closed) { "Sink is closed." }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun createPipe(name: String): PipeEnds {
    memScoped {
        val fds = allocArray<IntVar>(2)
        if (pipe(fds) != 0) {
            throw IOException("Unable to create $name pipe: ${currentErrorMessage()}")
        }
        return PipeEnds(
            readEnd = fds[0],
            writeEnd = fds[1],
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readChildErrorCode(fd: Int): Int? {
    memScoped {
        val errorCode = alloc<IntVar>()
        while (true) {
            val bytesRead = read(fd, errorCode.ptr, sizeOf<IntVar>().convert())
            when {
                bytesRead == 0L -> return null
                bytesRead == sizeOf<IntVar>().toLong() -> return errorCode.value
                bytesRead < 0 && errno == EINTR -> continue
                bytesRead < 0 -> return errno
                else -> return EIO
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeChildErrorAndExit(fd: Int, errorCode: Int): Nothing {
    memScoped {
        val errorValue = alloc<IntVar>()
        errorValue.value = errorCode
        while (true) {
            val bytesWritten = write(fd, errorValue.ptr, sizeOf<IntVar>().convert())
            if (bytesWritten == sizeOf<IntVar>().toLong()) {
                break
            }
            if (bytesWritten < 0 && errno == EINTR) {
                continue
            }
            break
        }
    }
    _exit(CHILD_PROCESS_EXIT_CODE)
    error("unreachable")
}

@OptIn(ExperimentalForeignApi::class)
private fun errorMessage(code: Int): String {
    return strerror(code)?.toKString()?.trim().takeUnless { it.isNullOrEmpty() } ?: "error code $code"
}

private fun currentErrorMessage(): String = errorMessage(errno)

@OptIn(ExperimentalForeignApi::class)
private fun closePipeQuietly(pipe: PipeEnds) {
    closeFdQuietly(pipe.readEnd)
    closeFdQuietly(pipe.writeEnd)
}

@OptIn(ExperimentalForeignApi::class)
private fun closeFdQuietly(fd: Int) {
    if (fd < 0) {
        return
    }
    while (true) {
        val result = close(fd)
        if (result == 0 || errno != EINTR) {
            return
        }
    }
}

private data class PipeEnds(
    val readEnd: Int,
    val writeEnd: Int,
)

private const val FD_IO_CHUNK_SIZE = 8 * 1024
private const val CLOSE_GRACE_PERIOD_MS = 100
private const val TERMINATE_GRACE_PERIOD_MS = 1_000
private const val POLL_INTERVAL_MS = 50
private const val SIGTERM = 15
private const val SIGKILL = 9
private const val CHILD_PROCESS_EXIT_CODE = 127
