package com.aichallenge.day2.agent.presentation.cli

class SystemPromptBuilder {
    fun build(
        basePrompt: String,
        selection: ConfigMenuSelection,
    ): String {
        val stopInstruction = selection.stopSequence.takeIf { it.isNotBlank() }?.let { stopText ->
            """When user sends "$stopText" stop generating questions and provide short summary"""
        } ?: "No explicit stop sequence behavior."

        return """
            $basePrompt
            
            Output rules:
            - Format: ${selection.format.readableName()}
            - Max output tokens: ${selection.maxOutputTokens?.toString() ?: "(none)"}
            - Stop sequence: ${selection.stopSequence.ifBlank { "(none)" }}
            - Stop behavior: $stopInstruction
            - Follow output rules exactly.
        """.trimIndent()
    }

    private fun OutputFormatOption.readableName(): String = when (this) {
        OutputFormatOption.PLAIN_TEXT -> "Plain text"
        OutputFormatOption.MARKDOWN -> "Markdown"
        OutputFormatOption.JSON -> "JSON"
        OutputFormatOption.TABLE -> "Table"
    }
}
