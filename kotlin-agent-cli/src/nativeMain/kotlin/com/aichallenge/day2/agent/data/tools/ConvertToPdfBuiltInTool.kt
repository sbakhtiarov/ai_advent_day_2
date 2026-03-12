@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.core.config.AppRuntimeEnvironment
import com.aichallenge.day2.agent.core.config.DefaultAppRuntimeEnvironment
import com.aichallenge.day2.agent.domain.model.PrivateToolResult
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.F_OK
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.access
import platform.posix.close
import platform.posix.errno
import platform.posix.mkdir
import platform.posix.mode_t
import platform.posix.open
import platform.posix.read
import platform.posix.write

private const val CONVERT_TO_PDF_TOOL_ID = "convert_to_pdf"
private const val REPORTLAB_MISSING_MARKER = "__REPORTLAB_MISSING__"
private const val PDF_BACKEND_NAME = "python_reportlab"

fun convertToPdfToolRegistration(
    commandExecutor: CommandExecutor = PosixCommandExecutor(),
    runtimeEnvironment: AppRuntimeEnvironment = DefaultAppRuntimeEnvironment(),
): BuiltInToolRegistration {
    return BuiltInToolRegistration(
        definition = BuiltInToolDefinition(
            toolId = CONVERT_TO_PDF_TOOL_ID,
            modelToolName = CONVERT_TO_PDF_TOOL_ID,
            description = "Convert a workspace text or markdown file to PDF. Use this when the user asks for an existing file to be exported as PDF.",
            parametersSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "input_file",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Source file path. Relative paths are resolved from the current workspace root; absolute paths must still be inside the workspace.")
                            },
                        )
                        put(
                            "output_file",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Destination PDF file path. Relative paths are resolved from the current workspace root; absolute paths must still be inside the workspace.")
                            },
                        )
                        put(
                            "overwrite",
                            buildJsonObject {
                                put("type", "boolean")
                                put("description", "When true, replace output_file if it already exists. Defaults to false.")
                            },
                        )
                    },
                )
                put(
                    "required",
                    buildJsonArray {
                        add(JsonPrimitive("input_file"))
                        add(JsonPrimitive("output_file"))
                    },
                )
                put("additionalProperties", false)
            },
        ),
        executor = ConvertToPdfBuiltInToolExecutor(
            commandExecutor = commandExecutor,
            runtimeEnvironment = runtimeEnvironment,
        ),
    )
}

class ConvertToPdfBuiltInToolExecutor(
    private val commandExecutor: CommandExecutor,
    private val runtimeEnvironment: AppRuntimeEnvironment,
) : BuiltInToolExecutor {
    override suspend fun execute(arguments: JsonObject): PrivateToolResult {
        val inputFile = requireStringArgument(arguments, "input_file")
        val outputFile = requireStringArgument(arguments, "output_file")
        val overwrite = optionalBooleanArgument(arguments, "overwrite") ?: false

        val workspaceRoot = resolveWorkspaceRoot()
        val absoluteInputPath = resolveAbsoluteTargetPath(workspaceRoot = workspaceRoot, path = inputFile)
        val absoluteOutputPath = resolveAbsoluteTargetPath(workspaceRoot = workspaceRoot, path = outputFile)

        if (!isPathInsideWorkspace(workspaceRoot = workspaceRoot, path = absoluteInputPath)) {
            throw IllegalArgumentException(
                "Argument 'input_file' must resolve to a path inside workspace '$workspaceRoot'.",
            )
        }
        if (!isPathInsideWorkspace(workspaceRoot = workspaceRoot, path = absoluteOutputPath)) {
            throw IllegalArgumentException(
                "Argument 'output_file' must resolve to a path inside workspace '$workspaceRoot'.",
            )
        }
        if (!fileExists(absoluteInputPath)) {
            throw IllegalArgumentException("Input file '$absoluteInputPath' does not exist.")
        }
        validateUtf8TextFile(absoluteInputPath)

        val outputExisted = fileExists(absoluteOutputPath)
        if (outputExisted && !overwrite) {
            throw IllegalArgumentException(
                "File '$absoluteOutputPath' already exists. Set overwrite=true to replace it.",
            )
        }

        ensureParentDirectoryExists(absoluteOutputPath)
        runConverterBackend(
            absoluteInputPath = absoluteInputPath,
            absoluteOutputPath = absoluteOutputPath,
            renderMode = if (isMarkdownPath(absoluteInputPath)) "markdown" else "text",
        )

        if (!fileExists(absoluteOutputPath)) {
            throw IllegalStateException("PDF conversion completed but output '$absoluteOutputPath' was not created.")
        }
        normalizePdfOutput(absoluteOutputPath)

        return PrivateToolResult(
            isError = false,
            content = textContent("Converted '$absoluteInputPath' to '$absoluteOutputPath'."),
            structuredContent = buildJsonObject {
                put("input_file", inputFile)
                put("output_file", outputFile)
                put("absolute_input_path", absoluteInputPath)
                put("absolute_output_path", absoluteOutputPath)
                put("overwritten", outputExisted)
                put("workspace_root", workspaceRoot)
                put("backend", PDF_BACKEND_NAME)
            },
        )
    }

    private fun requireStringArgument(arguments: JsonObject, name: String): String {
        val primitive = arguments[name] as? JsonPrimitive
            ?: throw IllegalArgumentException("Argument '$name' must be a non-blank string.")
        val value = primitive.strictStringValue()
            ?: throw IllegalArgumentException("Argument '$name' must be a non-blank string.")
        return value.trim().takeIf { normalized -> normalized.isNotEmpty() }
            ?: throw IllegalArgumentException("Argument '$name' must be a non-blank string.")
    }

    private fun JsonPrimitive.strictStringValue(): String? {
        val raw = toString()
        if (!(raw.length >= 2 && raw.first() == '"' && raw.last() == '"')) {
            return null
        }
        return contentOrNull
    }

    private fun optionalBooleanArgument(arguments: JsonObject, name: String): Boolean? {
        val value = arguments[name] ?: return null
        val primitive = value as? JsonPrimitive
            ?: throw IllegalArgumentException("Argument '$name' must be a boolean.")
        return primitive.booleanOrNull
            ?: throw IllegalArgumentException("Argument '$name' must be a boolean.")
    }

    private fun resolveWorkspaceRoot(): String {
        val cwd = runtimeEnvironment.currentWorkingDirectory()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Unable to determine current working directory for convert_to_pdf.")
        if (!cwd.startsWith("/")) {
            throw IllegalStateException("Current working directory must be an absolute path.")
        }
        return normalizeAbsolutePath(cwd)
    }

    private fun resolveAbsoluteTargetPath(
        workspaceRoot: String,
        path: String,
    ): String {
        val rawPath = if (path.startsWith("/")) {
            path
        } else {
            if (workspaceRoot == "/") {
                "/$path"
            } else {
                "${workspaceRoot.trimEnd('/')}/$path"
            }
        }
        return normalizeAbsolutePath(rawPath)
    }

    private fun normalizeAbsolutePath(path: String): String {
        require(path.startsWith("/")) {
            "Path must be absolute."
        }
        val segments = mutableListOf<String>()
        path.split('/').forEach { segment ->
            when (segment) {
                "",
                ".",
                -> Unit

                ".." -> if (segments.isNotEmpty()) {
                    segments.removeAt(segments.lastIndex)
                }

                else -> segments += segment
            }
        }
        return if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")
    }

    private fun isPathInsideWorkspace(workspaceRoot: String, path: String): Boolean {
        if (workspaceRoot == "/") {
            return true
        }
        return path == workspaceRoot || path.startsWith("$workspaceRoot/")
    }

    private fun ensureParentDirectoryExists(path: String) {
        val parent = parentDirectory(path) ?: return
        ensureDirectoryExists(parent)
    }

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
        throw IllegalStateException("Unable to create directory '$path'.")
    }

    private fun parentDirectory(path: String): String? {
        if (path.isBlank() || path == "/") return null
        val normalized = path.trimEnd('/')
        val separatorIndex = normalized.lastIndexOf('/')
        if (separatorIndex < 0) return null
        return if (separatorIndex == 0) "/" else normalized.substring(0, separatorIndex)
    }

    private fun fileExists(path: String): Boolean {
        return access(path, F_OK.convert()) == 0
    }

    private fun validateUtf8TextFile(path: String) {
        val bytes = readFileBytes(path)
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Input file '$path' must be valid UTF-8 text.")
        }
    }

    private fun readFileBytes(path: String): ByteArray {
        val fd = open(path, O_RDONLY)
        if (fd < 0) {
            throw IllegalArgumentException("Input file '$path' could not be opened for reading.")
        }
        try {
            val chunks = mutableListOf<ByteArray>()
            while (true) {
                val chunk = ByteArray(READ_BUFFER_SIZE)
                val bytesRead = chunk.usePinned { pinned ->
                    read(fd, pinned.addressOf(0), chunk.size.convert())
                }
                when {
                    bytesRead > 0 -> chunks += chunk.copyOf(bytesRead.toInt())
                    bytesRead == 0L -> break
                    bytesRead < 0 && errno == EINTR -> continue
                    else -> throw IllegalArgumentException("Input file '$path' could not be read as text.")
                }
            }
            if (chunks.isEmpty()) {
                return ByteArray(0)
            }
            val result = ByteArray(chunks.sumOf { it.size })
            var offset = 0
            chunks.forEach { chunk ->
                chunk.copyInto(result, destinationOffset = offset)
                offset += chunk.size
            }
            return result
        } finally {
            close(fd)
        }
    }

    private fun isMarkdownPath(path: String): Boolean {
        val lowercasePath = path.lowercase()
        return lowercasePath.endsWith(".md") || lowercasePath.endsWith(".markdown")
    }

    private fun normalizePdfOutput(path: String) {
        val outputBytes = readFileBytes(path)
        if (outputBytes.hasPdfHeader()) {
            return
        }
        val decoded = decodeBase64PdfPayload(outputBytes)
            ?: throw IllegalStateException("PDF conversion did not produce a valid binary PDF at '$path'.")
        if (!decoded.hasPdfHeader()) {
            throw IllegalStateException("PDF conversion did not produce a valid binary PDF at '$path'.")
        }
        writeBytesToFile(path, decoded)
    }

    private fun decodeBase64PdfPayload(bytes: ByteArray): ByteArray? {
        val raw = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val payload = raw.substringAfter("base64,", missingDelimiterValue = raw)
        val compact = payload.filterNot(Char::isWhitespace)
        if (compact.isEmpty()) {
            return null
        }
        val standardAlphabet = compact
            .replace('-', '+')
            .replace('_', '/')

        val normalized = when (standardAlphabet.length % 4) {
            0 -> standardAlphabet
            2 -> "$standardAlphabet=="
            3 -> "$standardAlphabet="
            else -> return null
        }
        val decoded = decodeBase64(normalized) ?: return null
        return decoded.takeIf { payload -> payload.hasPdfHeader() }
    }

    private fun decodeBase64(value: String): ByteArray? {
        val out = ByteArray((value.length / 4) * 3)
        var outIndex = 0
        var index = 0
        while (index < value.length) {
            val c0 = value[index]
            val c1 = value[index + 1]
            val c2 = value[index + 2]
            val c3 = value[index + 3]

            val b0 = base64Value(c0) ?: return null
            val b1 = base64Value(c1) ?: return null
            val b2 = if (c2 == '=') null else base64Value(c2) ?: return null
            val b3 = if (c3 == '=') null else base64Value(c3) ?: return null

            if (c2 == '=' && c3 != '=') {
                return null
            }
            if (c2 != '=' && c3 == '=') {
                out[outIndex++] = ((b0 shl 2) or (b1 shr 4)).toByte()
                out[outIndex++] = (((b1 and 0x0f) shl 4) or ((b2 ?: return null) shr 2)).toByte()
                index += 4
                continue
            }
            if (c2 == '=' && c3 == '=') {
                out[outIndex++] = ((b0 shl 2) or (b1 shr 4)).toByte()
                index += 4
                continue
            }

            out[outIndex++] = ((b0 shl 2) or (b1 shr 4)).toByte()
            out[outIndex++] = (((b1 and 0x0f) shl 4) or ((b2 ?: return null) shr 2)).toByte()
            out[outIndex++] = ((((b2 and 0x03) shl 6) or (b3 ?: return null))).toByte()
            index += 4
        }
        return out.copyOf(outIndex)
    }

    private fun base64Value(char: Char): Int? {
        return when (char) {
            in 'A'..'Z' -> char.code - 'A'.code
            in 'a'..'z' -> char.code - 'a'.code + 26
            in '0'..'9' -> char.code - '0'.code + 52
            '+' -> 62
            '/' -> 63
            else -> null
        }
    }

    private fun ByteArray.hasPdfHeader(): Boolean {
        val prefix = "%PDF-".encodeToByteArray()
        if (size < prefix.size) {
            return false
        }
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) {
                return false
            }
        }
        return true
    }

    private fun writeBytesToFile(path: String, bytes: ByteArray) {
        val fd = open(
            path,
            O_WRONLY or O_CREAT or O_TRUNC,
            FILE_MODE.convert<mode_t>(),
        )
        if (fd < 0) {
            throw IllegalStateException("Unable to open '$path' for writing.")
        }
        try {
            var offset = 0
            while (offset < bytes.size) {
                val written = bytes.usePinned { pinned ->
                    write(fd, pinned.addressOf(offset), (bytes.size - offset).convert())
                }
                when {
                    written > 0 -> offset += written.toInt()
                    written < 0 && errno == EINTR -> continue
                    else -> throw IllegalStateException("Unable to write file '$path'.")
                }
            }
        } finally {
            close(fd)
        }
    }

    private fun runConverterBackend(
        absoluteInputPath: String,
        absoluteOutputPath: String,
        renderMode: String,
    ) {
        val result = commandExecutor.execute(
            command = "python3",
            args = listOf(
                "-c",
                PYTHON_CONVERTER_SCRIPT,
                absoluteInputPath,
                absoluteOutputPath,
                renderMode,
            ),
        )

        if (result.exitCode == 0) {
            return
        }

        val stderr = result.stderr.trim()
        val stdout = result.stdout.trim()
        if (stderr.contains(REPORTLAB_MISSING_MARKER) || stdout.contains(REPORTLAB_MISSING_MARKER)) {
            throw IllegalStateException(
                "Python package 'reportlab' is required for convert_to_pdf. Install it with: python3 -m pip install reportlab",
            )
        }

        val details = listOfNotNull(
            stderr.takeIf { it.isNotEmpty() }?.let { value -> "stderr: $value" },
            stdout.takeIf { it.isNotEmpty() }?.let { value -> "stdout: $value" },
        ).joinToString(" | ")
        if (details.isNotEmpty()) {
            throw IllegalStateException("PDF conversion failed with exit code ${result.exitCode}. $details")
        }
        throw IllegalStateException("PDF conversion failed with exit code ${result.exitCode}.")
    }

    private fun textContent(text: String): JsonArray {
        return buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                },
            )
        }
    }

    companion object {
        private const val DIRECTORY_MODE = 493 // 0755
        private const val FILE_MODE = 420 // 0644
        private const val READ_BUFFER_SIZE = 8192
        private val PYTHON_CONVERTER_SCRIPT =
            """
            import html
            import re
            import sys

            REPORTLAB_MISSING_MARKER = "__REPORTLAB_MISSING__"

            try:
                from reportlab.lib.pagesizes import LETTER
                from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
                from reportlab.lib.units import inch
                from reportlab.platypus import Paragraph, Preformatted, SimpleDocTemplate, Spacer
            except ModuleNotFoundError:
                sys.stderr.write(REPORTLAB_MISSING_MARKER + "\n")
                raise SystemExit(3)

            if len(sys.argv) != 4:
                sys.stderr.write("invalid-arguments\n")
                raise SystemExit(2)

            input_path = sys.argv[1]
            output_path = sys.argv[2]
            render_mode = sys.argv[3]

            with open(input_path, "r", encoding="utf-8", errors="strict") as handle:
                text = handle.read()

            styles = getSampleStyleSheet()
            body = ParagraphStyle(
                "BodyTextV1",
                parent=styles["BodyText"],
                fontName="Helvetica",
                fontSize=10,
                leading=14,
            )
            heading_one = ParagraphStyle(
                "HeadingOneV1",
                parent=styles["Heading1"],
                fontName="Helvetica-Bold",
                fontSize=18,
                leading=22,
                spaceAfter=8,
            )
            heading_two = ParagraphStyle(
                "HeadingTwoV1",
                parent=styles["Heading2"],
                fontName="Helvetica-Bold",
                fontSize=14,
                leading=18,
                spaceAfter=6,
            )
            heading_three = ParagraphStyle(
                "HeadingThreeV1",
                parent=styles["Heading3"],
                fontName="Helvetica-Bold",
                fontSize=12,
                leading=16,
                spaceAfter=4,
            )
            code = ParagraphStyle(
                "CodeV1",
                parent=styles["BodyText"],
                fontName="Courier",
                fontSize=9,
                leading=12,
            )

            story = []

            def append_paragraph(value, style):
                escaped = html.escape(value).replace("\t", "    ")
                if escaped.strip() == "":
                    story.append(Spacer(1, 0.10 * inch))
                    return
                story.append(Paragraph(escaped, style))
                story.append(Spacer(1, 0.07 * inch))

            if render_mode == "markdown":
                lines = text.splitlines()
                in_code_block = False
                code_lines = []
                paragraph_lines = []

                def flush_paragraph():
                    if not paragraph_lines:
                        return
                    append_paragraph(" ".join(paragraph_lines).strip(), body)
                    paragraph_lines.clear()

                def flush_code_block():
                    if not code_lines:
                        return
                    story.append(Preformatted("\n".join(code_lines), code))
                    story.append(Spacer(1, 0.10 * inch))
                    code_lines.clear()

                for line in lines:
                    if line.strip().startswith("```"):
                        flush_paragraph()
                        if in_code_block:
                            flush_code_block()
                            in_code_block = False
                        else:
                            in_code_block = True
                        continue

                    if in_code_block:
                        code_lines.append(line)
                        continue

                    stripped = line.strip()
                    if stripped == "":
                        flush_paragraph()
                        story.append(Spacer(1, 0.10 * inch))
                        continue

                    heading_match = re.match(r"^\s*(#{1,6})\s+(.+)", line)
                    if heading_match:
                        flush_paragraph()
                        level = len(heading_match.group(1))
                        heading_text = heading_match.group(2).strip()
                        if level == 1:
                            append_paragraph(heading_text, heading_one)
                        elif level == 2:
                            append_paragraph(heading_text, heading_two)
                        else:
                            append_paragraph(heading_text, heading_three)
                        continue

                    unordered_match = re.match(r"^\s*[-*+]\s+(.+)", line)
                    if unordered_match:
                        flush_paragraph()
                        append_paragraph("- " + unordered_match.group(1).strip(), body)
                        continue

                    ordered_match = re.match(r"^\s*(\d+)\.\s+(.+)", line)
                    if ordered_match:
                        flush_paragraph()
                        append_paragraph(ordered_match.group(1) + ". " + ordered_match.group(2).strip(), body)
                        continue

                    paragraph_lines.append(stripped)

                flush_paragraph()
                if in_code_block:
                    flush_code_block()
            else:
                paragraph_buffer = []
                for line in text.splitlines():
                    stripped = line.rstrip()
                    if stripped == "":
                        if paragraph_buffer:
                            append_paragraph(" ".join(paragraph_buffer), body)
                            paragraph_buffer = []
                        story.append(Spacer(1, 0.10 * inch))
                        continue
                    paragraph_buffer.append(stripped)
                if paragraph_buffer:
                    append_paragraph(" ".join(paragraph_buffer), body)

            if not story:
                story.append(Paragraph("", body))

            document = SimpleDocTemplate(
                output_path,
                pagesize=LETTER,
                leftMargin=0.75 * inch,
                rightMargin=0.75 * inch,
                topMargin=0.75 * inch,
                bottomMargin=0.75 * inch,
            )
            document.build(story)
            """.trimIndent()
    }
}
