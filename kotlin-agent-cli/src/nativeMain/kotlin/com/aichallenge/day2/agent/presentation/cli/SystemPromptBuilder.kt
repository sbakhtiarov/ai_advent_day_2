package com.aichallenge.day2.agent.presentation.cli

import com.aichallenge.day2.agent.domain.model.ProfilePreferenceState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SystemPromptBuilder {
    fun build(
        basePrompt: String,
        selection: ConfigMenuSelection,
        userDefinedProfile: ProfilePreferenceState? = null,
    ): String {
        val stopInstruction = selection.stopSequence.takeIf { it.isNotBlank() }?.let { stopText ->
            """When user sends "$stopText" stop generating questions and provide short summary"""
        } ?: "No explicit stop sequence behavior."

        val outputRulesBlock = """
            $basePrompt
            
            Output rules:
            - Format: ${selection.format.readableName()}
            - Max output tokens: ${selection.maxOutputTokens?.toString() ?: "(none)"}
            - Stop sequence: ${selection.stopSequence.ifBlank { "(none)" }}
            - Stop behavior: $stopInstruction
            - Follow output rules exactly.
        """.trimIndent()
        val userDefinedProfileBlock = buildUserDefinedProfileBlock(userDefinedProfile)
            ?: return outputRulesBlock
        return "$outputRulesBlock\n\n$userDefinedProfileBlock"
    }

    private fun OutputFormatOption.readableName(): String = when (this) {
        OutputFormatOption.PLAIN_TEXT -> "Plain text"
        OutputFormatOption.MARKDOWN -> "Markdown"
        OutputFormatOption.JSON -> "JSON"
        OutputFormatOption.TABLE -> "Table"
    }

    private fun buildUserDefinedProfileBlock(profile: ProfilePreferenceState?): String? {
        if (profile == null) {
            return null
        }

        val normalizedWritingStyle = profile.writingStyle.trim()
        val normalizedToolingPreferences = normalizeNonEmptyDistinct(profile.toolingPreferences)
        val normalizedWorkflowDefaults = normalizeNonEmptyDistinct(profile.workflowDefaults)
        val normalizedStableConstraints = normalizeNonEmptyDistinct(profile.stableConstraints)
        val normalizedName = profile.name.trim()
        val normalizedWork = profile.work.trim()
        val normalizedProfession = profile.profession.trim()
        val normalizedOtherFacts = normalizeNonEmptyDistinct(profile.otherFacts)

        val fields = mutableListOf<Pair<String, String>>()
        if (normalizedWritingStyle.isNotEmpty()) {
            fields += "writing_style" to json.encodeToString(String.serializer(), normalizedWritingStyle)
        }
        if (normalizedToolingPreferences.isNotEmpty()) {
            fields += "tooling_preferences" to json.encodeToString(
                ListSerializer(String.serializer()),
                normalizedToolingPreferences,
            )
        }
        if (normalizedWorkflowDefaults.isNotEmpty()) {
            fields += "workflow_defaults" to json.encodeToString(
                ListSerializer(String.serializer()),
                normalizedWorkflowDefaults,
            )
        }
        if (normalizedStableConstraints.isNotEmpty()) {
            fields += "stable_constraints" to json.encodeToString(
                ListSerializer(String.serializer()),
                normalizedStableConstraints,
            )
        }
        if (normalizedName.isNotEmpty()) {
            fields += "name" to json.encodeToString(String.serializer(), normalizedName)
        }
        if (normalizedWork.isNotEmpty()) {
            fields += "work" to json.encodeToString(String.serializer(), normalizedWork)
        }
        if (normalizedProfession.isNotEmpty()) {
            fields += "profession" to json.encodeToString(String.serializer(), normalizedProfession)
        }
        if (normalizedOtherFacts.isNotEmpty()) {
            fields += "other_facts" to json.encodeToString(
                ListSerializer(String.serializer()),
                normalizedOtherFacts,
            )
        }
        if (fields.isEmpty()) {
            return null
        }

        val body = fields.joinToString(
            separator = ",",
            prefix = "{",
            postfix = "}",
        ) { (key, value) ->
            "\"$key\":$value"
        }
        return """
            User-defined profile defaults (highest priority):
            $body
            - These user-defined defaults override inferred and distilled profile memory.
            - Do not contradict these defaults unless the user explicitly changes this file.
        """.trimIndent()
    }

    private fun normalizeNonEmptyDistinct(values: List<String>): List<String> {
        return values.map { value -> value.trim() }
            .filter { value -> value.isNotEmpty() }
            .distinct()
    }

    companion object {
        private val json: Json = Json {
            prettyPrint = false
            encodeDefaults = false
        }
    }
}
