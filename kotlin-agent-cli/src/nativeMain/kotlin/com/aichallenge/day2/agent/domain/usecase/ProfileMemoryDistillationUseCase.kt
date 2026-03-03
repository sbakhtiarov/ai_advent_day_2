package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.MessageRole
import com.aichallenge.day2.agent.domain.model.ProfilePreferenceState
import com.aichallenge.day2.agent.domain.model.PromptRequestData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class ProfileMemoryDistillationUseCase(
    private val sendPromptUseCase: SendPromptUseCase,
    private val json: Json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = false
    },
) {
    suspend fun distill(
        previousPreferenceState: ProfilePreferenceState?,
        recentMessages: List<ConversationMessage>,
        model: String,
    ): ProfilePreferenceState {
        require(recentMessages.isNotEmpty()) {
            "recentMessages must not be empty."
        }
        require(model.isNotBlank()) {
            "model must not be blank."
        }

        val recentUserMessages = recentMessages.filter { message -> message.role == MessageRole.USER }
        require(recentUserMessages.isNotEmpty()) {
            "recentMessages must include at least one USER message."
        }

        val previousStateJson = preferenceStateToJson(previousPreferenceState ?: ProfilePreferenceState())
        val serializedMessages = recentUserMessages
            .mapIndexed { index, message ->
                "${index + 1}. ${message.role.name}: ${message.content}"
            }
            .joinToString(separator = "\n")

        val prompt = buildString {
            appendLine("Previous profile preference state JSON:")
            appendLine(previousStateJson)
            appendLine()
            appendLine("Recent user messages:")
            appendLine(serializedMessages)
            appendLine()
            appendLine("Return the updated profile preference state JSON only.")
        }.trim()

        val response = sendPromptUseCase.execute(
            prompt = PromptRequestData(
                systemPrompt = PROFILE_MEMORY_SYSTEM_PROMPT,
                contextSystemMessages = emptyList(),
                messages = listOf(
                    ConversationMessage.user(prompt),
                ),
            ),
            temperature = 0.0,
            model = model,
        ).content.trim()

        return validateAndNormalizePreferenceState(response)
    }

    private fun preferenceStateToJson(state: ProfilePreferenceState): String {
        val jsonObject = buildJsonObject {
            put(WRITING_STYLE_KEY, JsonPrimitive(state.writingStyle))
            put(TOOLING_PREFERENCES_KEY, JsonArray(state.toolingPreferences.map { JsonPrimitive(it) }))
            put(WORKFLOW_DEFAULTS_KEY, JsonArray(state.workflowDefaults.map { JsonPrimitive(it) }))
            put(STABLE_CONSTRAINTS_KEY, JsonArray(state.stableConstraints.map { JsonPrimitive(it) }))
            put(NAME_KEY, JsonPrimitive(state.name))
            put(WORK_KEY, JsonPrimitive(state.work))
            put(PROFESSION_KEY, JsonPrimitive(state.profession))
            put(OTHER_FACTS_KEY, JsonArray(state.otherFacts.map { JsonPrimitive(it) }))
        }

        return json.encodeToString(JsonObject.serializer(), jsonObject)
    }

    private fun validateAndNormalizePreferenceState(rawPreferenceState: String): ProfilePreferenceState {
        val parsed = runCatching {
            json.parseToJsonElement(rawPreferenceState)
        }.getOrElse {
            throw IllegalArgumentException("Profile memory preference state must be valid JSON.")
        }

        val jsonObject = parsed as? JsonObject
            ?: throw IllegalArgumentException("Profile memory preference state must be a JSON object.")
        val objectKeys = jsonObject.keys
        val missingKeys = PREFERENCE_STATE_KEYS.filterNot { key -> key in objectKeys }
        val extraKeys = objectKeys.filterNot { key -> key in PREFERENCE_STATE_KEYS }
        if (missingKeys.isNotEmpty() || extraKeys.isNotEmpty()) {
            throw IllegalArgumentException(
                "Profile memory preference state keys mismatch. Missing: ${missingKeys.joinToString()}; extra: ${extraKeys.joinToString()}",
            )
        }

        return ProfilePreferenceState(
            writingStyle = normalizeStringValue(WRITING_STYLE_KEY, jsonObject.getValue(WRITING_STYLE_KEY)),
            toolingPreferences = normalizeStringArray(
                TOOLING_PREFERENCES_KEY,
                jsonObject.getValue(TOOLING_PREFERENCES_KEY),
            ),
            workflowDefaults = normalizeStringArray(
                WORKFLOW_DEFAULTS_KEY,
                jsonObject.getValue(WORKFLOW_DEFAULTS_KEY),
            ),
            stableConstraints = normalizeStringArray(
                STABLE_CONSTRAINTS_KEY,
                jsonObject.getValue(STABLE_CONSTRAINTS_KEY),
            ),
            name = normalizeStringValue(NAME_KEY, jsonObject.getValue(NAME_KEY)),
            work = normalizeStringValue(WORK_KEY, jsonObject.getValue(WORK_KEY)),
            profession = normalizeStringValue(PROFESSION_KEY, jsonObject.getValue(PROFESSION_KEY)),
            otherFacts = normalizeStringArray(
                OTHER_FACTS_KEY,
                jsonObject.getValue(OTHER_FACTS_KEY),
            ),
        )
    }

    private fun normalizeStringValue(
        key: String,
        value: JsonElement,
    ): String {
        val primitive = value as? JsonPrimitive
            ?: throw IllegalArgumentException("Profile memory key '$key' must be a string.")
        if (!primitive.isString) {
            throw IllegalArgumentException("Profile memory key '$key' must be a string.")
        }

        return primitive.content.trim()
    }

    private fun normalizeStringArray(
        key: String,
        value: JsonElement,
    ): List<String> {
        val array = value as? JsonArray
            ?: throw IllegalArgumentException("Profile memory key '$key' must be an array of strings.")

        return array.mapIndexed { index, element ->
            val primitive = element as? JsonPrimitive
                ?: throw IllegalArgumentException("Profile memory key '$key' item #${index + 1} must be a string.")
            if (!primitive.isString) {
                throw IllegalArgumentException("Profile memory key '$key' item #${index + 1} must be a string.")
            }
            primitive.content.trim()
        }.filter { item -> item.isNotEmpty() }
            .distinct()
    }

    companion object {
        private const val WRITING_STYLE_KEY = "writing_style"
        private const val TOOLING_PREFERENCES_KEY = "tooling_preferences"
        private const val WORKFLOW_DEFAULTS_KEY = "workflow_defaults"
        private const val STABLE_CONSTRAINTS_KEY = "stable_constraints"
        private const val NAME_KEY = "name"
        private const val WORK_KEY = "work"
        private const val PROFESSION_KEY = "profession"
        private const val OTHER_FACTS_KEY = "other_facts"
        private val PREFERENCE_STATE_KEYS = listOf(
            WRITING_STYLE_KEY,
            TOOLING_PREFERENCES_KEY,
            WORKFLOW_DEFAULTS_KEY,
            STABLE_CONSTRAINTS_KEY,
            NAME_KEY,
            WORK_KEY,
            PROFESSION_KEY,
            OTHER_FACTS_KEY,
        )
        private val PROFILE_MEMORY_SYSTEM_PROMPT = """
            You maintain a strict structured profile memory for an AI assistant.
            Update the profile preference state from previous state and recent messages.

            Rules:
            - Output valid JSON only, with no markdown and no explanation.
            - Use exactly these keys:
              writing_style, tooling_preferences, workflow_defaults, stable_constraints,
              name, work, profession, other_facts
            - writing_style must be a string.
            - name, work, profession must be strings.
            - tooling_preferences, workflow_defaults, stable_constraints, other_facts must be arrays of strings.
            - Keep entries concise and stable.
            - Remove duplicates, obsolete details, and empty strings.
            - Collect facts only from explicit USER messages.
            - If USER messages do not provide new profile facts, keep previous state unchanged.
            - Do not assume or infer unstated preferences.
            - Do not invent facts.
        """.trimIndent()
    }
}
