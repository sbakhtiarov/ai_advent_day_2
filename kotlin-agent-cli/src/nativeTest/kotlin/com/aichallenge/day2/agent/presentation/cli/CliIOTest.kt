package com.aichallenge.day2.agent.presentation.cli

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliIOTest {
    @Test
    fun calculateFooterRenderLineCountIncludesPromptDividerAndLabel() {
        val lineCount = calculateFooterRenderLineCount(
            prompt = "> ",
            inputText = "hello",
            footerLabel = "API: Local",
            width = 80,
        )

        assertEquals(3, lineCount)
    }

    @Test
    fun calculateFooterRenderLineCountAccountsForMultilinePreviewWrapping() {
        val lineCount = calculateFooterRenderLineCount(
            prompt = "> ",
            inputText = "1234\n567890",
            footerLabel = null,
            width = 5,
        )

        assertEquals(5, lineCount)
    }

    @Test
    fun calculateFooterRenderLineCountIncludesTransientHint() {
        val lineCount = calculateFooterRenderLineCount(
            prompt = "> ",
            inputText = "/mo",
            footerLabel = null,
            width = 80,
            hintText = "matches> /model, /models",
        )

        assertEquals(3, lineCount)
    }

    @Test
    fun resolveCliCommandCompletionReturnsUniqueMatchWithTrailingSpaceForArgumentCommand() {
        val result = resolveCliCommandCompletion("/tem")

        assertEquals("/temperature ", result.replacementText)
        assertContentEquals(listOf("/temperature"), result.matches)
        assertFalse(result.isAmbiguous)
        assertTrue(result.shouldApply)
    }

    @Test
    fun resolveCliCommandCompletionReturnsSharedPrefixAndMatchesWhenAmbiguous() {
        val result = resolveCliCommandCompletion("/mo")

        assertEquals("/model", result.replacementText)
        assertContentEquals(listOf("/model", "/models"), result.matches)
        assertTrue(result.isAmbiguous)
        assertTrue(result.shouldApply)
    }

    @Test
    fun resolveCliCommandCompletionKeepsExactPrefixWhenCommandMatchesMultipleEntries() {
        val result = resolveCliCommandCompletion("/model")

        assertEquals("/model", result.replacementText)
        assertContentEquals(listOf("/model", "/models"), result.matches)
        assertTrue(result.isAmbiguous)
        assertFalse(result.shouldApply)
    }

    @Test
    fun resolveCliCommandCompletionIgnoresUnknownCommandPrefix() {
        val result = resolveCliCommandCompletion("/zzz")

        assertEquals("/zzz", result.replacementText)
        assertTrue(result.matches.isEmpty())
        assertFalse(result.isAmbiguous)
        assertFalse(result.shouldApply)
    }

    @Test
    fun resolveCliCommandCompletionIgnoresNonCommandInput() {
        val result = resolveCliCommandCompletion("hello")

        assertEquals("hello", result.replacementText)
        assertTrue(result.matches.isEmpty())
        assertFalse(result.shouldApply)
    }

    @Test
    fun resolveCliCommandCompletionIgnoresInputAfterWhitespace() {
        val result = resolveCliCommandCompletion("/mcp tool")

        assertEquals("/mcp tool", result.replacementText)
        assertTrue(result.matches.isEmpty())
        assertFalse(result.shouldApply)
    }

    @Test
    fun resolveCliCommandCompletionReturnsReviewPrCommand() {
        val result = resolveCliCommandCompletion("/rev")

        assertEquals("/review_pr ", result.replacementText)
        assertContentEquals(listOf("/review_pr"), result.matches)
        assertFalse(result.isAmbiguous)
        assertTrue(result.shouldApply)
    }

    @Test
    fun buildCliCommandMatchesHintReturnsReadableHint() {
        assertEquals(
            "matches> /model, /models",
            buildCliCommandMatchesHint(listOf("/model", "/models")),
        )
    }

    @Test
    fun buildCliCommandMatchesHintReturnsNullForEmptyMatches() {
        assertNull(buildCliCommandMatchesHint(emptyList()))
    }

    @Test
    fun buildCliCommandHelpTextIncludesReviewPrCommand() {
        val helpText = buildCliCommandHelpText()

        assertTrue(helpText.contains("/review_pr <public-pr-url>"))
        assertTrue(helpText.contains("review a public GitHub pull request with Wire context"))
    }
}
