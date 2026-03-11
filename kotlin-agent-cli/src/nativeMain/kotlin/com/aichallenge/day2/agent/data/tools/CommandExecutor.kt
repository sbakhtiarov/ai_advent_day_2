package com.aichallenge.day2.agent.data.tools

import kotlinx.cinterop.*
import kotlinx.io.IOException
import platform.posix.*

data class CommandExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

interface CommandExecutor {
    fun execute(command: String, args: List<String>): CommandExecutionResult
}

@OptIn(ExperimentalForeignApi::class)
class PosixCommandExecutor : CommandExecutor {
    override fun execute(command: String, args: List<String>): CommandExecutionResult {
        val stdoutPipe = createPipe("stdout")
        val stderrPipe = createPipe("stderr")
        val errorPipe = createPipe("error")

        try {
            val pid = fork()
            if (pid < 0) {
                throw IOException("Unable to fork command '$command': ${currentErrorMessage()}")
            }

            if (pid == 0) {
                launchChildProcess(
                    command = command,
                    args = args,
                    stdoutPipe = stdoutPipe,
                    stderrPipe = stderrPipe,
                    errorPipe = errorPipe,
                )
            }

            closeFdQuietly(stdoutPipe.writeEnd)
            closeFdQuietly(stderrPipe.writeEnd)
            closeFdQuietly(errorPipe.writeEnd)

            val childErrorCode = readChildErrorCode(errorPipe.readEnd)
            closeFdQuietly(errorPipe.readEnd)
            if (childErrorCode != null) {
                closeFdQuietly(stdoutPipe.readEnd)
                closeFdQuietly(stderrPipe.readEnd)
                waitpid(pid, null, 0)
                throw IOException("Unable to launch command '$command': ${errorMessage(childErrorCode)}")
            }

            memScoped {
                val status = alloc<IntVar>()
                if (waitpid(pid, status.ptr, 0) < 0) {
                    closeFdQuietly(stdoutPipe.readEnd)
                    closeFdQuietly(stderrPipe.readEnd)
                    throw IOException("Unable to wait for command '$command': ${currentErrorMessage()}")
                }
                val stdout = readAll(stdoutPipe.readEnd)
                val stderr = readAll(stderrPipe.readEnd)
                return CommandExecutionResult(
                    exitCode = exitCodeFromWaitStatus(status.value),
                    stdout = stdout,
                    stderr = stderr,
                )
            }
        } catch (throwable: Throwable) {
            closePipeQuietly(stdoutPipe)
            closePipeQuietly(stderrPipe)
            closePipeQuietly(errorPipe)
            throw throwable
        }
    }

    private fun launchChildProcess(
        command: String,
        args: List<String>,
        stdoutPipe: PipeEnds,
        stderrPipe: PipeEnds,
        errorPipe: PipeEnds,
    ): Nothing {
        closeFdQuietly(stdoutPipe.readEnd)
        closeFdQuietly(stderrPipe.readEnd)
        closeFdQuietly(errorPipe.readEnd)

        if (fcntl(errorPipe.writeEnd, F_SETFD, FD_CLOEXEC) == -1) {
            writeChildErrorAndExit(errorPipe.writeEnd, errno)
        }
        if (dup2(stdoutPipe.writeEnd, STDOUT_FILENO) == -1) {
            writeChildErrorAndExit(errorPipe.writeEnd, errno)
        }
        if (dup2(stderrPipe.writeEnd, STDERR_FILENO) == -1) {
            writeChildErrorAndExit(errorPipe.writeEnd, errno)
        }

        closeFdQuietly(stdoutPipe.writeEnd)
        closeFdQuietly(stderrPipe.writeEnd)

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
            val bytesRead = read(fd, errorCode.ptr, kotlinx.cinterop.sizeOf<IntVar>().convert())
            when {
                bytesRead == 0L -> return null
                bytesRead == kotlinx.cinterop.sizeOf<IntVar>().toLong() -> return errorCode.value
                bytesRead < 0 && errno == platform.posix.EINTR -> continue
                bytesRead < 0 -> return errno
                else -> return platform.posix.EIO
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
            val bytesWritten = write(fd, errorValue.ptr, kotlinx.cinterop.sizeOf<IntVar>().convert())
            if (bytesWritten == kotlinx.cinterop.sizeOf<IntVar>().toLong()) {
                break
            }
            if (bytesWritten < 0 && errno == platform.posix.EINTR) {
                continue
            }
            break
        }
    }
    _exit(127)
    error("unreachable")
}

@OptIn(ExperimentalForeignApi::class)
private fun readAll(fd: Int): String {
    val chunks = mutableListOf<ByteArray>()
    try {
        while (true) {
            val chunk = ByteArray(8 * 1024)
            val bytesRead = chunk.usePinned { pinned ->
                read(fd, pinned.addressOf(0), chunk.size.convert())
            }
            when {
                bytesRead > 0 -> chunks += chunk.copyOf(bytesRead.toInt())
                bytesRead == 0L -> break
                errno == platform.posix.EINTR -> continue
                else -> throw IOException("Failed to read command output: ${currentErrorMessage()}")
            }
        }
    } finally {
        closeFdQuietly(fd)
    }

    if (chunks.isEmpty()) {
        return ""
    }
    val totalBytes = chunks.sumOf(ByteArray::size)
    val buffer = ByteArray(totalBytes)
    var offset = 0
    chunks.forEach { chunk ->
        chunk.copyInto(buffer, destinationOffset = offset)
        offset += chunk.size
    }
    return buffer.decodeToString()
}

private fun exitCodeFromWaitStatus(status: Int): Int {
    val signal = status and 0x7f
    return if (signal == 0) {
        (status ushr 8) and 0xff
    } else {
        128 + signal
    }
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
        if (result == 0 || errno != platform.posix.EINTR) {
            return
        }
    }
}

private data class PipeEnds(
    val readEnd: Int,
    val writeEnd: Int,
)
