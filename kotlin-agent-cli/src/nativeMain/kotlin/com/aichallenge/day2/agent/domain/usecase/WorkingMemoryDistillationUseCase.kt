package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.PromptRequestData
import com.aichallenge.day2.agent.domain.model.WorkingTaskState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class WorkingMemoryDistillationUseCase(
    private val sendPromptUseCase: SendPromptUseCase,
    private val json: Json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = false
    },
) {
    suspend fun distill(
        previousTaskState: WorkingTaskState?,
        recentMessages: List<ConversationMessage>,
        model: String,
    ): WorkingTaskState {
        require(recentMessages.isNotEmpty()) {
            "recentMessages must not be empty."
        }
        require(model.isNotBlank()) {
            "model must not be blank."
        }

        val previousStateJson = taskStateToJson(previousTaskState ?: WorkingTaskState())
        val serializedMessages = recentMessages
            .mapIndexed { index, message ->
                "${index + 1}. ${message.role.name}: ${message.content}"
            }
            .joinToString(separator = "\n")

        val prompt = buildString {
            appendLine("Previous task state JSON:")
            appendLine(previousStateJson)
            appendLine()
            appendLine("Recent messages:")
            appendLine(serializedMessages)
            appendLine()
            appendLine("Return the updated task state JSON only.")
        }.trim()

        val response = sendPromptUseCase.execute(
            prompt = PromptRequestData(
                systemPrompt = WORKING_MEMORY_SYSTEM_PROMPT,
                contextSystemMessages = emptyList(),
                messages = listOf(
                    ConversationMessage.user(prompt),
                ),
            ),
            temperature = 0.0,
            model = model,
        ).content.trim()

        return validateAndNormalizeTaskState(response)
    }

    private fun taskStateToJson(state: WorkingTaskState): String {
        val jsonObject = buildJsonObject {
            put(GOAL_KEY, JsonPrimitive(state.goal))
            put(CONSTRAINTS_KEY, JsonArray(state.constraints.map { JsonPrimitive(it) }))
            put(DECISIONS_KEY, JsonArray(state.decisions.map { JsonPrimitive(it) }))
            put(ASSUMPTIONS_KEY, JsonArray(state.assumptions.map { JsonPrimitive(it) }))
            put(OPEN_QUESTIONS_KEY, JsonArray(state.openQuestions.map { JsonPrimitive(it) }))
            put(NEXT_STEPS_KEY, JsonArray(state.nextSteps.map { JsonPrimitive(it) }))
            put(ARTIFACTS_KEY, JsonArray(state.artifacts.map { JsonPrimitive(it) }))
        }

        return json.encodeToString(JsonObject.serializer(), jsonObject)
    }

    private fun validateAndNormalizeTaskState(rawTaskState: String): WorkingTaskState {
        val parsed = runCatching {
            json.parseToJsonElement(rawTaskState)
        }.getOrElse {
            throw IllegalArgumentException("Working memory task state must be valid JSON.")
        }

        val jsonObject = parsed as? JsonObject
            ?: throw IllegalArgumentException("Working memory task state must be a JSON object.")
        val objectKeys = jsonObject.keys
        val missingKeys = TASK_STATE_KEYS.filterNot { key -> key in objectKeys }
        val extraKeys = objectKeys.filterNot { key -> key in TASK_STATE_KEYS }
        if (missingKeys.isNotEmpty() || extraKeys.isNotEmpty()) {
            throw IllegalArgumentException(
                "Working memory task state keys mismatch. Missing: ${missingKeys.joinToString()}; extra: ${extraKeys.joinToString()}",
            )
        }

        return WorkingTaskState(
            goal = normalizeStringValue(GOAL_KEY, jsonObject.getValue(GOAL_KEY)),
            constraints = normalizeStringArray(CONSTRAINTS_KEY, jsonObject.getValue(CONSTRAINTS_KEY)),
            decisions = normalizeStringArray(DECISIONS_KEY, jsonObject.getValue(DECISIONS_KEY)),
            assumptions = normalizeStringArray(ASSUMPTIONS_KEY, jsonObject.getValue(ASSUMPTIONS_KEY)),
            openQuestions = normalizeStringArray(OPEN_QUESTIONS_KEY, jsonObject.getValue(OPEN_QUESTIONS_KEY)),
            nextSteps = normalizeStringArray(NEXT_STEPS_KEY, jsonObject.getValue(NEXT_STEPS_KEY)),
            artifacts = normalizeStringArray(ARTIFACTS_KEY, jsonObject.getValue(ARTIFACTS_KEY)),
        )
    }

    private fun normalizeStringValue(
        key: String,
        value: JsonElement,
    ): String {
        val primitive = value as? JsonPrimitive
            ?: throw IllegalArgumentException("Working memory key '$key' must be a string.")
        if (!primitive.isString) {
            throw IllegalArgumentException("Working memory key '$key' must be a string.")
        }

        return primitive.content.trim()
    }

    private fun normalizeStringArray(
        key: String,
        value: JsonElement,
    ): List<String> {
        val array = value as? JsonArray
            ?: throw IllegalArgumentException("Working memory key '$key' must be an array of strings.")

        return array.mapIndexed { index, element ->
            val primitive = element as? JsonPrimitive
                ?: throw IllegalArgumentException("Working memory key '$key' item #${index + 1} must be a string.")
            if (!primitive.isString) {
                throw IllegalArgumentException("Working memory key '$key' item #${index + 1} must be a string.")
            }
            primitive.content.trim()
        }.filter { item -> item.isNotEmpty() }
            .distinct()
    }

    companion object {
        private const val GOAL_KEY = "goal"
        private const val CONSTRAINTS_KEY = "constraints"
        private const val DECISIONS_KEY = "decisions"
        private const val ASSUMPTIONS_KEY = "assumptions"
        private const val OPEN_QUESTIONS_KEY = "open_questions"
        private const val NEXT_STEPS_KEY = "next_steps"
        private const val ARTIFACTS_KEY = "artifacts"
        private val TASK_STATE_KEYS = listOf(
            GOAL_KEY,
            CONSTRAINTS_KEY,
            DECISIONS_KEY,
            ASSUMPTIONS_KEY,
            OPEN_QUESTIONS_KEY,
            NEXT_STEPS_KEY,
            ARTIFACTS_KEY,
        )
        private val WORKING_MEMORY_SYSTEM_PROMPT = """
            You maintain a strict structured working-memory task state for an AI assistant.
            Update the task state from previous state and recent messages.

            Rules:
            - Output valid JSON only, with no markdown and no explanation.
            - Use exactly these keys:
              goal, constraints, decisions, assumptions, open_questions, next_steps, artifacts
            - goal must be a string.
            - All other keys must be arrays of strings.
            - Keep items concise and factual.
            - Remove duplicates, obsolete details, and empty strings.
            - Do not store volatile readouts that become stale quickly, especially exact current-time answers.
            - If the user asked for the current time, keep only durable context such as that time lookup was needed; do not retain the exact returned clock reading.
            - Do not invent facts.
        """.trimIndent()
    }
}
