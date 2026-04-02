package com.aichallenge.day2.agent.review

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PullRequestReviewServiceTest {
    @Test
    fun reviewReturnsStructuredCoverageAndDeduplicatedRagChunks() {
        runBlocking {
            val service = PullRequestReviewService(
                pullRequestFetcher = FakePullRequestFetcher(
                    pullRequest = samplePullRequest(
                        changedFiles = listOf(
                            sampleFile(
                                path = "src/App.kt",
                                diff = """
                                    @@ -1,2 +1,3 @@
                                    -old
                                    +new
                                """.trimIndent(),
                            ),
                        ),
                    ),
                ),
                ragRetriever = FakeReviewRagRetriever(
                    chunksByQuestion = mapOf(
                        "Add PR review API" to listOf(sampleChunk("chunk-1")),
                        "Server-side review endpoint" to listOf(sampleChunk("chunk-1"), sampleChunk("chunk-2")),
                    ),
                ),
                modelClient = FakeModelClient(),
            )

            val progressTitles = mutableListOf<String>()
            val result = service.review("https://github.com/openai/demo/pull/42") { progress ->
                progressTitles += progress.title
            }

            assertEquals("https://github.com/openai/demo/pull/42", result.prUrl)
            assertEquals("gpt-5.2-codex", result.model)
            assertEquals(2, result.coverage.ragChunks)
            assertEquals(1, result.coverage.diffsIncluded)
            assertEquals(0, result.coverage.diffsOmittedDueToPromptBudget)
            assertEquals(0, result.coverage.diffsUnavailable)
            assertEquals(
                listOf(
                    PullRequestReviewService.FETCH_STEP_TITLE,
                    PullRequestReviewService.FETCH_STEP_TITLE,
                    PullRequestReviewService.RAG_STEP_TITLE,
                    PullRequestReviewService.RAG_STEP_TITLE,
                    PullRequestReviewService.REVIEW_STEP_TITLE,
                    PullRequestReviewService.OUTPUT_STEP_TITLE,
                ),
                progressTitles,
            )
        }
    }

    @Test
    fun invalidUrlFailsBeforeFetch() {
        runBlocking {
            val service = PullRequestReviewService(
                pullRequestFetcher = FakePullRequestFetcher(samplePullRequest()),
                ragRetriever = FakeReviewRagRetriever(emptyMap()),
                modelClient = FakeModelClient(),
            )

            assertFailsWith<InvalidPullRequestUrlException> {
                service.review("https://example.com/not-a-pr")
            }
        }
    }

    @Test
    fun ragFailureIsWrapped() {
        runBlocking {
            val service = PullRequestReviewService(
                pullRequestFetcher = FakePullRequestFetcher(samplePullRequest()),
                ragRetriever = object : ReviewRagRetriever {
                    override suspend fun retrieve(question: String): List<ReviewRagChunk> {
                        throw IllegalStateException("RAG unavailable")
                    }
                },
                modelClient = FakeModelClient(),
            )

            val error = assertFailsWith<RagRetrievalException> {
                service.review("https://github.com/openai/demo/pull/42")
            }

            assertTrue(error.message.orEmpty().contains("RAG unavailable"))
        }
    }

    @Test
    fun llmFailureIsWrapped() {
        runBlocking {
            val service = PullRequestReviewService(
                pullRequestFetcher = FakePullRequestFetcher(samplePullRequest()),
                ragRetriever = FakeReviewRagRetriever(emptyMap()),
                modelClient = object : PullRequestReviewModelClient {
                    override val modelId: String = "gpt-5.2-codex"
                    override val contextWindowTokens: Int? = 400_000

                    override suspend fun review(
                        systemPrompt: String,
                        contextSystemMessage: String,
                        userPrompt: String,
                    ): PullRequestReviewModelResponse {
                        throw IllegalStateException("LLM timeout")
                    }
                },
            )

            val error = assertFailsWith<ReviewGenerationException> {
                service.review("https://github.com/openai/demo/pull/42")
            }

            assertTrue(error.message.orEmpty().contains("LLM timeout"))
        }
    }

    @Test
    fun promptBudgetCanOmitDiffsAndReportCoverage() {
        runBlocking {
            val service = PullRequestReviewService(
                pullRequestFetcher = FakePullRequestFetcher(
                    pullRequest = samplePullRequest(
                        changedFiles = listOf(
                            sampleFile(
                                path = "src/LargeDiff.kt",
                                diff = buildString {
                                    appendLine("@@ -1,1 +1,120 @@")
                                    repeat(500) { index ->
                                        appendLine("+ line $index")
                                    }
                                }.trimEnd(),
                            ),
                        ),
                    ),
                ),
                ragRetriever = FakeReviewRagRetriever(emptyMap()),
                modelClient = FakeModelClient(contextWindowTokens = 128),
            )

            val result = service.review("https://github.com/openai/demo/pull/42")

            assertEquals(0, result.coverage.diffsIncluded)
            assertEquals(1, result.coverage.diffsOmittedDueToPromptBudget)
        }
    }
}

private class FakePullRequestFetcher(
    private val pullRequest: PullRequestReviewData,
) : PullRequestFetcher {
    override suspend fun fetch(prUrl: String): PullRequestReviewData = pullRequest.copy(prUrl = prUrl)
}

private class FakeReviewRagRetriever(
    private val chunksByQuestion: Map<String, List<ReviewRagChunk>>,
) : ReviewRagRetriever {
    override suspend fun retrieve(question: String): List<ReviewRagChunk> = chunksByQuestion[question].orEmpty()
}

private class FakeModelClient(
    override val modelId: String = "gpt-5.2-codex",
    override val contextWindowTokens: Int? = 400_000,
) : PullRequestReviewModelClient {
    override suspend fun review(
        systemPrompt: String,
        contextSystemMessage: String,
        userPrompt: String,
    ): PullRequestReviewModelResponse {
        return PullRequestReviewModelResponse(
            reviewMarkdown = """
                Summary
                OK

                Bugs
                None.

                Architectural Issues
                None.

                Improvements / Recommendations
                None.

                Open Questions / Coverage Notes
                None.
            """.trimIndent(),
            usage = ReviewTokenUsage(
                totalTokens = 300,
                inputTokens = 200,
                outputTokens = 100,
            ),
        )
    }
}

private fun samplePullRequest(
    changedFiles: List<PullRequestReviewFile> = listOf(sampleFile()),
): PullRequestReviewData {
    return PullRequestReviewData(
        prUrl = "https://github.com/openai/demo/pull/42",
        owner = "openai",
        repo = "demo",
        pullNumber = 42,
        title = "Add PR review API",
        description = "Server-side review endpoint",
        headBranch = "feature/review-api",
        baseBranch = "main",
        changedFilesCount = changedFiles.size,
        warnings = emptyList(),
        changedFiles = changedFiles,
    )
}

private fun sampleFile(
    path: String = "src/App.kt",
    diff: String? = "@@ -1 +1 @@\n-old\n+new",
): PullRequestReviewFile {
    return PullRequestReviewFile(
        path = path,
        previousPath = null,
        status = "modified",
        additions = 1,
        deletions = 1,
        changes = 2,
        diff = diff,
        diffSource = if (diff == null) "unavailable" else "raw_diff",
        diffAvailable = diff != null,
    )
}

private fun sampleChunk(chunkId: String): ReviewRagChunk {
    return ReviewRagChunk(
        chunkId = chunkId,
        sectionName = "Architecture",
        headingPath = "Architecture > API",
        sourcePath = "docs/api.md",
        score = 0.91,
        content = "Relevant review guidance.",
    )
}
