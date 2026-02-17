package com.aichallenge.day2.agent.presentation.cli

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.TIOCGWINSZ
import platform.posix.ioctl
import platform.posix.read
import platform.posix.system
import platform.posix.winsize

interface CliIO {
    fun clearScreen()
    fun hideCursor()
    fun showCursor()
    fun writeLine(text: String = "")
    fun readLine(prompt: String): String?
    fun readLineInFooter(prompt: String, divider: String): String?
    fun openConfigMenu(tabs: List<String>, descriptions: List<String>)
}

object StdCliIO : CliIO {
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

    override fun readLineInFooter(prompt: String, divider: String): String? {
        val input = StringBuilder()
        val terminalWidth = detectTerminalWidth().coerceAtLeast(prompt.length + 1)
        val dividerChar = divider.firstOrNull() ?: '─'
        val dividerLine = dividerChar.toString().repeat(terminalWidth)
        val coloredDividerLine = colorizeDivider(dividerLine)
        val width = terminalWidth
        var renderedInputLines = 1

        // Initial footer render:
        // divider
        // > input (cursor stays here)
        // divider
        print(coloredDividerLine)
        print('\n')
        print(prompt)
        print("\u001B7")
        print('\n')
        print(coloredDividerLine)
        print("\u001B8")

        return withRawInput<String?> {
            var result: String? = null

            loop@ while (true) {
                when (val key = readByte()) {
                    null -> {
                        result = null
                        break@loop
                    }

                    ENTER_CR, ENTER_LF -> {
                        moveCursorToPromptStart(renderedInputLines)
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
                            renderedInputLines = redrawFooter(
                                prompt = prompt,
                                input = input,
                                divider = coloredDividerLine,
                                width = width,
                                previouslyRenderedInputLines = renderedInputLines,
                            )
                        }
                    }

                    ESCAPE -> {
                        // Ignore the common CSI sequence bytes for arrows/home/end.
                        readByte()
                        readByte()
                    }

                    CTRL_C, CTRL_D -> {
                        if (input.isEmpty()) {
                            result = null
                            break@loop
                        }
                    }

                    else -> {
                        if (isPrintableAscii(key)) {
                            input.append(key.toChar())
                            renderedInputLines = redrawFooter(
                                prompt = prompt,
                                input = input,
                                divider = coloredDividerLine,
                                width = width,
                                previouslyRenderedInputLines = renderedInputLines,
                            )
                        }
                    }
                }
            }

            result
        }
    }

    override fun openConfigMenu(tabs: List<String>, descriptions: List<String>) {
        if (tabs.isEmpty()) {
            return
        }

        val formatTabIndex = tabs.indexOf("Format").takeIf { it >= 0 } ?: 0
        val sizeTabIndex = tabs.indexOf("Size").takeIf { it >= 0 } ?: 1
        val stopTabIndex = tabs.indexOf("Stop").takeIf { it >= 0 } ?: 2
        val formatOptions = listOf(
            "Plain text",
            "Markdown",
            "JSON",
            "Table",
        )
        val sizeOptions = listOf(
            "Unlimited",
            "One line",
            "Three lines",
            "Five lines",
        )
        val safeDescriptions = tabs.mapIndexed { index, _ -> descriptions.getOrElse(index) { "" } }
        var selectedIndex = 0
        var selectedFormatOption = 0
        var selectedSizeOption = 0
        val stopInput = StringBuilder()

        fun renderMenu() {
            // Restore anchor below divider and redraw menu in-place.
            print("\u001B8")
            print("\u001B[J")

            val tabsLine = tabs.mapIndexed { index, tab ->
                if (index == selectedIndex) "[ $tab ]" else "  $tab  "
            }.joinToString(separator = "  ")

            print("   Config: ")
            print(tabsLine)
            print('\n')
            print('\n')
            print("   ")
            print(safeDescriptions[selectedIndex])
            print('\n')
            if (selectedIndex == formatTabIndex) {
                print('\n')
                formatOptions.forEachIndexed { index, option ->
                    val optionText = "${index + 1}. $option"
                    val decorated = if (index == selectedFormatOption) {
                        "$OPTION_SELECTED_COLOR$optionText$ANSI_RESET"
                    } else {
                        optionText
                    }
                    print("   ")
                    print(decorated)
                    print('\n')
                }
                print('\n')
                print('\n')
            }
            if (selectedIndex == sizeTabIndex) {
                print('\n')
                sizeOptions.forEachIndexed { index, option ->
                    val optionText = "${index + 1}. $option"
                    val decorated = if (index == selectedSizeOption) {
                        "$OPTION_SELECTED_COLOR$optionText$ANSI_RESET"
                    } else {
                        optionText
                    }
                    print("   ")
                    print(decorated)
                    print('\n')
                }
                print('\n')
                print('\n')
            }
            if (selectedIndex == stopTabIndex) {
                print('\n')
                print("   > ")
                print(stopInput.toString())
                print('\n')
                print('\n')
            }
            print("   ")
            print("Press ESC to close")
        }

        // Menu anchor line: first line below the input divider.
        print('\n')
        print("\u001B7")
        renderMenu()

        withRawInput<Unit> {
            while (true) {
                when (val key = readByte()) {
                    null -> break

                    ENTER_CR, ENTER_LF -> {
                        if (selectedIndex == stopTabIndex) {
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
                        if (selectedIndex == stopTabIndex && stopInput.isNotEmpty()) {
                            stopInput.deleteAt(stopInput.lastIndex)
                            renderMenu()
                        }
                    }

                    ESCAPE -> {
                        val escNext = readOptionalByte(timeoutDeciseconds = 1)
                        if (escNext == null) {
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
                                    if (selectedIndex == sizeTabIndex) {
                                        selectedSizeOption = (selectedSizeOption - 1 + sizeOptions.size) % sizeOptions.size
                                        renderMenu()
                                    }
                                }

                                ARROW_DOWN -> {
                                    if (selectedIndex == formatTabIndex) {
                                        selectedFormatOption = (selectedFormatOption + 1) % formatOptions.size
                                        renderMenu()
                                    }
                                    if (selectedIndex == sizeTabIndex) {
                                        selectedSizeOption = (selectedSizeOption + 1) % sizeOptions.size
                                        renderMenu()
                                    }
                                }
                            }
                        }
                    }

                    else -> {
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
        print("\u001B[J")
    }

    private fun redrawFooter(
        prompt: String,
        input: StringBuilder,
        divider: String,
        width: Int,
        previouslyRenderedInputLines: Int,
    ): Int {
        moveCursorToPromptStart(previouslyRenderedInputLines)
        print("\u001B[J")
        print(prompt)
        print(input.toString())
        print("\u001B7")
        print('\n')
        print(divider)
        print("\u001B8")

        val promptAndInputLength = prompt.length + input.length
        return calculateVisualLineCount(promptAndInputLength, width)
    }

    private fun moveCursorToPromptStart(renderedInputLines: Int) {
        print('\r')
        if (renderedInputLines > 1) {
            print("\u001B[${renderedInputLines - 1}A")
        }
    }

    private fun colorizeDivider(divider: String): String = "$DIVIDER_COLOR$divider$ANSI_RESET"

    private inline fun <T> withRawInput(block: () -> T): T {
        system("stty -echo -icanon -isig min 1 time 0")
        return try {
            block()
        } finally {
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

    private const val ENTER_CR = 13
    private const val ENTER_LF = 10
    private const val BACKSPACE = 8
    private const val DELETE = 127
    private const val ESCAPE = 27
    private const val CTRL_C = 3
    private const val CTRL_D = 4
    private const val TAB = 9
    private const val CSI = 91
    private const val ARROW_UP = 65
    private const val ARROW_DOWN = 66
    private const val ARROW_LEFT = 68
    private const val ARROW_RIGHT = 67
    private const val DIVIDER_COLOR = "\u001B[38;5;240m"
    private const val OPTION_SELECTED_COLOR = "\u001B[38;5;39m"
    private const val ANSI_RESET = "\u001B[0m"
}
