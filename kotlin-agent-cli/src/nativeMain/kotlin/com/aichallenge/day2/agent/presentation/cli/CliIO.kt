package com.aichallenge.day2.agent.presentation.cli

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.TIOCGWINSZ
import platform.posix.fgets
import platform.posix.fputs
import platform.posix.fflush
import platform.posix.ioctl
import platform.posix.pclose
import platform.posix.popen
import platform.posix.read
import platform.posix.stdout
import platform.posix.system
import platform.posix.winsize

enum class OutputFormatOption {
    PLAIN_TEXT,
    MARKDOWN,
    JSON,
    TABLE,
}

data class ConfigMenuSelection(
    val format: OutputFormatOption,
    val maxOutputTokens: Int?,
    val stopSequence: String,
) {
    companion object {
        fun default(): ConfigMenuSelection = ConfigMenuSelection(
            format = OutputFormatOption.PLAIN_TEXT,
            maxOutputTokens = null,
            stopSequence = "",
        )
    }
}

interface CliIO {
    fun clearScreen()
    fun hideCursor()
    fun showCursor()
    fun writeLine(text: String = "")
    fun readLine(prompt: String): String?
    fun readLineInFooter(prompt: String, divider: String, systemPromptText: String): String?
    fun showThinkingIndicator() {}
    fun updateThinkingIndicator(progressText: String) {}
    fun hideThinkingIndicator() {}
    fun openConfigMenu(
        tabs: List<String>,
        descriptions: List<String>,
        currentSelection: ConfigMenuSelection,
    ): ConfigMenuSelection
}

object StdCliIO : CliIO {
    private var thinkingIndicatorVisible = false

    override fun clearScreen() {
        // ANSI escape sequence: clear screen and move cursor to top-left.
        print("\u001B[2J\u001B[H")
    }

    override fun hideCursor() {
        print("\u001B[?25l")
    }

    override fun showCursor() {
        print("\u001B[?25h")
    }

    override fun writeLine(text: String) {
        println(text)
    }

    override fun readLine(prompt: String): String? {
        print(prompt)
        return readlnOrNull()
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun showThinkingIndicator() {
        if (thinkingIndicatorVisible) {
            return
        }

        print("\r\n")
        thinkingIndicatorVisible = true
        updateThinkingIndicator(progressText = "")
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun updateThinkingIndicator(progressText: String) {
        if (!thinkingIndicatorVisible) {
            return
        }

        val suffix = progressText.trim().takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
        print('\r')
        print("\u001B[2K")
        print("  ${THINKING_LABEL_COLOR}Thinking...$suffix${ANSI_RESET}")
        fflush(stdout)
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun hideThinkingIndicator() {
        if (!thinkingIndicatorVisible) {
            return
        }

        print('\r')
        print("\u001B[2K")
        print("\u001B[1A")
        print('\r')
        fflush(stdout)
        thinkingIndicatorVisible = false
    }

    override fun readLineInFooter(prompt: String, divider: String, systemPromptText: String): String? {
        val input = StringBuilder()
        val pendingKeys = ArrayDeque<Int>()
        val terminalWidth = detectTerminalWidth().coerceAtLeast(prompt.length + 1)
        val dividerChar = divider.firstOrNull() ?: '─'
        val dividerLine = dividerChar.toString().repeat(terminalWidth)
        val coloredDividerLine = colorizeDivider(dividerLine)
        val systemLine = formatSystemPromptLine(systemPromptText)

        // Initial footer render:
        // divider
        // > input (cursor stays here)
        // divider
        print(coloredDividerLine)
        print('\n')
        val systemLineVisualLines = calculateVisualLineCount(stripAnsi(systemLine).length, terminalWidth)
        val reservedFooterLines = FOOTER_RESERVED_INPUT_LINES + 1 + systemLineVisualLines
        ensureMenuFits(requiredMenuLines = reservedFooterLines)
        print("\u001B7")
        redrawFooterFromPromptAnchor(
            prompt = prompt,
            input = input,
            divider = coloredDividerLine,
            width = terminalWidth,
            systemLine = systemLine,
        )

        return withRawInput<String?> {
            var result: String? = null

            loop@ while (true) {
                val key = if (pendingKeys.isNotEmpty()) pendingKeys.removeFirst() else readByte()
                when (key) {
                    null -> {
                        result = null
                        break@loop
                    }

                    ENTER_CR, ENTER_LF -> {
                        val nextKey = readOptionalByte(timeoutDeciseconds = PASTE_ENTER_LOOKAHEAD_DECISECONDS)
                        if (nextKey != null) {
                            if (key == ENTER_CR && nextKey == ENTER_LF) {
                                val afterLf = readOptionalByte(timeoutDeciseconds = PASTE_ENTER_LOOKAHEAD_DECISECONDS)
                                if (afterLf == null) {
                                    // Treat an isolated CRLF as a regular Enter key press.
                                    print("\u001B8")
                                    print('\r')
                                    print("\u001B[J")
                                    print(prompt)
                                    print(input.toString())
                                    print('\n')
                                    print(coloredDividerLine)
                                    result = input.toString()
                                    break@loop
                                }
                                input.append('\n')
                                pendingKeys.addLast(afterLf)
                            } else {
                                input.append('\n')
                                pendingKeys.addLast(nextKey)
                            }
                            redrawFooterFromPromptAnchor(
                                prompt = prompt,
                                input = input,
                                divider = coloredDividerLine,
                                width = terminalWidth,
                                systemLine = systemLine,
                            )
                            continue@loop
                        }

                        print("\u001B8")
                        print('\r')
                        print("\u001B[J")
                        print(prompt)
                        print(input.toString())
                        print('\n')
                        print(coloredDividerLine)
                        result = input.toString()
                        break@loop
                    }

                    BACKSPACE, DELETE -> {
                        if (input.isNotEmpty()) {
                            input.deleteAt(input.lastIndex)
                            redrawFooterFromPromptAnchor(
                                prompt = prompt,
                                input = input,
                                divider = coloredDividerLine,
                                width = terminalWidth,
                                systemLine = systemLine,
                            )
                        }
                    }

                    ESCAPE -> {
                        val escNext = readOptionalByte(timeoutDeciseconds = 1)
                        if (escNext == CSI) {
                            val csiFirst = readOptionalByte(timeoutDeciseconds = 1) ?: continue@loop
                            val pasted = readBracketedPasteIfPresent(csiFirst)
                            if (!pasted.isNullOrEmpty()) {
                                input.append(sanitizeMultilineInput(pasted))
                                redrawFooterFromPromptAnchor(
                                    prompt = prompt,
                                    input = input,
                                    divider = coloredDividerLine,
                                    width = terminalWidth,
                                    systemLine = systemLine,
                                )
                            }
                        }
                    }

                    CTRL_C, CTRL_D -> {
                        if (input.isEmpty()) {
                            result = null
                            break@loop
                        } else if (key == CTRL_C) {
                            writeClipboardText(input.toString())
                        }
                    }

                    CTRL_V -> {
                        val clipboardText = readClipboardText()
                        if (!clipboardText.isNullOrEmpty()) {
                            input.append(sanitizeMultilineInput(clipboardText))
                            redrawFooterFromPromptAnchor(
                                prompt = prompt,
                                input = input,
                                divider = coloredDividerLine,
                                width = terminalWidth,
                                systemLine = systemLine,
                            )
                        }
                    }

                    else -> {
                        if (isPrintableAscii(key)) {
                            input.append(key.toChar())
                            redrawFooterFromPromptAnchor(
                                prompt = prompt,
                                input = input,
                                divider = coloredDividerLine,
                                width = terminalWidth,
                                systemLine = systemLine,
                            )
                        }
                    }
                }
            }

            result
        }
    }

    override fun openConfigMenu(
        tabs: List<String>,
        descriptions: List<String>,
        currentSelection: ConfigMenuSelection,
    ): ConfigMenuSelection {
        if (tabs.isEmpty()) {
            return currentSelection
        }

        val formatTabIndex = tabs.indexOf("Format").takeIf { it >= 0 } ?: 0
        val sizeTabIndex = tabs.indexOf("Size").takeIf { it >= 0 } ?: 1
        val stopTabIndex = tabs.indexOf("Stop").takeIf { it >= 0 } ?: 2
        val formatOptions = listOf(
            OutputFormatOption.PLAIN_TEXT to "Plain text",
            OutputFormatOption.MARKDOWN to "Markdown",
            OutputFormatOption.JSON to "JSON",
            OutputFormatOption.TABLE to "Table",
        )
        val safeDescriptions = tabs.mapIndexed { index, _ -> descriptions.getOrElse(index) { "" } }
        var selectedIndex = 0
        var selectedFormatOption = formatOptions.indexOfFirst { it.first == currentSelection.format }
            .takeIf { it >= 0 } ?: 0
        val maxTokensInput = StringBuilder(currentSelection.maxOutputTokens?.toString().orEmpty())
        val stopInput = StringBuilder(currentSelection.stopSequence)

        fun buildSelection(): ConfigMenuSelection = ConfigMenuSelection(
            format = formatOptions[selectedFormatOption].first,
            maxOutputTokens = maxTokensInput.toString().toIntOrNull()?.takeIf { it > 0 },
            stopSequence = stopInput.toString(),
        )

        fun buildMenuLines(): List<String> {
            val tabsLine = tabs.mapIndexed { index, tab ->
                if (index == selectedIndex) "[ $tab ]" else "  $tab  "
            }.joinToString(separator = "  ")

            val lines = mutableListOf<String>()
            lines += "   Config: $tabsLine"
            lines += ""
            lines += "   ${safeDescriptions[selectedIndex]}"
            if (selectedIndex == formatTabIndex) {
                lines += ""
                formatOptions.forEachIndexed { index, (_, label) ->
                    val optionText = "${index + 1}. $label"
                    val decorated = if (index == selectedFormatOption) {
                        "$OPTION_SELECTED_COLOR$optionText$ANSI_RESET"
                    } else {
                        optionText
                    }
                    lines += "   $decorated"
                }
                lines += ""
                lines += ""
            }
            if (selectedIndex == sizeTabIndex) {
                lines += ""
                lines += "   > ${maxTokensInput}"
                lines += ""
                lines += ""
            }
            if (selectedIndex == stopTabIndex) {
                lines += ""
                lines += "   > ${stopInput}"
                lines += ""
            }
            lines += "   Press ESC to close"
            return lines
        }

        fun renderMenu() {
            // Restore anchor below divider and redraw menu in-place.
            print("\u001B8")
            print('\r')

            val terminalWidth = detectTerminalWidth().coerceAtLeast(1)
            val menuLines = buildMenuLines()
            val menuHeight = calculateWrappedLineCount(menuLines, terminalWidth)
            ensureMenuFits(requiredMenuLines = menuHeight)

            // Save the adjusted anchor so subsequent redraws stay fixed.
            print("\u001B7")
            print("\u001B[J")
            menuLines.forEachIndexed { index, line ->
                print(line)
                if (index != menuLines.lastIndex) {
                    print('\n')
                }
            }
        }

        // Menu anchor line: first line below the input divider.
        print("\r\n")
        print("\u001B7")
        renderMenu()

        var result = buildSelection()

        withRawInput<Unit> {
            while (true) {
                when (val key = readByte()) {
                    null -> {
                        result = buildSelection()
                        break
                    }

                    ENTER_CR, ENTER_LF -> {
                        if (selectedIndex == stopTabIndex) {
                            result = buildSelection()
                            break
                        } else {
                            selectedIndex = (selectedIndex + 1) % tabs.size
                            renderMenu()
                        }
                    }

                    TAB -> {
                        selectedIndex = (selectedIndex + 1) % tabs.size
                        renderMenu()
                    }

                    BACKSPACE, DELETE -> {
                        if (selectedIndex == sizeTabIndex && maxTokensInput.isNotEmpty()) {
                            maxTokensInput.deleteAt(maxTokensInput.lastIndex)
                            renderMenu()
                        }
                        if (selectedIndex == stopTabIndex && stopInput.isNotEmpty()) {
                            stopInput.deleteAt(stopInput.lastIndex)
                            renderMenu()
                        }
                    }

                    ESCAPE -> {
                        val escNext = readOptionalByte(timeoutDeciseconds = 1)
                        if (escNext == null) {
                            result = buildSelection()
                            break
                        }

                        if (escNext == CSI) {
                            when (readOptionalByte(timeoutDeciseconds = 1)) {
                                ARROW_LEFT -> {
                                    selectedIndex = (selectedIndex - 1 + tabs.size) % tabs.size
                                    renderMenu()
                                }

                                ARROW_RIGHT -> {
                                    selectedIndex = (selectedIndex + 1) % tabs.size
                                    renderMenu()
                                }

                                ARROW_UP -> {
                                    if (selectedIndex == formatTabIndex) {
                                        selectedFormatOption = (selectedFormatOption - 1 + formatOptions.size) % formatOptions.size
                                        renderMenu()
                                    }
                                }

                                ARROW_DOWN -> {
                                    if (selectedIndex == formatTabIndex) {
                                        selectedFormatOption = (selectedFormatOption + 1) % formatOptions.size
                                        renderMenu()
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        if (selectedIndex == sizeTabIndex && key == CTRL_V) {
                            val clipboardDigits = sanitizeDigitsOnly(readClipboardText())
                            if (clipboardDigits.isNotEmpty()) {
                                maxTokensInput.append(clipboardDigits)
                                renderMenu()
                            }
                        }
                        if (selectedIndex == sizeTabIndex && key in '0'.code..'9'.code) {
                            maxTokensInput.append(key.toChar())
                            renderMenu()
                        }
                        if (selectedIndex == stopTabIndex && key == CTRL_V) {
                            val clipboardText = sanitizeSingleLineInput(readClipboardText())
                            if (clipboardText.isNotEmpty()) {
                                stopInput.append(clipboardText)
                                renderMenu()
                            }
                        }
                        if (selectedIndex == stopTabIndex && isPrintableAscii(key)) {
                            stopInput.append(key.toChar())
                            renderMenu()
                        }
                    }
                }
            }
        }

        // Cleanup menu area after close.
        print("\u001B8")
        print('\r')
        print("\u001B[J")
        return result
    }

    private fun redrawFooterFromPromptAnchor(
        prompt: String,
        input: StringBuilder,
        divider: String,
        width: Int,
        systemLine: String,
    ) {
        val inputPreview = buildInputPreview(input.toString())
        val continuationPrefix = " ".repeat(prompt.length)

        // Keep cursor hidden during repaint to avoid visible jump at prompt start.
        print("\u001B[?25l")
        print("\u001B8")
        print('\r')
        print("\u001B7")
        print("\u001B[J")
        print(prompt)
        print(inputPreview.visibleLines.firstOrNull().orEmpty())
        inputPreview.visibleLines.drop(1).forEach { line ->
            print('\n')
            print(continuationPrefix)
            print(line)
        }
        print('\n')
        print(divider)
        print('\n')
        print(systemLine)
        print("\u001B8")
        moveCursorToPreviewEnd(
            prompt = prompt,
            preview = inputPreview,
            continuationPrefix = continuationPrefix,
            width = width,
        )
        print("\u001B[?25h")
    }

    private fun moveCursorToPreviewEnd(
        prompt: String,
        preview: InputPreview,
        continuationPrefix: String,
        width: Int,
    ) {
        val safeWidth = width.coerceAtLeast(1)
        val visibleLines = preview.visibleLines.ifEmpty { listOf("") }
        val rowsBeforeLastLine = visibleLines.dropLast(1).mapIndexed { index, line ->
            val prefixLength = if (index == 0) prompt.length else continuationPrefix.length
            calculateVisualLineCount(prefixLength + line.length, safeWidth)
        }.sum()
        val lastLinePrefixLength = if (visibleLines.size == 1) prompt.length else continuationPrefix.length
        val rowOffset = rowsBeforeLastLine + calculateVisualLineCount(
            length = lastLinePrefixLength + visibleLines.last().length,
            width = safeWidth,
        ) - 1
        val column = ((lastLinePrefixLength + visibleLines.last().length) % safeWidth) + 1
        if (rowOffset > 0) {
            print("\u001B[${rowOffset}B")
        }
        print("\u001B[${column}G")
    }

    private fun colorizeDivider(divider: String): String = "$DIVIDER_COLOR$divider$ANSI_RESET"

    private fun formatSystemPromptLine(systemPromptText: String): String {
        val normalizedPrompt = systemPromptText.trim()
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .ifBlank { "emtpty" }
        return "${SYSTEM_PROMPT_LABEL_COLOR}System prompt:${ANSI_RESET} $normalizedPrompt"
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readClipboardText(): String? = memScoped {
        val pipe = popen("pbpaste", "r") ?: return null
        try {
            val buffer = allocArray<ByteVar>(1024)
            val output = StringBuilder()
            while (fgets(buffer, 1024, pipe) != null) {
                output.append(buffer.toKString())
            }
            output.toString().ifEmpty { null }
        } finally {
            pclose(pipe)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun writeClipboardText(text: String) {
        val pipe = popen("pbcopy", "w") ?: return
        try {
            fputs(text, pipe)
            fflush(pipe)
        } finally {
            pclose(pipe)
        }
    }

    private fun sanitizeSingleLineInput(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        return text
            .replace('\n', ' ')
            .replace('\r', ' ')
            .filter { it.code in 32..126 }
    }

    private fun sanitizeMultilineInput(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .filter { it == '\n' || it.code in 32..126 }
    }

    private fun sanitizeDigitsOnly(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        return text.filter { it in '0'..'9' }
    }

    private fun readBracketedPasteIfPresent(firstCsiByte: Int): String? {
        val startMarker = StringBuilder().append(firstCsiByte.toChar())
        while (startMarker.length < 4 && startMarker.last() != '~') {
            val next = readOptionalByte(timeoutDeciseconds = 1) ?: return null
            startMarker.append(next.toChar())
        }
        if (startMarker.toString() != BRACKETED_PASTE_START_MARKER) {
            return null
        }

        val payload = StringBuilder()
        while (payload.length < BRACKETED_PASTE_MAX_LENGTH) {
            val value = readByte() ?: break
            payload.append(value.toChar())
            if (payload.endsWith(BRACKETED_PASTE_END_SEQUENCE)) {
                payload.setLength(payload.length - BRACKETED_PASTE_END_SEQUENCE.length)
                break
            }
        }
        return payload.toString()
    }

    private fun StringBuilder.endsWith(suffix: String): Boolean {
        if (length < suffix.length) return false
        for (index in suffix.indices) {
            if (this[length - suffix.length + index] != suffix[index]) {
                return false
            }
        }
        return true
    }

    @OptIn(ExperimentalForeignApi::class)
    private inline fun <T> withRawInput(block: () -> T): T {
        print("\u001B[?2004h")
        fflush(stdout)
        system("stty -echo -icanon -isig min 1 time 0")
        return try {
            block()
        } finally {
            print("\u001B[?2004l")
            fflush(stdout)
            system("stty sane")
        }
    }

    private fun readOptionalByte(timeoutDeciseconds: Int): Int? {
        system("stty -echo -icanon -isig min 0 time $timeoutDeciseconds")
        return try {
            readByte()
        } finally {
            system("stty -echo -icanon -isig min 1 time 0")
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readByte(): Int? {
        val buffer = ByteArray(1)
        val bytesRead = buffer.usePinned { pinned ->
            read(STDIN_FILENO, pinned.addressOf(0), 1.convert())
        }
        if (bytesRead <= 0) return null
        return buffer[0].toInt() and 0xFF
    }

    private fun isPrintableAscii(value: Int): Boolean = value in 32..126

    private fun calculateVisualLineCount(length: Int, width: Int): Int {
        if (width <= 0) return 1
        return maxOf(1, (length + width - 1) / width)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun detectTerminalWidth(): Int = memScoped {
        val window = alloc<winsize>()
        val result = ioctl(STDOUT_FILENO, TIOCGWINSZ.convert(), window.ptr)
        if (result == 0 && window.ws_col.toInt() > 0) {
            window.ws_col.toInt()
        } else {
            80
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun detectTerminalHeight(): Int = memScoped {
        val window = alloc<winsize>()
        val result = ioctl(STDOUT_FILENO, TIOCGWINSZ.convert(), window.ptr)
        if (result == 0 && window.ws_row.toInt() > 0) {
            window.ws_row.toInt()
        } else {
            24
        }
    }

    private fun ensureMenuFits(requiredMenuLines: Int) {
        if (requiredMenuLines <= 0) return
        val terminalHeight = detectTerminalHeight().coerceAtLeast(1)
        val cursorRow = queryCursorPosition()?.first ?: return
        val overflow = cursorRow + requiredMenuLines - 1 - terminalHeight

        if (overflow > 0) {
            print("\u001B[${overflow}S")
            val targetRow = (cursorRow - overflow).coerceAtLeast(1)
            print("\u001B[${targetRow};1H")
        } else {
            print('\r')
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun queryCursorPosition(): Pair<Int, Int>? {
        system("stty -echo -icanon -isig min 0 time 1")
        return try {
            print("\u001B[6n")
            fflush(stdout)

            val response = StringBuilder()
            var readCount = 0
            while (readCount < 64) {
                val value = readByte() ?: break
                response.append(value.toChar())
                if (value == 'R'.code) {
                    break
                }
                readCount++
            }

            parseCursorResponse(response.toString())
        } finally {
            system("stty -echo -icanon -isig min 1 time 0")
        }
    }

    private fun parseCursorResponse(text: String): Pair<Int, Int>? {
        val sequenceStart = text.indexOf("\u001B[")
        if (sequenceStart == -1) {
            return null
        }

        val sequenceEnd = text.indexOf('R', startIndex = sequenceStart)
        if (sequenceEnd == -1) {
            return null
        }

        val payload = text.substring(sequenceStart + 2, sequenceEnd)
        val parts = payload.split(';')
        if (parts.size != 2) {
            return null
        }

        val row = parts[0].toIntOrNull() ?: return null
        val column = parts[1].toIntOrNull() ?: return null
        return row to column
    }

    private fun calculateWrappedLineCount(lines: List<String>, width: Int): Int = lines.sumOf { line ->
        calculateVisualLineCount(stripAnsi(line).length, width)
    }

    private fun stripAnsi(text: String): String = ANSI_ESCAPE_REGEX.replace(text, "")

    private fun buildInputPreview(text: String): InputPreview {
        val lines = text.split('\n')
        if (lines.size <= MAX_VISIBLE_INPUT_LINES) {
            return InputPreview(visibleLines = lines)
        }

        val visible = lines.take(MAX_VISIBLE_INPUT_LINES).toMutableList()
        val hiddenLines = lines.size - MAX_VISIBLE_INPUT_LINES
        visible += "[+ $hiddenLines more lines]"
        return InputPreview(visibleLines = visible)
    }

    private data class InputPreview(
        val visibleLines: List<String>,
    )

    private const val ENTER_CR = 13
    private const val ENTER_LF = 10
    private const val BACKSPACE = 8
    private const val DELETE = 127
    private const val ESCAPE = 27
    private const val CTRL_C = 3
    private const val CTRL_D = 4
    private const val CTRL_V = 22
    private const val TAB = 9
    private const val CSI = 91
    private const val ARROW_UP = 65
    private const val ARROW_DOWN = 66
    private const val ARROW_LEFT = 68
    private const val ARROW_RIGHT = 67
    private const val DIVIDER_COLOR = "\u001B[38;5;240m"
    private const val SYSTEM_PROMPT_LABEL_COLOR = "\u001B[38;5;40m"
    private const val THINKING_LABEL_COLOR = "\u001B[38;5;45m"
    private const val OPTION_SELECTED_COLOR = "\u001B[38;5;39m"
    private const val ANSI_RESET = "\u001B[0m"
    private const val FOOTER_RESERVED_INPUT_LINES = 3
    private const val BRACKETED_PASTE_START_MARKER = "200~"
    private const val BRACKETED_PASTE_END_SEQUENCE = "\u001B[201~"
    private const val BRACKETED_PASTE_MAX_LENGTH = 8192
    private const val PASTE_ENTER_LOOKAHEAD_DECISECONDS = 5
    private const val MAX_VISIBLE_INPUT_LINES = 10
    private val ANSI_ESCAPE_REGEX = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
}
