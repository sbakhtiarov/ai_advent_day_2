package com.aichallenge.day2.agent.server

import com.aichallenge.day2.agent.review.InvalidPullRequestUrlException
import com.aichallenge.day2.agent.review.PullRequestFetcher
import com.aichallenge.day2.agent.review.PullRequestFetchException
import com.aichallenge.day2.agent.review.PullRequestReviewData
import com.aichallenge.day2.agent.review.PullRequestReviewFile
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private const val GITHUB_API_BASE_URL = "https://api.github.com"
private const val GITHUB_JSON_ACCEPT = "application/vnd.github+json"
private const val GITHUB_DIFF_ACCEPT = "application/vnd.github.v3.diff"
private const val GITHUB_API_VERSION = "2022-11-28"
private const val GITHUB_USER_AGENT = "kotlin-agent-cli-http-api"
private const val GITHUB_FILES_PAGE_SIZE = 100
private const val GITHUB_MAX_PR_FILES = 3_000

class JvmGithubPullRequestFetcher(
    private val httpClient: HttpClient,
    private val json: Json,
) : PullRequestFetcher {
    override suspend fun fetch(prUrl: String): PullRequestReviewData {
        val reference = parsePullRequestUrl(prUrl)
        val warnings = linkedSetOf<String>()

        val pullRequest = fetchPullRequest(reference)
        val files = fetchPullRequestFiles(reference, warnings)
        val diffSections = parseRawDiff(fetchPullRequestDiff(reference))
        val changedFiles = files.map { file -> mergeFileWithDiff(file, diffSections) }

        return PullRequestReviewData(
            prUrl = reference.normalizedUrl,
            owner = reference.owner,
            repo = reference.repo,
            pullNumber = reference.pullNumber,
            title = pullRequest.title,
            description = pullRequest.body,
            headBranch = pullRequest.head.ref,
            baseBranch = pullRequest.base.ref,
            changedFilesCount = changedFiles.size,
            warnings = warnings.toList(),
            changedFiles = changedFiles,
        )
    }

    private fun parsePullRequestUrl(rawUrl: String): GithubPullRequestReference {
        val url = runCatching { Url(rawUrl.trim()) }
            .getOrElse {
                throw InvalidPullRequestUrlException(
                    "Pull request URL must be a valid public GitHub pull request URL like https://github.com/owner/repo/pull/123.",
                )
            }

        if (url.protocol.name.lowercase() != "https") {
            throw InvalidPullRequestUrlException("Pull request URL must use https://github.com.")
        }
        if (url.host.lowercase() != "github.com") {
            throw InvalidPullRequestUrlException("Pull request URL must point to github.com.")
        }

        val segments = url.encodedPath
            .trimEnd('/')
            .split('/')
            .filter { segment -> segment.isNotBlank() }
        if (segments.size != 4 || segments[2] != "pull") {
            throw InvalidPullRequestUrlException(
                "Pull request URL must match https://github.com/{owner}/{repo}/pull/{number}.",
            )
        }

        val owner = segments[0].trim()
        val repo = segments[1].trim()
        val pullNumber = segments[3].toLongOrNull()
            ?: throw InvalidPullRequestUrlException(
                "Pull request URL must contain a numeric pull request number.",
            )
        if (owner.isEmpty() || repo.isEmpty()) {
            throw InvalidPullRequestUrlException(
                "Pull request URL must match https://github.com/{owner}/{repo}/pull/{number}.",
            )
        }

        return GithubPullRequestReference(
            owner = owner,
            repo = repo,
            pullNumber = pullNumber,
            normalizedUrl = "https://github.com/$owner/$repo/pull/$pullNumber",
        )
    }

    private suspend fun fetchPullRequest(reference: GithubPullRequestReference): GithubPullRequestResponse {
        val response = getJson<GithubPullRequestResponse>(
            url = reference.apiUrl(),
            acceptHeader = GITHUB_JSON_ACCEPT,
            displayUrl = reference.normalizedUrl,
        )
        if (response.title.isBlank()) {
            throw PullRequestFetchException(
                "GitHub returned malformed pull request data for '${reference.normalizedUrl}': missing title.",
            )
        }
        if (response.head.ref.isBlank() || response.base.ref.isBlank()) {
            throw PullRequestFetchException(
                "GitHub returned malformed pull request data for '${reference.normalizedUrl}': missing head/base branch.",
            )
        }
        return response
    }

    private suspend fun fetchPullRequestFiles(
        reference: GithubPullRequestReference,
        warnings: MutableSet<String>,
    ): List<GithubPullRequestFileResponse> {
        val files = mutableListOf<GithubPullRequestFileResponse>()
        var page = 1

        while (true) {
            val batch = getJson<List<GithubPullRequestFileResponse>>(
                url = "${reference.apiUrl()}/files",
                acceptHeader = GITHUB_JSON_ACCEPT,
                displayUrl = "${reference.normalizedUrl}/files?page=$page",
                queryParameters = listOf(
                    "per_page" to GITHUB_FILES_PAGE_SIZE.toString(),
                    "page" to page.toString(),
                ),
            )
            if (batch.isEmpty()) {
                break
            }

            batch.forEach { file ->
                if (file.filename.isBlank()) {
                    throw PullRequestFetchException(
                        "GitHub returned malformed pull request file data for '${reference.normalizedUrl}': missing filename.",
                    )
                }
                files += file
            }
            if (files.size >= GITHUB_MAX_PR_FILES) {
                warnings += "GitHub may truncate pull request file lists after 3000 files."
                break
            }
            page += 1
        }

        return files
    }

    private suspend fun fetchPullRequestDiff(reference: GithubPullRequestReference): String {
        val response = httpClient.get(reference.apiUrl()) {
            header(HttpHeaders.Accept, GITHUB_DIFF_ACCEPT)
            header(HttpHeaders.UserAgent, GITHUB_USER_AGENT)
            header("X-GitHub-Api-Version", GITHUB_API_VERSION)
        }
        val body = response.bodyAsText()
        ensureSuccessfulGitHubResponse(
            status = response.status,
            body = body,
            displayUrl = reference.normalizedUrl,
        )
        return body
    }

    private suspend inline fun <reified T> getJson(
        url: String,
        acceptHeader: String,
        displayUrl: String,
        queryParameters: List<Pair<String, String>> = emptyList(),
    ): T {
        val response = httpClient.get(url) {
            header(HttpHeaders.Accept, acceptHeader)
            header(HttpHeaders.UserAgent, GITHUB_USER_AGENT)
            header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            queryParameters.forEach { (name, value) ->
                parameter(name, value)
            }
        }
        val body = response.bodyAsText()
        ensureSuccessfulGitHubResponse(
            status = response.status,
            body = body,
            displayUrl = displayUrl,
        )
        return runCatching {
            json.decodeFromString<T>(body)
        }.getOrElse { error ->
            throw PullRequestFetchException(
                "GitHub returned malformed JSON for '$displayUrl'.",
                error,
            )
        }
    }

    private fun ensureSuccessfulGitHubResponse(
        status: HttpStatusCode,
        body: String,
        displayUrl: String,
    ) {
        if (status.value in 200..299) {
            return
        }

        val message = extractGitHubErrorMessage(body)
        when (status) {
            HttpStatusCode.NotFound -> {
                throw PullRequestFetchException(
                    "GitHub pull request not found or not publicly accessible: '$displayUrl'.",
                )
            }

            HttpStatusCode.Forbidden,
            HttpStatusCode.TooManyRequests,
            -> {
                val suffix = message?.let { detail -> ": $detail" }.orEmpty()
                throw PullRequestFetchException(
                    "GitHub API rate limit or access failure while fetching '$displayUrl'$suffix",
                )
            }

            else -> {
                val suffix = message?.let { detail -> ": $detail" }.orEmpty()
                throw PullRequestFetchException(
                    "GitHub API request failed with HTTP ${status.value} while fetching '$displayUrl'$suffix",
                )
            }
        }
    }

    private fun extractGitHubErrorMessage(body: String): String? {
        return runCatching {
            json.decodeFromString<GithubErrorResponse>(body).message
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun parseRawDiff(rawDiff: String): List<ParsedDiffSection> {
        if (rawDiff.isBlank()) {
            return emptyList()
        }

        val sections = mutableListOf<List<String>>()
        var currentSection = mutableListOf<String>()
        rawDiff.lineSequence().forEach { line ->
            if (line.startsWith("diff --git ")) {
                if (currentSection.isNotEmpty()) {
                    sections += currentSection.toList()
                }
                currentSection = mutableListOf(line)
            } else if (currentSection.isNotEmpty()) {
                currentSection += line
            }
        }
        if (currentSection.isNotEmpty()) {
            sections += currentSection.toList()
        }

        return sections.mapNotNull { section -> parseRawDiffSection(section) }
    }

    private fun parseRawDiffSection(lines: List<String>): ParsedDiffSection? {
        if (lines.isEmpty()) return null

        val headerPaths = parseDiffGitHeader(lines.first())
        var previousPath: String? = headerPaths?.first
        var currentPath: String? = headerPaths?.second

        lines.forEach { line ->
            when {
                line.startsWith("rename from ") -> previousPath = line.removePrefix("rename from ").trim()
                line.startsWith("rename to ") -> currentPath = line.removePrefix("rename to ").trim()
                line.startsWith("--- ") -> {
                    parseDiffMarkerPath(line.removePrefix("--- ").trim())?.let { path ->
                        previousPath = path
                    }
                }

                line.startsWith("+++ ") -> {
                    parseDiffMarkerPath(line.removePrefix("+++ ").trim())?.let { path ->
                        currentPath = path
                    }
                }
            }
        }

        if (currentPath == null && previousPath == null) {
            return null
        }

        return ParsedDiffSection(
            currentPath = currentPath,
            previousPath = previousPath,
            diffText = lines.joinToString(separator = "\n"),
        )
    }

    private fun parseDiffGitHeader(line: String): Pair<String, String>? {
        if (!line.startsWith("diff --git a/")) {
            return null
        }
        val markerIndex = line.lastIndexOf(" b/")
        if (markerIndex <= "diff --git a/".length) {
            return null
        }
        val previousPath = line.substring("diff --git a/".length, markerIndex)
        val currentPath = line.substring(markerIndex + " b/".length)
        if (previousPath.isBlank() || currentPath.isBlank()) {
            return null
        }
        return previousPath to currentPath
    }

    private fun parseDiffMarkerPath(rawPath: String): String? {
        val normalized = rawPath.removeSurrounding("\"")
        if (normalized == "/dev/null") {
            return null
        }
        return when {
            normalized.startsWith("a/") || normalized.startsWith("b/") -> normalized.substring(2)
            normalized.isBlank() -> null
            else -> normalized
        }
    }

    private fun mergeFileWithDiff(
        file: GithubPullRequestFileResponse,
        diffSections: List<ParsedDiffSection>,
    ): PullRequestReviewFile {
        val matchingSection = diffSections.firstOrNull { section ->
            section.matches(file.filename, file.previousFilename)
        }
        val diff = when {
            matchingSection != null -> matchingSection.diffText
            !file.patch.isNullOrBlank() -> file.patch
            else -> null
        }
        val diffSource = when {
            matchingSection != null -> "raw_diff"
            !file.patch.isNullOrBlank() -> "files_api_patch"
            else -> "unavailable"
        }

        return PullRequestReviewFile(
            path = file.filename,
            previousPath = file.previousFilename?.trim()?.takeIf { it.isNotEmpty() },
            status = file.status.trim().ifEmpty { "unknown" },
            additions = file.additions,
            deletions = file.deletions,
            changes = file.changes,
            diff = diff,
            diffSource = diffSource,
            diffAvailable = diff != null,
        )
    }
}

@Serializable
private data class GithubPullRequestResponse(
    val title: String = "",
    val body: String? = null,
    val head: GithubPullRequestBranchResponse = GithubPullRequestBranchResponse(),
    val base: GithubPullRequestBranchResponse = GithubPullRequestBranchResponse(),
)

@Serializable
private data class GithubPullRequestBranchResponse(
    val ref: String = "",
)

@Serializable
private data class GithubPullRequestFileResponse(
    val filename: String = "",
    val status: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val changes: Int = 0,
    val patch: String? = null,
    @SerialName("previous_filename")
    val previousFilename: String? = null,
)

@Serializable
private data class GithubErrorResponse(
    val message: String? = null,
)

private data class GithubPullRequestReference(
    val owner: String,
    val repo: String,
    val pullNumber: Long,
    val normalizedUrl: String,
) {
    fun apiUrl(): String = "$GITHUB_API_BASE_URL/repos/$owner/$repo/pulls/$pullNumber"
}

private data class ParsedDiffSection(
    val currentPath: String?,
    val previousPath: String?,
    val diffText: String,
) {
    fun matches(path: String, previousFileName: String?): Boolean {
        return sequenceOf(path, previousFileName)
            .filterNotNull()
            .map(String::trim)
            .any { candidate ->
                candidate == currentPath || candidate == previousPath
            }
    }
}
