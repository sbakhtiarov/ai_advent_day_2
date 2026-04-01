package com.aichallenge.day2.agent.data.tools

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FetchGithubPullRequestBuiltInToolTest {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Test
    fun parsePullRequestUrlAcceptsCanonicalGithubPullRequestUrl() {
        val executor = createExecutor { request ->
            unexpectedRequest(request)
        }

        val parsed = executor.parsePullRequestUrl("https://github.com/wireapp/wire-android/pull/4672?foo=bar#files")

        assertEquals("wireapp", parsed.owner)
        assertEquals("wire-android", parsed.repo)
        assertEquals(4672L, parsed.pullNumber)
        assertEquals("https://github.com/wireapp/wire-android/pull/4672", parsed.normalizedUrl)
    }

    @Test
    fun executeRejectsInvalidPullRequestUrls() = runBlocking {
        val executor = createExecutor { request ->
            unexpectedRequest(request)
        }
        val invalidUrls = listOf(
            "",
            "https://github.com/wireapp/wire-android/issues/4672",
            "http://github.com/wireapp/wire-android/pull/4672",
            "https://gitlab.com/wireapp/wire-android/pull/4672",
            "https://github.com/wireapp/wire-android/pull/not-a-number",
        )

        invalidUrls.forEach { invalidUrl ->
            val error = assertFailsWith<IllegalArgumentException> {
                executor.execute(
                    buildJsonObject {
                        put("pr_url", invalidUrl)
                    },
                )
            }
            assertContains(error.message.orEmpty(), "pr_url")
        }
    }

    @Test
    fun executeFetchesPullRequestMetadataFilesAndPerFileDiffs() = runBlocking {
        val recordedRequests = mutableListOf<RecordedGitHubRequest>()
        val executor = createExecutor(recordedRequests) { request ->
            when {
                request.url.encodedPath == "/repos/wireapp/wire-android/pulls/4672" &&
                    request.headers[HttpHeaders.Accept] == "application/vnd.github+json" -> {
                    respondJson(
                        """
                            {
                              "title": "Improve PR fetcher",
                              "body": "Adds a GitHub PR tool.",
                              "head": { "ref": "feature/pr-fetcher" },
                              "base": { "ref": "develop" }
                            }
                        """.trimIndent(),
                    )
                }

                request.url.encodedPath == "/repos/wireapp/wire-android/pulls/4672/files" &&
                    request.url.parameters["page"] == "1" -> {
                    respondJson(
                        """
                            [
                              {
                                "filename": "docs/guide.md",
                                "status": "modified",
                                "additions": 4,
                                "deletions": 1,
                                "changes": 5,
                                "patch": "@@ -1 +1 @@\n-old\n+new"
                              },
                              {
                                "filename": "src/NewName.kt",
                                "previous_filename": "src/OldName.kt",
                                "status": "renamed",
                                "additions": 10,
                                "deletions": 8,
                                "changes": 18,
                                "patch": null
                              }
                            ]
                        """.trimIndent(),
                    )
                }

                request.url.encodedPath == "/repos/wireapp/wire-android/pulls/4672/files" &&
                    request.url.parameters["page"] == "2" -> {
                    respondJson(
                        """
                            [
                              {
                                "filename": "images/logo.png",
                                "status": "modified",
                                "additions": 0,
                                "deletions": 0,
                                "changes": 0,
                                "patch": null
                              }
                            ]
                        """.trimIndent(),
                    )
                }

                request.url.encodedPath == "/repos/wireapp/wire-android/pulls/4672/files" &&
                    request.url.parameters["page"] == "3" -> respondJson("[]")

                request.url.encodedPath == "/repos/wireapp/wire-android/pulls/4672" &&
                    request.headers[HttpHeaders.Accept] == "application/vnd.github.v3.diff" -> {
                    respondText(
                        """
                            diff --git a/src/OldName.kt b/src/NewName.kt
                            similarity index 98%
                            rename from src/OldName.kt
                            rename to src/NewName.kt
                            --- a/src/OldName.kt
                            +++ b/src/NewName.kt
                            @@ -1,2 +1,2 @@
                            -oldName()
                            +newName()
                        """.trimIndent(),
                    )
                }

                else -> unexpectedRequest(request)
            }
        }

        val result = executor.execute(
            buildJsonObject {
                put("pr_url", "https://github.com/wireapp/wire-android/pull/4672")
            },
        )

        assertEquals(false, result.isError)
        assertContains(result.content.single().jsonObject["text"]?.jsonPrimitive?.content.orEmpty(), "3 changed file(s)")

        val structured = result.structuredContent ?: error("Missing structured content")
        assertEquals("Improve PR fetcher", structured["title"]?.jsonPrimitive?.content)
        assertEquals("Adds a GitHub PR tool.", structured["description"]?.jsonPrimitive?.content)
        assertEquals("feature/pr-fetcher", structured["head_branch"]?.jsonPrimitive?.content)
        assertEquals("develop", structured["base_branch"]?.jsonPrimitive?.content)
        assertEquals("3", structured["changed_files_count"]?.jsonPrimitive?.content)
        assertTrue(structured["warnings"]?.jsonArray?.isEmpty() == true)

        val changedFiles = structured["changed_files"]?.jsonArray ?: error("Missing changed_files")
        assertEquals(3, changedFiles.size)

        val modifiedFile = changedFiles[0].jsonObject
        assertEquals("docs/guide.md", modifiedFile["path"]?.jsonPrimitive?.content)
        assertEquals("files_api_patch", modifiedFile["diff_source"]?.jsonPrimitive?.content)
        assertTrue(modifiedFile["diff_available"]?.jsonPrimitive?.content?.toBooleanStrict() == true)
        assertContains(modifiedFile["diff"]?.jsonPrimitive?.content.orEmpty(), "@@ -1 +1 @@")

        val renamedFile = changedFiles[1].jsonObject
        assertEquals("src/NewName.kt", renamedFile["path"]?.jsonPrimitive?.content)
        assertEquals("src/OldName.kt", renamedFile["previous_path"]?.jsonPrimitive?.content)
        assertEquals("raw_diff", renamedFile["diff_source"]?.jsonPrimitive?.content)
        assertContains(renamedFile["diff"]?.jsonPrimitive?.content.orEmpty(), "rename from src/OldName.kt")

        val binaryFile = changedFiles[2].jsonObject
        assertEquals("images/logo.png", binaryFile["path"]?.jsonPrimitive?.content)
        assertEquals("unavailable", binaryFile["diff_source"]?.jsonPrimitive?.content)
        assertFalse(binaryFile["diff_available"]?.jsonPrimitive?.content?.toBooleanStrict() ?: true)
        assertEquals(JsonNull, binaryFile["diff"])

        assertEquals(5, recordedRequests.size)
        recordedRequests.forEach { request ->
            assertEquals("kotlin-agent-cli", request.userAgent)
            assertEquals("2022-11-28", request.apiVersion)
        }
        assertEquals(
            listOf(
                "application/vnd.github+json",
                "application/vnd.github+json",
                "application/vnd.github+json",
                "application/vnd.github+json",
                "application/vnd.github.v3.diff",
            ),
            recordedRequests.map(RecordedGitHubRequest::accept),
        )
    }

    @Test
    fun executeAddsWarningWhenGitHubFileListMayBeTruncatedAtApiLimit() = runBlocking {
        val executor = createExecutor { request ->
            when {
                request.url.encodedPath == "/repos/wireapp/wire-android/pulls/4672" &&
                    request.headers[HttpHeaders.Accept] == "application/vnd.github+json" -> {
                    respondJson(
                        """
                            {
                              "title": "Huge PR",
                              "body": null,
                              "head": { "ref": "feature/huge" },
                              "base": { "ref": "main" }
                            }
                        """.trimIndent(),
                    )
                }

                request.url.encodedPath == "/repos/wireapp/wire-android/pulls/4672/files" -> {
                    val page = request.url.parameters["page"]?.toInt() ?: 0
                    if (page in 1..30) {
                        respondJson(
                            buildLargeFilesPage(page),
                        )
                    } else {
                        unexpectedRequest(request)
                    }
                }

                request.url.encodedPath == "/repos/wireapp/wire-android/pulls/4672" &&
                    request.headers[HttpHeaders.Accept] == "application/vnd.github.v3.diff" -> respondText("")

                else -> unexpectedRequest(request)
            }
        }

        val result = executor.execute(
            buildJsonObject {
                put("pr_url", "https://github.com/wireapp/wire-android/pull/4672")
            },
        )

        val warnings = result.structuredContent?.get("warnings")?.jsonArray ?: error("Missing warnings")
        assertEquals(1, warnings.size)
        assertContains(warnings.single().jsonPrimitive.content, "3000 files")
        assertEquals("3000", result.structuredContent?.get("changed_files_count")?.jsonPrimitive?.content)
    }

    @Test
    fun executeMapsNotFoundToClearError() = runBlocking {
        val executor = createExecutor { request ->
            respondJson(
                """{"message":"Not Found"}""",
                status = HttpStatusCode.NotFound,
            )
        }

        val error = assertFailsWith<IllegalStateException> {
            executor.execute(
                buildJsonObject {
                    put("pr_url", "https://github.com/wireapp/wire-android/pull/4672")
                },
            )
        }

        assertContains(error.message.orEmpty(), "not found")
    }

    @Test
    fun executeMapsRateLimitToClearError() = runBlocking {
        val executor = createExecutor { request ->
            respondJson(
                """{"message":"API rate limit exceeded"}""",
                status = HttpStatusCode.Forbidden,
            )
        }

        val error = assertFailsWith<IllegalStateException> {
            executor.execute(
                buildJsonObject {
                    put("pr_url", "https://github.com/wireapp/wire-android/pull/4672")
                },
            )
        }

        assertContains(error.message.orEmpty(), "rate limit")
    }

    @Test
    fun executeFailsWhenGitHubReturnsMalformedPullRequestPayload() = runBlocking {
        val executor = createExecutor { request ->
            when {
                request.url.encodedPath == "/repos/wireapp/wire-android/pulls/4672" &&
                    request.headers[HttpHeaders.Accept] == "application/vnd.github+json" -> {
                    respondJson(
                        """
                            {
                              "title": "",
                              "body": "Missing title",
                              "head": { "ref": "feature/x" },
                              "base": { "ref": "main" }
                            }
                        """.trimIndent(),
                    )
                }

                else -> unexpectedRequest(request)
            }
        }

        val error = assertFailsWith<IllegalStateException> {
            executor.execute(
                buildJsonObject {
                    put("pr_url", "https://github.com/wireapp/wire-android/pull/4672")
                },
            )
        }

        assertContains(error.message.orEmpty(), "malformed")
        assertContains(error.message.orEmpty(), "title")
    }

    private fun createExecutor(
        recordedRequests: MutableList<RecordedGitHubRequest> = mutableListOf(),
        handler: MockRequestHandler,
    ): FetchGithubPullRequestBuiltInToolExecutor {
        val httpClient = HttpClient(
            MockEngine { request ->
                recordedRequests += RecordedGitHubRequest(
                    url = request.url.toString(),
                    accept = request.headers[HttpHeaders.Accept],
                    userAgent = request.headers[HttpHeaders.UserAgent],
                    apiVersion = request.headers["X-GitHub-Api-Version"],
                )
                handler(request)
            },
        )
        return FetchGithubPullRequestBuiltInToolExecutor(
            httpClient = httpClient,
            json = json,
        )
    }
}

private data class RecordedGitHubRequest(
    val url: String,
    val accept: String?,
    val userAgent: String?,
    val apiVersion: String?,
)

private fun unexpectedRequest(request: HttpRequestData): Nothing {
    error("Unexpected request: ${request.url} accept=${request.headers[HttpHeaders.Accept]}")
}

private fun MockRequestHandleScope.respondJson(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = body,
    status = status,
    headers = headersOf(
        HttpHeaders.ContentType,
        ContentType.Application.Json.toString(),
    ),
)

private fun MockRequestHandleScope.respondText(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = body,
    status = status,
    headers = headersOf(
        HttpHeaders.ContentType,
        ContentType.Text.Plain.toString(),
    ),
)

private fun buildLargeFilesPage(page: Int): String {
    val pageStart = (page - 1) * 100
    return buildString {
        append("[")
        repeat(100) { index ->
            if (index > 0) append(",")
            val fileIndex = pageStart + index + 1
            append(
                """
                    {
                      "filename": "src/File$fileIndex.kt",
                      "status": "modified",
                      "additions": 1,
                      "deletions": 0,
                      "changes": 1,
                      "patch": "@@ -0,0 +1 @@\n+value$fileIndex"
                    }
                """.trimIndent(),
            )
        }
        append("]")
    }
}
