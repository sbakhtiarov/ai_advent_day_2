package com.aichallenge.day2.agent.review

import kotlin.math.ceil
import kotlin.math.roundToInt

class PullRequestReviewService(
    private val pullRequestFetcher: PullRequestFetcher,
    private val ragRetriever: ReviewRagRetriever,
    private val modelClient: PullRequestReviewModelClient,
) {
    suspend fun review(
        prUrl: String,
        onProgress: (PullRequestReviewProgress) -> Unit = {},
    ): PullRequestReviewResult {
        val normalizedPrUrl = normalizePullRequestUrl(prUrl)

        onProgress(
            PullRequestReviewProgress(
                title = FETCH_STEP_TITLE,
                message = "Loading GitHub pull request metadata and diffs for $normalizedPrUrl.",
            ),
        )
        val pullRequest = try {
            pullRequestFetcher.fetch(normalizedPrUrl)
        } catch (error: InvalidPullRequestUrlException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw InvalidPullRequestUrlException(error.message ?: INVALID_PR_URL_MESSAGE, error)
        } catch (error: Throwable) {
            throw PullRequestFetchException(
                message = error.message ?: "Failed to fetch pull request details.",
                cause = error,
            )
        }
        onProgress(
            PullRequestReviewProgress(
                title = FETCH_STEP_TITLE,
                message = buildFetchSummaryMessage(pullRequest),
            ),
        )

        onProgress(
            PullRequestReviewProgress(
                title = RAG_STEP_TITLE,
                message = buildRagStartMessage(pullRequest),
            ),
        )
        val ragContext = try {
            retrieveRagContext(pullRequest)
        } catch (error: RagRetrievalException) {
            throw error
        } catch (error: Throwable) {
            throw RagRetrievalException(
                message = error.message ?: "Failed to retrieve Wire App context.",
                cause = error,
            )
        }
        onProgress(
            PullRequestReviewProgress(
                title = RAG_STEP_TITLE,
                message = buildRagSummaryMessage(ragContext),
            ),
        )

        val promptContext = buildPromptContext(
            pullRequest = pullRequest,
            retrievedChunks = ragContext.chunks,
        )
        onProgress(
            PullRequestReviewProgress(
                title = REVIEW_STEP_TITLE,
                message = buildReviewPreparationMessage(promptContext),
            ),
        )

        val response = try {
            modelClient.review(
                systemPrompt = REVIEW_PR_SYSTEM_PROMPT,
                contextSystemMessage = promptContext.contextSystemMessage,
                userPrompt = REVIEW_PR_USER_PROMPT,
            )
        } catch (error: Throwable) {
            throw ReviewGenerationException(
                message = error.message ?: "Failed to generate the pull request review.",
                cause = error,
            )
        }

        onProgress(
            PullRequestReviewProgress(
                title = OUTPUT_STEP_TITLE,
                message = "Rendering the final pull request review.",
            ),
        )

        return PullRequestReviewResult(
            prUrl = pullRequest.prUrl,
            reviewMarkdown = response.reviewMarkdown,
            model = modelClient.modelId,
            usage = response.usage,
            coverage = PullRequestReviewCoverage(
                ragChunks = ragContext.chunks.size,
                diffsIncluded = promptContext.includedDiffCount,
                diffsOmittedDueToPromptBudget = promptContext.omittedDiffCount,
                diffsUnavailable = promptContext.unavailableDiffCount,
            ),
        )
    }

    fun normalizePullRequestUrl(prUrl: String): String {
        val normalized = prUrl.trim()
        if (normalized.isEmpty()) {
            throw InvalidPullRequestUrlException("Pull request URL must not be blank.")
        }

        val match = GITHUB_PULL_REQUEST_REGEX.matchEntire(normalized)
            ?: throw InvalidPullRequestUrlException(INVALID_PR_URL_MESSAGE)
        val owner = match.groupValues[1]
        val repo = match.groupValues[2]
        val pullNumber = match.groupValues[3]
        return "https://github.com/$owner/$repo/pull/$pullNumber"
    }

    private suspend fun retrieveRagContext(pullRequest: PullRequestReviewData): ReviewPrRagContext {
        val queries = buildList {
            add(pullRequest.title)
            pullRequest.description
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(::add)
        }
        val uniqueChunks = linkedMapOf<String, ReviewRagChunk>()
        queries.forEach { query ->
            ragRetriever.retrieve(query).forEach { chunk ->
                if (chunk.chunkId !in uniqueChunks) {
                    uniqueChunks[chunk.chunkId] = chunk
                }
            }
        }
        return ReviewPrRagContext(
            chunks = uniqueChunks.values.toList(),
            usedTitleOnlyFallback = queries.size == 1,
            attemptedQueryCount = queries.size,
        )
    }

    private fun buildPromptContext(
        pullRequest: PullRequestReviewData,
        retrievedChunks: List<ReviewRagChunk>,
    ): ReviewPrPromptContext {
        val promptBudgetTokens = resolvePromptBudgetTokens()
        val metadataSection = buildMetadataSection(
            pullRequest = pullRequest,
            retrievedChunks = retrievedChunks,
        )
        val fileSections = mutableListOf<String>()
        val omittedDiffPaths = mutableListOf<String>()
        var includedDiffCount = 0
        var omittedDiffCount = 0
        var unavailableDiffCount = 0
        var usedTokens = estimateTextTokens(metadataSection) + estimateTextTokens(REVIEW_PR_USER_PROMPT)

        pullRequest.changedFiles.forEachIndexed { index, file ->
            val summaryBlock = buildFileSummaryBlock(index, file)
            val diffBlock = file.diff?.trim()?.takeIf { it.isNotEmpty() }
            val includeDiff = diffBlock != null &&
                usedTokens + estimateTextTokens(summaryBlock) + estimateTextTokens(diffBlock) <= promptBudgetTokens

            val fileBlock = buildString {
                append(summaryBlock)
                when {
                    includeDiff && diffBlock != null -> {
                        appendLine("diff_included_in_review_prompt: true")
                        appendLine("diff:")
                        appendLine(diffBlock)
                        includedDiffCount += 1
                        usedTokens += estimateTextTokens(summaryBlock) + estimateTextTokens(diffBlock)
                    }

                    diffBlock != null -> {
                        appendLine("diff_included_in_review_prompt: false")
                        appendLine("diff_omission_reason: omitted_due_to_prompt_budget")
                        omittedDiffPaths += file.path
                        omittedDiffCount += 1
                        usedTokens += estimateTextTokens(summaryBlock)
                    }

                    else -> {
                        appendLine("diff_included_in_review_prompt: false")
                        appendLine("diff_omission_reason: unavailable_from_fetch_result")
                        unavailableDiffCount += 1
                        usedTokens += estimateTextTokens(summaryBlock)
                    }
                }
            }.trimEnd()
            fileSections += fileBlock
        }

        val coverageNotes = buildString {
            appendLine("Coverage notes:")
            appendLine("prompt_budget_tokens: $promptBudgetTokens")
            appendLine("diffs_included: $includedDiffCount")
            appendLine("diffs_omitted_due_to_prompt_budget: $omittedDiffCount")
            appendLine("diffs_unavailable_from_fetch: $unavailableDiffCount")
            if (omittedDiffPaths.isNotEmpty()) {
                appendLine("diff_paths_omitted_due_to_prompt_budget:")
                omittedDiffPaths.forEach { path ->
                    appendLine("- $path")
                }
            }
        }.trimEnd()

        return ReviewPrPromptContext(
            contextSystemMessage = buildString {
                appendLine(metadataSection)
                appendLine()
                appendLine("Changed files:")
                fileSections.forEachIndexed { index, fileSection ->
                    appendLine()
                    appendLine("File ${index + 1}:")
                    appendLine(fileSection)
                }
                appendLine()
                append(coverageNotes)
            }.trimEnd(),
            includedDiffCount = includedDiffCount,
            omittedDiffCount = omittedDiffCount,
            unavailableDiffCount = unavailableDiffCount,
        )
    }

    private fun buildMetadataSection(
        pullRequest: PullRequestReviewData,
        retrievedChunks: List<ReviewRagChunk>,
    ): String {
        val ragContext = if (retrievedChunks.isEmpty()) {
            "Wire App RAG context:\n- No relevant project context was found."
        } else {
            buildString {
                appendLine("Wire App RAG context:")
                retrievedChunks.forEachIndexed { index, chunk ->
                    appendLine()
                    appendLine("Chunk ${index + 1}:")
                    appendLine("chunk_id: ${chunk.chunkId}")
                    appendLine("section_name: ${chunk.sectionName}")
                    appendLine("heading_path: ${chunk.headingPath}")
                    appendLine("source_path: ${chunk.sourcePath}")
                    appendLine("score: ${formatRate(chunk.score)}")
                    appendLine("content:")
                    appendLine(chunk.content.trim())
                }
            }.trimEnd()
        }

        return buildString {
            appendLine("Pull request details:")
            appendLine("pr_url: ${pullRequest.prUrl}")
            appendLine("repository: ${pullRequest.owner}/${pullRequest.repo}")
            appendLine("pull_number: ${pullRequest.pullNumber}")
            appendLine("title: ${pullRequest.title}")
            appendLine("description: ${pullRequest.description ?: "(blank)"}")
            appendLine("base_branch: ${pullRequest.baseBranch}")
            appendLine("head_branch: ${pullRequest.headBranch}")
            appendLine("changed_files_count: ${pullRequest.changedFilesCount}")
            appendLine("fetch_warnings:")
            if (pullRequest.warnings.isEmpty()) {
                appendLine("- none")
            } else {
                pullRequest.warnings.forEach { warning ->
                    appendLine("- $warning")
                }
            }
            appendLine()
            append(ragContext)
        }.trimEnd()
    }

    private fun buildFileSummaryBlock(
        index: Int,
        file: PullRequestReviewFile,
    ): String {
        return buildString {
            appendLine("file_index: ${index + 1}")
            appendLine("path: ${file.path}")
            appendLine("previous_path: ${file.previousPath ?: "(none)"}")
            appendLine("status: ${file.status}")
            appendLine("additions: ${file.additions}")
            appendLine("deletions: ${file.deletions}")
            appendLine("changes: ${file.changes}")
            appendLine("diff_source: ${file.diffSource}")
            appendLine("diff_available: ${file.diffAvailable}")
        }
    }

    private fun resolvePromptBudgetTokens(): Int {
        val scaledBudget = modelClient.contextWindowTokens?.let { contextWindow ->
            (contextWindow * REVIEW_PR_PROMPT_BUDGET_RATIO).roundToInt()
        }
        return if (scaledBudget != null) {
            scaledBudget.coerceAtLeast(REVIEW_PR_MIN_PROMPT_BUDGET_TOKENS)
        } else {
            REVIEW_PR_DEFAULT_PROMPT_BUDGET_TOKENS
        }
    }

    private fun buildFetchSummaryMessage(pullRequest: PullRequestReviewData): String {
        val warnings = if (pullRequest.warnings.isEmpty()) {
            "Warnings: none."
        } else {
            "Warnings: ${pullRequest.warnings.joinToString(separator = " | ")}"
        }
        return "Loaded ${pullRequest.owner}/${pullRequest.repo}#${pullRequest.pullNumber} \"${pullRequest.title}\" with ${pullRequest.changedFilesCount} changed file(s). $warnings"
    }

    private fun buildRagStartMessage(pullRequest: PullRequestReviewData): String {
        return if (pullRequest.description.isNullOrBlank()) {
            "Querying Wire App RAG with the PR title. The PR description is blank, so title-only fallback will be used."
        } else {
            "Querying Wire App RAG separately with the PR title and description."
        }
    }

    private fun buildRagSummaryMessage(ragContext: ReviewPrRagContext): String {
        return when {
            ragContext.chunks.isEmpty() && ragContext.usedTitleOnlyFallback ->
                "Retrieved 0 unique Wire App RAG chunk(s). No project context was found, and title-only fallback was used."

            ragContext.chunks.isEmpty() ->
                "Retrieved 0 unique Wire App RAG chunk(s). No project context was found."

            ragContext.usedTitleOnlyFallback ->
                "Retrieved ${ragContext.chunks.size} unique Wire App RAG chunk(s) using the PR title only."

            else ->
                "Retrieved ${ragContext.chunks.size} unique Wire App RAG chunk(s) from ${ragContext.attemptedQueryCount} query/queries."
        }
    }

    private fun buildReviewPreparationMessage(promptContext: ReviewPrPromptContext): String {
        return buildString {
            append("Prepared the review prompt with ")
            append(promptContext.includedDiffCount)
            append(" full diff(s)")
            if (promptContext.omittedDiffCount > 0) {
                append(", ")
                append(promptContext.omittedDiffCount)
                append(" diff(s) omitted due to prompt budget")
            }
            if (promptContext.unavailableDiffCount > 0) {
                append(", ")
                append(promptContext.unavailableDiffCount)
                append(" diff(s) unavailable from the fetch result")
            }
            append(".")
        }
    }

    private fun estimateTextTokens(text: String): Int {
        if (text.isEmpty()) {
            return 0
        }
        return ceil(text.length / CHARS_PER_TOKEN).toInt()
    }

    private fun formatRate(value: Double): String {
        return ((value * 100.0).roundToInt() / 100.0).toString()
    }

    private data class ReviewPrRagContext(
        val chunks: List<ReviewRagChunk>,
        val usedTitleOnlyFallback: Boolean,
        val attemptedQueryCount: Int,
    )

    private data class ReviewPrPromptContext(
        val contextSystemMessage: String,
        val includedDiffCount: Int,
        val omittedDiffCount: Int,
        val unavailableDiffCount: Int,
    )

    companion object {
        const val FETCH_STEP_TITLE = "Step 1/4 - Fetch PR details"
        const val RAG_STEP_TITLE = "Step 2/4 - Retrieve Wire context"
        const val REVIEW_STEP_TITLE = "Step 3/4 - Review source code changes"
        const val OUTPUT_STEP_TITLE = "Step 4/4 - Provide full PR review"

        private const val REVIEW_PR_DEFAULT_PROMPT_BUDGET_TOKENS = 24_000
        private const val REVIEW_PR_MIN_PROMPT_BUDGET_TOKENS = 128
        private const val REVIEW_PR_PROMPT_BUDGET_RATIO = 0.55
        private const val CHARS_PER_TOKEN = 4.0
        private const val INVALID_PR_URL_MESSAGE =
            "Pull request URL must match https://github.com/{owner}/{repo}/pull/{number}."
        private val GITHUB_PULL_REQUEST_REGEX = Regex(
            pattern = "^https://github\\.com/([^/\\s]+)/([^/\\s]+)/pull/([0-9]+)(?:/)?(?:[#?].*)?$",
            option = RegexOption.IGNORE_CASE,
        )

        val REVIEW_PR_SYSTEM_PROMPT = """
            You are reviewing a GitHub pull request using only the PR data and Wire App RAG context provided in this turn.
            Rules:
            - Review only the provided evidence. Do not rely on prior conversation, memory, or outside knowledge.
            - Focus on bugs, architectural issues, and practical improvements or recommendations.
            - Call out uncertainty when evidence is incomplete or some diffs were omitted or unavailable.
            - Reference concrete file paths from the provided PR data whenever possible.
            - Keep the review direct and high signal.
            - Use exactly these Markdown sections in this order:
              Summary
              Bugs
              Architectural Issues
              Improvements / Recommendations
              Open Questions / Coverage Notes
            - Never omit a section. If a section has no findings, say so briefly.
            - In Open Questions / Coverage Notes, explicitly disclose any omitted or unavailable diff coverage mentioned in the provided context.
        """.trimIndent()
        const val REVIEW_PR_USER_PROMPT = "Review this pull request and provide the full PR review."
    }
}
