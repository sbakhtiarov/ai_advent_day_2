package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.BranchTopicCatalogEntry
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class BranchClassificationUseCase(
    private val sendPromptUseCase: SendPromptUseCase,
    private val json: Json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = false
    },
) {
    suspend fun classify(
        existingTopics: List<BranchTopicCatalogEntry>,
        userPrompt: String,
        assistantResponse: String,
        fallbackTopic: String,
        fallbackSubtopic: String,
        model: String,
    ): BranchClassificationResult {
        val normalizedFallbackTopic = normalizeName(fallbackTopic, DEFAULT_TOPIC_NAME)
        val normalizedFallbackSubtopic = normalizeName(fallbackSubtopic, DEFAULT_SUBTOPIC_NAME)

        repeat(MAX_ATTEMPTS) {
            val parsed = runCatching {
                val response = sendPromptUseCase.execute(
                    conversation = listOf(
                        ConversationMessage.system(CLASSIFICATION_SYSTEM_PROMPT),
                        ConversationMessage.user(
                            buildClassificationPrompt(
                                existingTopics = existingTopics,
                                userPrompt = userPrompt,
                                assistantResponse = assistantResponse,
                            ),
                        ),
                    ),
                    temperature = 0.0,
                    model = model,
                )
                parseResponse(response.content)
            }.getOrNull()

            if (parsed != null) {
                return BranchClassificationResult(
                    topic = parsed.topic,
                    subtopic = parsed.subtopic,
                    usedFallback = false,
                )
            }
        }

        return BranchClassificationResult(
            topic = normalizedFallbackTopic,
            subtopic = normalizedFallbackSubtopic,
            usedFallback = true,
        )
    }

    private fun parseResponse(content: String): ParsedBranchClassification {
        val candidate = extractJsonObject(content)
        val parsed = json.parseToJsonElement(candidate) as? JsonObject
            ?: throw IllegalArgumentException("Branch classification response must be a JSON object.")
        if (parsed.keys != setOf(TOPIC_KEY, SUBTOPIC_KEY)) {
            throw IllegalArgumentException("Branch classification response must contain only '$TOPIC_KEY' and '$SUBTOPIC_KEY'.")
        }

        val topic = parsed[TOPIC_KEY] as? JsonPrimitive
            ?: throw IllegalArgumentException("Branch classification response must contain '$TOPIC_KEY'.")
        val subtopic = parsed[SUBTOPIC_KEY] as? JsonPrimitive
            ?: throw IllegalArgumentException("Branch classification response must contain '$SUBTOPIC_KEY'.")

        if (!topic.isString || !subtopic.isString) {
            throw IllegalArgumentException("Branch classification keys must be strings.")
        }

        val normalizedTopic = normalizeName(topic.content, DEFAULT_TOPIC_NAME)
        if (normalizedTopic.isEmpty()) {
            throw IllegalArgumentException("Branch classification topic must not be blank.")
        }

        val normalizedSubtopic = normalizeName(subtopic.content, DEFAULT_SUBTOPIC_NAME)

        return ParsedBranchClassification(
            topic = normalizedTopic,
            subtopic = normalizedSubtopic,
        )
    }

    private fun buildClassificationPrompt(
        existingTopics: List<BranchTopicCatalogEntry>,
        userPrompt: String,
        assistantResponse: String,
    ): String {
        val serializedTopics = if (existingTopics.isEmpty()) {
            "(none)"
        } else {
            existingTopics.joinToString(separator = "\n") { topic ->
                val subtopics = if (topic.subtopics.isEmpty()) {
                    "(none)"
                } else {
                    topic.subtopics.joinToString(separator = ", ")
                }
                "- ${topic.topic}: [$subtopics]"
            }
        }

        return buildString {
            appendLine("Existing topic/subtopic map:")
            appendLine(serializedTopics)
            appendLine()
            appendLine("Latest user prompt:")
            appendLine(userPrompt)
            appendLine()
            appendLine("Latest assistant response:")
            appendLine(assistantResponse)
            appendLine()
            appendLine("Return JSON only in this exact schema:")
            appendLine("{\"topic\":\"...\",\"subtopic\":\"...\"}")
            appendLine("Use one of existing topics/subtopics when appropriate; otherwise create a concise new name.")
            appendLine("If subtopic is unclear, use \"General\".")
        }.trim()
    }

    private fun extractJsonObject(rawContent: String): String {
        val trimmed = rawContent.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1)
        }
        return trimmed
    }

    private fun normalizeName(
        raw: String,
        fallback: String,
    ): String {
        val normalized = raw.trim()
            .split(Regex("\\s+"))
            .filter { token -> token.isNotBlank() }
            .joinToString(separator = " ")

        return normalized.ifEmpty { fallback }
    }

    private data class ParsedBranchClassification(
        val topic: String,
        val subtopic: String,
    )

    companion object {
        private const val TOPIC_KEY = "topic"
        private const val SUBTOPIC_KEY = "subtopic"
        private const val DEFAULT_TOPIC_NAME = "General"
        private const val DEFAULT_SUBTOPIC_NAME = "General"
        private const val MAX_ATTEMPTS = 2
        private val CLASSIFICATION_SYSTEM_PROMPT = """
            You classify conversation turns into topic and subtopic for AI session memory routing.
            Always output valid JSON only, no markdown, no explanation.
            Keep topic/subtopic names concise and human readable.
            Reuse existing topic/subtopic names when they match the turn.
        """.trimIndent()
    }
}

data class BranchClassificationResult(
    val topic: String,
    val subtopic: String,
    val usedFallback: Boolean,
)
