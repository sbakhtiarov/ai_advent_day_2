package com.aichallenge.day2.agent.server

import com.aichallenge.day2.agent.review.InvalidPullRequestUrlException
import com.aichallenge.day2.agent.review.PullRequestFetchException
import com.aichallenge.day2.agent.review.PullRequestReviewResult
import com.aichallenge.day2.agent.review.RagRetrievalException
import com.aichallenge.day2.agent.review.ReviewGenerationException
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.TimeSource

fun Application.reviewApi(
    reviewHandler: suspend (String) -> PullRequestReviewResult,
) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(StatusPages) {
        exception<InvalidPullRequestUrlException> { call, cause ->
            call.respond(
                status = HttpStatusCode.BadRequest,
                message = ApiErrorResponse(
                    status = "error",
                    error = "invalid_request",
                    message = cause.message ?: "Invalid pull request URL.",
                ),
            )
        }
        exception<PullRequestFetchException> { call, cause ->
            call.respond(
                status = HttpStatusCode.BadGateway,
                message = ApiErrorResponse(
                    status = "error",
                    error = "github_fetch_failed",
                    message = cause.message ?: "Failed to fetch pull request details.",
                ),
            )
        }
        exception<RagRetrievalException> { call, cause ->
            call.respond(
                status = HttpStatusCode.BadGateway,
                message = ApiErrorResponse(
                    status = "error",
                    error = "rag_failed",
                    message = cause.message ?: "Failed to retrieve Wire App context.",
                ),
            )
        }
        exception<ReviewGenerationException> { call, cause ->
            call.respond(
                status = HttpStatusCode.BadGateway,
                message = ApiErrorResponse(
                    status = "error",
                    error = "llm_failed",
                    message = cause.message ?: "Failed to generate the review.",
                ),
            )
        }
        exception<Throwable> { call, cause ->
            call.respond(
                status = HttpStatusCode.InternalServerError,
                message = ApiErrorResponse(
                    status = "error",
                    error = "internal_error",
                    message = cause.message ?: "Unexpected internal error.",
                ),
            )
        }
    }

    routing {
        post("/review-pr") {
            val request = runCatching { call.receive<ReviewPrRequest>() }
                .getOrElse {
                    throw InvalidPullRequestUrlException("Request body must be valid JSON with a non-blank pr_url.")
                }
            val prUrl = request.prUrl
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw InvalidPullRequestUrlException("Request body must include a non-blank pr_url.")

            val startedAt = TimeSource.Monotonic.markNow()
            val result = reviewHandler(prUrl)
            val elapsedMs = startedAt.elapsedNow().inWholeMilliseconds

            call.respond(
                status = HttpStatusCode.OK,
                message = ReviewPrSuccessResponse(
                    status = "ok",
                    prUrl = result.prUrl,
                    reviewMarkdown = result.reviewMarkdown,
                    model = result.model,
                    elapsedMs = elapsedMs,
                    usage = result.usage?.let { usage ->
                        UsageResponse(
                            inputTokens = usage.inputTokens,
                            outputTokens = usage.outputTokens,
                            totalTokens = usage.totalTokens,
                        )
                    },
                    coverage = CoverageResponse(
                        ragChunks = result.coverage.ragChunks,
                        diffsIncluded = result.coverage.diffsIncluded,
                        diffsOmittedDueToPromptBudget = result.coverage.diffsOmittedDueToPromptBudget,
                        diffsUnavailable = result.coverage.diffsUnavailable,
                    ),
                ),
            )
        }
    }
}

@Serializable
data class ReviewPrRequest(
    @SerialName("pr_url")
    val prUrl: String? = null,
)

@Serializable
data class ReviewPrSuccessResponse(
    val status: String,
    @SerialName("pr_url")
    val prUrl: String,
    @SerialName("review_markdown")
    val reviewMarkdown: String,
    val model: String,
    @SerialName("elapsed_ms")
    val elapsedMs: Long,
    val usage: UsageResponse?,
    val coverage: CoverageResponse,
)

@Serializable
data class UsageResponse(
    @SerialName("input_tokens")
    val inputTokens: Int,
    @SerialName("output_tokens")
    val outputTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int,
)

@Serializable
data class CoverageResponse(
    @SerialName("rag_chunks")
    val ragChunks: Int,
    @SerialName("diffs_included")
    val diffsIncluded: Int,
    @SerialName("diffs_omitted_due_to_prompt_budget")
    val diffsOmittedDueToPromptBudget: Int,
    @SerialName("diffs_unavailable")
    val diffsUnavailable: Int,
)

@Serializable
data class ApiErrorResponse(
    val status: String,
    val error: String,
    val message: String,
)
