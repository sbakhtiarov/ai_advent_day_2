package com.aichallenge.day2.agent.review

data class PullRequestReviewData(
    val prUrl: String,
    val owner: String,
    val repo: String,
    val pullNumber: Long,
    val title: String,
    val description: String?,
    val headBranch: String,
    val baseBranch: String,
    val changedFilesCount: Int,
    val warnings: List<String>,
    val changedFiles: List<PullRequestReviewFile>,
)

data class PullRequestReviewFile(
    val path: String,
    val previousPath: String?,
    val status: String,
    val additions: Int,
    val deletions: Int,
    val changes: Int,
    val diff: String?,
    val diffSource: String,
    val diffAvailable: Boolean,
)

data class ReviewRagChunk(
    val chunkId: String,
    val sectionName: String,
    val headingPath: String,
    val sourcePath: String,
    val score: Double,
    val content: String,
)

data class ReviewTokenUsage(
    val totalTokens: Int,
    val inputTokens: Int,
    val outputTokens: Int,
)

data class PullRequestReviewCoverage(
    val ragChunks: Int,
    val diffsIncluded: Int,
    val diffsOmittedDueToPromptBudget: Int,
    val diffsUnavailable: Int,
)

data class PullRequestReviewResult(
    val prUrl: String,
    val reviewMarkdown: String,
    val model: String,
    val usage: ReviewTokenUsage?,
    val coverage: PullRequestReviewCoverage,
)

data class PullRequestReviewProgress(
    val title: String,
    val message: String,
)

data class PullRequestReviewModelResponse(
    val reviewMarkdown: String,
    val usage: ReviewTokenUsage?,
)

interface PullRequestFetcher {
    suspend fun fetch(prUrl: String): PullRequestReviewData
}

interface ReviewRagRetriever {
    suspend fun retrieve(question: String): List<ReviewRagChunk>
}

interface PullRequestReviewModelClient {
    val modelId: String
    val contextWindowTokens: Int?

    suspend fun review(
        systemPrompt: String,
        contextSystemMessage: String,
        userPrompt: String,
    ): PullRequestReviewModelResponse
}

sealed class PullRequestReviewException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class InvalidPullRequestUrlException(
    message: String,
    cause: Throwable? = null,
) : PullRequestReviewException(message, cause)

class PullRequestFetchException(
    message: String,
    cause: Throwable? = null,
) : PullRequestReviewException(message, cause)

class RagRetrievalException(
    message: String,
    cause: Throwable? = null,
) : PullRequestReviewException(message, cause)

class ReviewGenerationException(
    message: String,
    cause: Throwable? = null,
) : PullRequestReviewException(message, cause)
