package com.aichallenge.day2.agent.review

import com.aichallenge.day2.agent.data.tools.BuiltInPrivateToolProvider
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.LlmToolCapabilities
import com.aichallenge.day2.agent.domain.model.PromptRequestData
import com.aichallenge.day2.agent.domain.service.WireAppRagRetriever
import com.aichallenge.day2.agent.domain.usecase.SendPromptUseCase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val FETCH_GITHUB_PULL_REQUEST_TOOL_ID = "fetch_github_pull_request"

class NativePullRequestFetcher(
    private val builtInPrivateToolProvider: BuiltInPrivateToolProvider,
) : PullRequestFetcher {
    override suspend fun fetch(prUrl: String): PullRequestReviewData {
        val result = builtInPrivateToolProvider.execute(
            toolId = FETCH_GITHUB_PULL_REQUEST_TOOL_ID,
            arguments = buildJsonObject {
                put("pr_url", prUrl)
            },
        )
        if (result.isError) {
            val message = extractPrivateToolText(result.content)
                .takeIf { it.isNotBlank() }
                ?: "Built-in tool '$FETCH_GITHUB_PULL_REQUEST_TOOL_ID' failed."
            throw PullRequestFetchException(message)
        }
        val structuredContent = result.structuredContent
            ?: throw PullRequestFetchException(
                "Built-in tool '$FETCH_GITHUB_PULL_REQUEST_TOOL_ID' returned no structured content.",
            )
        return parsePullRequest(structuredContent)
    }

    private fun extractPrivateToolText(content: JsonArray): String {
        return content.mapNotNull { item ->
            item.jsonObject["text"]?.jsonPrimitive?.contentOrNull
        }.joinToString(separator = "\n")
    }

    private fun parsePullRequest(structuredContent: JsonObject): PullRequestReviewData {
        return PullRequestReviewData(
            prUrl = structuredContent.requiredString("pr_url"),
            owner = structuredContent.requiredString("owner"),
            repo = structuredContent.requiredString("repo"),
            pullNumber = structuredContent.requiredLong("pull_number"),
            title = structuredContent.requiredString("title"),
            description = structuredContent.optionalString("description"),
            headBranch = structuredContent.requiredString("head_branch"),
            baseBranch = structuredContent.requiredString("base_branch"),
            changedFilesCount = structuredContent.requiredInt("changed_files_count"),
            warnings = structuredContent.requiredArray("warnings").map { warning ->
                warning.jsonPrimitive.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw PullRequestFetchException(
                        "Built-in tool '$FETCH_GITHUB_PULL_REQUEST_TOOL_ID' returned malformed warnings data.",
                    )
            },
            changedFiles = structuredContent.requiredArray("changed_files").map { entry ->
                val file = entry.jsonObject
                PullRequestReviewFile(
                    path = file.requiredString("path"),
                    previousPath = file.optionalString("previous_path"),
                    status = file.requiredString("status"),
                    additions = file.requiredInt("additions"),
                    deletions = file.requiredInt("deletions"),
                    changes = file.requiredInt("changes"),
                    diff = file.optionalString("diff"),
                    diffSource = file.requiredString("diff_source"),
                    diffAvailable = file.requiredBoolean("diff_available"),
                )
            },
        )
    }
}

class NativeReviewRagRetriever(
    private val wireAppRagRetriever: WireAppRagRetriever,
) : ReviewRagRetriever {
    override suspend fun retrieve(question: String): List<ReviewRagChunk> {
        return wireAppRagRetriever.retrieve(question).map { chunk ->
            ReviewRagChunk(
                chunkId = chunk.chunkId,
                sectionName = chunk.sectionName,
                headingPath = chunk.headingPath,
                sourcePath = chunk.sourcePath,
                score = chunk.score,
                content = chunk.content,
            )
        }
    }
}

class NativePullRequestReviewModelClient(
    private val sendPromptUseCase: SendPromptUseCase,
    override val modelId: String,
    override val contextWindowTokens: Int?,
    private val temperature: Double?,
) : PullRequestReviewModelClient {
    override suspend fun review(
        systemPrompt: String,
        contextSystemMessage: String,
        userPrompt: String,
    ): PullRequestReviewModelResponse {
        val response = sendPromptUseCase.execute(
            prompt = PromptRequestData(
                systemPrompt = systemPrompt,
                contextSystemMessages = listOf(contextSystemMessage),
                messages = listOf(
                    ConversationMessage.user(userPrompt),
                ),
                toolCapabilities = LlmToolCapabilities(),
            ),
            temperature = temperature,
            model = modelId,
        )
        return PullRequestReviewModelResponse(
            reviewMarkdown = response.content,
            usage = response.usage?.let { usage ->
                ReviewTokenUsage(
                    totalTokens = usage.totalTokens,
                    inputTokens = usage.inputTokens,
                    outputTokens = usage.outputTokens,
                )
            },
        )
    }
}

private fun JsonObject.requiredString(key: String): String {
    return this[key]?.jsonPrimitive?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: throw PullRequestFetchException(
            "Built-in tool '$FETCH_GITHUB_PULL_REQUEST_TOOL_ID' returned malformed data: missing '$key'.",
        )
}

private fun JsonObject.optionalString(key: String): String? {
    return this[key]?.jsonPrimitive?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

private fun JsonObject.requiredInt(key: String): Int {
    return this[key]?.jsonPrimitive?.contentOrNull
        ?.toIntOrNull()
        ?: throw PullRequestFetchException(
            "Built-in tool '$FETCH_GITHUB_PULL_REQUEST_TOOL_ID' returned malformed data: missing '$key'.",
        )
}

private fun JsonObject.requiredLong(key: String): Long {
    return this[key]?.jsonPrimitive?.contentOrNull
        ?.toLongOrNull()
        ?: throw PullRequestFetchException(
            "Built-in tool '$FETCH_GITHUB_PULL_REQUEST_TOOL_ID' returned malformed data: missing '$key'.",
        )
}

private fun JsonObject.requiredBoolean(key: String): Boolean {
    return this[key]?.jsonPrimitive?.contentOrNull
        ?.toBooleanStrictOrNull()
        ?: throw PullRequestFetchException(
            "Built-in tool '$FETCH_GITHUB_PULL_REQUEST_TOOL_ID' returned malformed data: missing '$key'.",
        )
}

private fun JsonObject.requiredArray(key: String) =
    this[key] as? JsonArray
        ?: throw PullRequestFetchException(
            "Built-in tool '$FETCH_GITHUB_PULL_REQUEST_TOOL_ID' returned malformed data: missing '$key'.",
        )
