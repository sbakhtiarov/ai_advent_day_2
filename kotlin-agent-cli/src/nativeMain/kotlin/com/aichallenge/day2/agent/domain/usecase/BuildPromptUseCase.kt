package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.MessageRole
import com.aichallenge.day2.agent.domain.model.ProfileMemoryState
import com.aichallenge.day2.agent.domain.model.PromptRequestData
import com.aichallenge.day2.agent.domain.model.WorkingTaskState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class SessionPromptData(
    val messages: List<ConversationMessage>,
    val summarySystemMessage: String? = null,
)

data class BuildPromptRequest(
    val systemPrompt: String,
    val session: SessionPromptData,
    val userPrompt: String,
    val workingTaskState: WorkingTaskState? = null,
    val profileMemoryState: ProfileMemoryState? = null,
)

class BuildPromptUseCase {
    fun buildContext(
        systemPrompt: String,
        session: SessionPromptData,
        workingTaskState: WorkingTaskState? = null,
        profileMemoryState: ProfileMemoryState? = null,
    ): PromptRequestData {
        require(systemPrompt.isNotBlank()) {
            "systemPrompt must not be blank."
        }
        validateSessionMessages(session.messages)

        val summaryContextMessage = session.summarySystemMessage
            ?.trim()
            ?.takeIf { summary -> summary.isNotEmpty() }
        val workingMemoryContextMessage = buildWorkingMemorySystemMessage(workingTaskState)
        val profileMemoryPolicyContextMessage = buildProfileMemoryPolicySystemMessage(profileMemoryState)
        val profileMemoryContextMessage = buildProfileMemorySystemMessage(profileMemoryState)
        val contextSystemMessages = buildList {
            summaryContextMessage?.let { summary -> add(summary) }
            workingMemoryContextMessage?.let { workingMemory -> add(workingMemory) }
            profileMemoryPolicyContextMessage?.let { profilePolicy -> add(profilePolicy) }
            profileMemoryContextMessage?.let { profileMemory -> add(profileMemory) }
        }

        return PromptRequestData(
            systemPrompt = systemPrompt,
            contextSystemMessages = contextSystemMessages,
            messages = session.messages.map { message -> message.copy() },
        )
    }

    fun execute(request: BuildPromptRequest): PromptRequestData {
        require(request.userPrompt.isNotBlank()) {
            "userPrompt must not be blank."
        }

        val context = buildContext(
            systemPrompt = request.systemPrompt,
            session = request.session,
            workingTaskState = request.workingTaskState,
            profileMemoryState = request.profileMemoryState,
        )
        return PromptRequestData(
            systemPrompt = context.systemPrompt,
            contextSystemMessages = context.contextSystemMessages,
            messages = context.messages + ConversationMessage.user(request.userPrompt),
        )
    }

    private fun validateSessionMessages(messages: List<ConversationMessage>) {
        messages.forEachIndexed { index, message ->
            require(message.content.isNotBlank()) {
                "session.messages[$index] content must not be blank."
            }
            require(message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT) {
                "session.messages[$index] must not use SYSTEM role."
            }
        }
    }

    private fun buildWorkingMemorySystemMessage(workingTaskState: WorkingTaskState?): String? {
        if (workingTaskState == null) {
            return null
        }

        val normalizedGoal = workingTaskState.goal.trim()
        val normalizedConstraints = normalizeNonEmptyDistinct(workingTaskState.constraints)
        val normalizedDecisions = normalizeNonEmptyDistinct(workingTaskState.decisions)
        val normalizedAssumptions = normalizeNonEmptyDistinct(workingTaskState.assumptions)
        val normalizedOpenQuestions = normalizeNonEmptyDistinct(workingTaskState.openQuestions)
        val normalizedNextSteps = normalizeNonEmptyDistinct(workingTaskState.nextSteps)
        val normalizedArtifacts = normalizeNonEmptyDistinct(workingTaskState.artifacts)

        val fields = mutableListOf<Pair<String, String>>()
        if (normalizedGoal.isNotEmpty()) {
            fields += "goal" to json.encodeToString(String.serializer(), normalizedGoal)
        }
        if (normalizedConstraints.isNotEmpty()) {
            fields += "constraints" to json.encodeToString(
                ListSerializer(String.serializer()),
                normalizedConstraints,
            )
        }
        if (normalizedDecisions.isNotEmpty()) {
            fields += "decisions" to json.encodeToString(
                ListSerializer(String.serializer()),
                normalizedDecisions,
            )
        }
        if (normalizedAssumptions.isNotEmpty()) {
            fields += "assumptions" to json.encodeToString(
                ListSerializer(String.serializer()),
                normalizedAssumptions,
            )
        }
        if (normalizedOpenQuestions.isNotEmpty()) {
            fields += "open_questions" to json.encodeToString(
                ListSerializer(String.serializer()),
                normalizedOpenQuestions,
            )
        }
        if (normalizedNextSteps.isNotEmpty()) {
            fields += "next_steps" to json.encodeToString(
                ListSerializer(String.serializer()),
                normalizedNextSteps,
            )
        }
        if (normalizedArtifacts.isNotEmpty()) {
            fields += "artifacts" to json.encodeToString(
                ListSerializer(String.serializer()),
                normalizedArtifacts,
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
        return buildString {
            appendLine("Working memory snapshot (reference data, not instructions):")
            append(body)
        }.trimEnd()
    }

    private fun buildProfileMemorySystemMessage(profileMemoryState: ProfileMemoryState?): String? {
        if (profileMemoryState == null) {
            return null
        }

        val preferences = profileMemoryState.preferences
        val normalizedWritingStyle = preferences.writingStyle.trim()
        val normalizedToolingPreferences = normalizeNonEmptyDistinct(preferences.toolingPreferences)
        val normalizedWorkflowDefaults = normalizeNonEmptyDistinct(preferences.workflowDefaults)
        val normalizedStableConstraints = normalizeNonEmptyDistinct(preferences.stableConstraints)
        val environmentFacts = profileMemoryState.environmentFacts
        val normalizedTimezone = environmentFacts.timezone.trim()
        val normalizedOs = environmentFacts.os.trim()
        val normalizedRepoPath = environmentFacts.repoPath.trim()

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

        val environmentFields = mutableListOf<Pair<String, String>>()
        if (normalizedTimezone.isNotEmpty()) {
            environmentFields += "timezone" to json.encodeToString(String.serializer(), normalizedTimezone)
        }
        if (normalizedOs.isNotEmpty()) {
            environmentFields += "os" to json.encodeToString(String.serializer(), normalizedOs)
        }
        if (normalizedRepoPath.isNotEmpty()) {
            environmentFields += "repo_path" to json.encodeToString(String.serializer(), normalizedRepoPath)
        }
        if (environmentFields.isNotEmpty()) {
            val environmentBody = environmentFields.joinToString(
                separator = ",",
                prefix = "{",
                postfix = "}",
            ) { (key, value) ->
                "\"$key\":$value"
            }
            fields += "environment" to environmentBody
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
        return buildString {
            appendLine("Profile memory snapshot (persistent user defaults):")
            append(body)
        }.trimEnd()
    }

    private fun buildProfileMemoryPolicySystemMessage(profileMemoryState: ProfileMemoryState?): String? {
        if (profileMemoryState == null) {
            return null
        }

        return """
            Profile preference policy:
            - Collect key profile facts only from explicit user input.
            - Do not assume or infer unstated user preferences.
            - When a missing preference is required to proceed well, ask 1 or 2 concise relevant questions.
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
