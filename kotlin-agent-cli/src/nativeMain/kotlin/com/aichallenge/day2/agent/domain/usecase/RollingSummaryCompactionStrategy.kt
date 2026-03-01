package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.SessionCompactionSummaryMode
import com.aichallenge.day2.agent.domain.model.SessionCompactionStrategy

class RollingSummaryCompactionStrategy(
    private val sendPromptUseCase: SendPromptUseCase,
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
            ConversationMessage.system(SUMMARY_SYSTEM_PROMPT),
            ConversationMessage.user(
                buildSummaryPrompt(
                    previousSummary = previousSummary,
                    messagesToCompact = messagesToCompact,
                ),
            ),
        )

        return sendPromptUseCase.execute(
            conversation = conversation,
            temperature = 0.0,
            model = model,
        ).content.trim()
    }

    private fun buildSummaryPrompt(
        previousSummary: String?,
        messagesToCompact: List<ConversationMessage>,
    ): String {
        val serializedMessages = messagesToCompact
            .mapIndexed { index, message ->
                "${index + 1}. ${message.role.name}: ${message.content}"
            }
            .joinToString(separator = "\n")

        val summaryBlock = previousSummary?.trim().takeUnless { it.isNullOrEmpty() } ?: "(none)"
        return buildString {
            appendLine("Previous summary:")
            appendLine(summaryBlock)
            appendLine()
            appendLine("New messages to compact:")
            appendLine(serializedMessages)
            appendLine()
            appendLine("Return an updated rolling summary that merges previous summary and new messages.")
            appendLine("Output summary text only.")
        }.trim()
    }

    companion object {
        private const val STRATEGY_ID = "rolling-summary-v1"
        private val SUMMARY_SYSTEM_PROMPT = """
            You maintain a rolling conversation summary for an AI agent.
            Produce one updated summary that:
            - preserves key facts, decisions, and user preferences,
            - preserves unresolved questions and next steps,
            - removes repetition and obsolete details,
            - stays concise and factual.
            Output only the updated summary text.
        """.trimIndent()
    }
}
