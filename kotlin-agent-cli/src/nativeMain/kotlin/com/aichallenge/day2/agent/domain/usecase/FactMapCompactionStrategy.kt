package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.SessionCompactionSummaryMode
import com.aichallenge.day2.agent.domain.model.SessionCompactionStrategy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class FactMapCompactionStrategy(
    private val sendPromptUseCase: SendPromptUseCase,
    private val json: Json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = false
    },
) : SessionCompactionStrategy {
    override val id: String = STRATEGY_ID
    override val summaryMode: SessionCompactionSummaryMode = SessionCompactionSummaryMode.GENERATE

    override suspend fun compact(
        previousSummary: String?,
        messagesToCompact: List<ConversationMessage>,
        model: String,
    ): String {
        require(messagesToCompact.isNotEmpty()) {
            "messagesToCompact must not be empty."
        }
        require(model.isNotBlank()) {
            "model must not be blank."
        }

        val conversation = listOf(
            ConversationMessage.system(FACT_MAP_SYSTEM_PROMPT),
            ConversationMessage.user(
                buildFactMapPrompt(
                    previousSummary = normalizeOrDefaultPreviousSummary(previousSummary),
                    messagesToCompact = messagesToCompact,
                ),
            ),
        )

        val response = sendPromptUseCase.execute(
            conversation = conversation,
            temperature = 0.0,
            model = model,
        ).content.trim()

        return validateAndNormalizeFactMap(response)
    }

    private fun buildFactMapPrompt(
        previousSummary: String,
        messagesToCompact: List<ConversationMessage>,
    ): String {
        val serializedMessages = messagesToCompact
            .mapIndexed { index, message ->
                "${index + 1}. ${message.role.name}: ${message.content}"
            }
            .joinToString(separator = "\n")

        return buildString {
            appendLine("Previous fact map JSON:")
            appendLine(previousSummary)
            appendLine()
            appendLine("New messages to compact:")
            appendLine(serializedMessages)
            appendLine()
            appendLine("Return the updated fact map JSON only.")
        }.trim()
    }

    private fun normalizeOrDefaultPreviousSummary(previousSummary: String?): String {
        val trimmedSummary = previousSummary?.trim().orEmpty()
        if (trimmedSummary.isEmpty()) {
            return EMPTY_FACT_MAP_JSON
        }

        return runCatching {
            validateAndNormalizeFactMap(trimmedSummary)
        }.getOrElse {
            EMPTY_FACT_MAP_JSON
        }
    }

    private fun validateAndNormalizeFactMap(rawSummary: String): String {
        val parsed = runCatching {
            json.parseToJsonElement(rawSummary)
        }.getOrElse {
            throw IllegalArgumentException("Fact map summary must be valid JSON.")
        }

        val jsonObject = parsed as? JsonObject
            ?: throw IllegalArgumentException("Fact map summary must be a JSON object.")
        val objectKeys = jsonObject.keys
        val missingKeys = FACT_MAP_KEYS.filterNot { key -> key in objectKeys }
        val extraKeys = objectKeys.filterNot { key -> key in FACT_MAP_KEYS }
        if (missingKeys.isNotEmpty() || extraKeys.isNotEmpty()) {
            throw IllegalArgumentException(
                "Fact map summary keys mismatch. Missing: ${missingKeys.joinToString()}; extra: ${extraKeys.joinToString()}",
            )
        }

        val normalized = linkedMapOf<String, JsonElement>()
        FACT_MAP_KEYS.forEach { key ->
            val value = jsonObject.getValue(key)
            normalized[key] = when (key) {
                GOAL_KEY -> normalizeGoal(value)
                else -> normalizeStringArray(key, value)
            }
        }

        return json.encodeToString(JsonObject.serializer(), JsonObject(normalized))
    }

    private fun normalizeGoal(value: JsonElement): JsonPrimitive {
        val primitive = value as? JsonPrimitive
            ?: throw IllegalArgumentException("Fact map key '$GOAL_KEY' must be a string.")
        if (!primitive.isString) {
            throw IllegalArgumentException("Fact map key '$GOAL_KEY' must be a string.")
        }

        return JsonPrimitive(primitive.content.trim())
    }

    private fun normalizeStringArray(
        key: String,
        value: JsonElement,
    ): JsonArray {
        val array = value as? JsonArray
            ?: throw IllegalArgumentException("Fact map key '$key' must be an array of strings.")

        val normalizedItems = array.mapIndexed { index, element ->
            val primitive = element as? JsonPrimitive
                ?: throw IllegalArgumentException("Fact map key '$key' item #${index + 1} must be a string.")
            if (!primitive.isString) {
                throw IllegalArgumentException("Fact map key '$key' item #${index + 1} must be a string.")
            }
            primitive.content.trim()
        }.filter { item -> item.isNotEmpty() }
            .distinct()
            .map { item -> JsonPrimitive(item) }

        return JsonArray(normalizedItems)
    }

    companion object {
        private const val STRATEGY_ID = "fact-map-v1"
        private const val GOAL_KEY = "goal"
        private val FACT_MAP_KEYS = listOf(
            GOAL_KEY,
            "constraints",
            "decisions",
            "preferences",
            "agreements",
        )
        private val FACT_MAP_SYSTEM_PROMPT = """
            You maintain structured conversation memory for an AI assistant.
            Update a fact map based on previous fact map and new compacted messages.

            Capture only durable, decision-relevant facts:
            - goal
            - constraints
            - decisions
            - preferences
            - agreements

            Rules:
            - Keep facts concise and factual.
            - Remove duplicates and obsolete or superseded facts.
            - If a new message contradicts an old fact, keep the newest valid fact.
            - Do not invent facts.
            - Output valid JSON only, no markdown, no explanation.
            - Use exactly this schema:
            {
              "goal": "",
              "constraints": [],
              "decisions": [],
              "preferences": [],
              "agreements": []
            }
        """.trimIndent()
        private val EMPTY_FACT_MAP_JSON = buildJsonObject {
            put(GOAL_KEY, JsonPrimitive(""))
            put("constraints", JsonArray(emptyList()))
            put("decisions", JsonArray(emptyList()))
            put("preferences", JsonArray(emptyList()))
            put("agreements", JsonArray(emptyList()))
        }.toString()
    }
}
