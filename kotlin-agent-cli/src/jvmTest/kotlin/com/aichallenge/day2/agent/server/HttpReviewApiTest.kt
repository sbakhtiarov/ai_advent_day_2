package com.aichallenge.day2.agent.server

import com.aichallenge.day2.agent.review.InvalidPullRequestUrlException
import com.aichallenge.day2.agent.review.PullRequestFetchException
import com.aichallenge.day2.agent.review.PullRequestReviewCoverage
import com.aichallenge.day2.agent.review.PullRequestReviewResult
import com.aichallenge.day2.agent.review.ReviewTokenUsage
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class HttpReviewApiTest {
    @Test
    fun reviewPrReturnsStructuredJson() = testApplication {
        application {
            reviewApi { prUrl ->
                PullRequestReviewResult(
                    prUrl = prUrl,
                    reviewMarkdown = "Summary\nLooks good.",
                    model = "gpt-5.2-codex",
                    usage = ReviewTokenUsage(
                        totalTokens = 300,
                        inputTokens = 200,
                        outputTokens = 100,
                    ),
                    coverage = PullRequestReviewCoverage(
                        ragChunks = 2,
                        diffsIncluded = 3,
                        diffsOmittedDueToPromptBudget = 1,
                        diffsUnavailable = 0,
                    ),
                )
            }
        }

        val response = client.post("/review-pr") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"pr_url":"https://github.com/openai/demo/pull/42"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertContains(body, "\"status\":\"ok\"")
        assertContains(body, "\"pr_url\":\"https://github.com/openai/demo/pull/42\"")
        assertContains(body, "\"review_markdown\":\"Summary\\nLooks good.\"")
        assertContains(body, "\"rag_chunks\":2")
    }

    @Test
    fun blankPrUrlReturnsBadRequest() = testApplication {
        application {
            reviewApi { error("not reached") }
        }

        val response = client.post("/review-pr") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"pr_url":"   "}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "\"error\":\"invalid_request\"")
    }

    @Test
    fun githubFailureMapsToBadGateway() = testApplication {
        application {
            reviewApi {
                throw PullRequestFetchException("GitHub unavailable")
            }
        }

        val response = client.post("/review-pr") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"pr_url":"https://github.com/openai/demo/pull/42"}""")
        }

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertContains(response.bodyAsText(), "\"error\":\"github_fetch_failed\"")
    }

    @Test
    fun invalidUrlExceptionMapsToBadRequest() = testApplication {
        application {
            reviewApi {
                throw InvalidPullRequestUrlException("bad pr url")
            }
        }

        val response = client.post("/review-pr") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"pr_url":"https://github.com/openai/demo/pull/abc"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "\"message\":\"bad pr url\"")
    }
}
