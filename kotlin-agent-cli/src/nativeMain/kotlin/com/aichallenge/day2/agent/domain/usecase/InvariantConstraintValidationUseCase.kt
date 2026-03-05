package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.PromptRequestData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class InvariantValidationStatus {
    PASS,
    FAIL,
}

enum class InvariantViolationSource {
    USER,
    LLM,
}

data class InvariantViolation(
    val constraint: String,
    val userMessage: String,
    val source: InvariantViolationSource,
)

data class InvariantValidationResult(
    val status: InvariantValidationStatus,
    val failedConstraints: List<InvariantViolation>,
)

class InvariantConstraintValidationUseCase(
    private val sendPromptUseCase: SendPromptUseCase,
    private val json: Json = Json {
        ignoreUnknownKeys = true
    },
) {
    suspend fun validate(
        invariants: List<String>,
        userPrompt: String,
        assistantResponse: String,
        model: String,
    ): InvariantValidationResult {
        val strictInvariants = normalizeStrictInvariants(invariants)
        if (strictInvariants.isEmpty()) {
            return InvariantValidationResult(
                status = InvariantValidationStatus.PASS,
                failedConstraints = emptyList(),
            )
        }

        val validationResponse = sendPromptUseCase.execute(
            prompt = PromptRequestData(
                systemPrompt = INVARIANT_VALIDATION_SYSTEM_PROMPT,
                messages = listOf(
                    ConversationMessage.user(
                        buildValidationPrompt(
                            strictInvariants = strictInvariants,
                            userPrompt = userPrompt,
                            assistantResponse = assistantResponse,
                        ),
                    ),
                ),
            ),
            temperature = 0.0,
            model = model,
        )

        return parseValidationResult(
            rawContent = validationResponse.content,
            strictInvariants = strictInvariants,
        )
    }

    private fun buildValidationPrompt(
        strictInvariants: List<String>,
        userPrompt: String,
        assistantResponse: String,
    ): String {
        return buildString {
            appendLine("Invariant constraints:")
            strictInvariants.forEachIndexed { index, invariant ->
                appendLine("${index + 1}. $invariant")
            }
            appendLine()
            appendLine("Original user prompt:")
            appendLine(userPrompt.trim())
            appendLine()
            appendLine("Candidate LLM response:")
            appendLine(assistantResponse.trim())
            appendLine()
            appendLine("Return strict JSON only with this exact schema:")
            appendLine("""{"status":"PASS|FAIL","failed_constraints":[{"constraint":"...","source":"user|llm","user_message":"..."}]}""")
            appendLine("- If status is PASS, return failed_constraints as [].")
            appendLine("- If status is FAIL, include one object for each failed invariant.")
            appendLine("- source must be 'user' if the violation is in the user prompt, or 'llm' if the violation is in the candidate LLM response.")
            appendLine("- Every user_message must be a meaningful, actionable message for the user.")
            appendLine("- Do not return markdown, code fences, or any extra text.")
        }.trimEnd()
    }

    private fun parseValidationResult(
        rawContent: String,
        strictInvariants: List<String>,
    ): InvariantValidationResult {
        val root = parseJsonObject(rawContent)
            ?: return invalidValidatorResponse(
                strictInvariants = strictInvariants,
                reason = "Validation output is not valid JSON.",
            )

        val status = root["status"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.uppercase()

        return when (status) {
            "PASS" -> InvariantValidationResult(
                status = InvariantValidationStatus.PASS,
                failedConstraints = emptyList(),
            )

            "FAIL" -> {
                val failedConstraints = parseFailedConstraints(root, strictInvariants)
                InvariantValidationResult(
                    status = InvariantValidationStatus.FAIL,
                    failedConstraints = failedConstraints,
                )
            }

            else -> invalidValidatorResponse(
                strictInvariants = strictInvariants,
                reason = "Validation output has invalid or missing status.",
            )
        }
    }

    private fun parseFailedConstraints(
        root: JsonObject,
        strictInvariants: List<String>,
    ): List<InvariantViolation> {
        val failedArray = runCatching { root["failed_constraints"]?.jsonArray }
            .getOrNull()
            ?: JsonArray(emptyList())

        val failed = failedArray.mapIndexedNotNull { index, element ->
            val failedObject = runCatching { element.jsonObject }.getOrNull() ?: return@mapIndexedNotNull null
            val rawConstraint = failedObject["constraint"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                .orEmpty()
            val rawUserMessage = failedObject["user_message"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                .orEmpty()
            val rawSource = failedObject["source"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                .orEmpty()
            val resolvedConstraint = rawConstraint.ifEmpty {
                strictInvariants.getOrNull(index) ?: strictInvariants.firstOrNull().orEmpty()
            }.ifEmpty { "[Strict] Invariant constraint" }
            val resolvedSource = parseViolationSource(rawSource)
            val resolvedUserMessage = rawUserMessage.ifEmpty {
                "Response violates '$resolvedConstraint'. Update the response to satisfy this strict invariant."
            }
            InvariantViolation(
                constraint = resolvedConstraint,
                userMessage = resolvedUserMessage,
                source = resolvedSource,
            )
        }

        if (failed.isNotEmpty()) {
            return failed
        }

        val fallbackConstraint = strictInvariants.firstOrNull() ?: "[Strict] Invariant constraint"
        return listOf(
            InvariantViolation(
                constraint = fallbackConstraint,
                userMessage = "Validation returned FAIL but did not provide failed constraints. Regenerate while satisfying all strict invariants.",
                source = InvariantViolationSource.LLM,
            ),
        )
    }

    private fun invalidValidatorResponse(
        strictInvariants: List<String>,
        reason: String,
    ): InvariantValidationResult {
        val fallbackConstraint = strictInvariants.firstOrNull() ?: "[Strict] Invariant constraint"
        return InvariantValidationResult(
            status = InvariantValidationStatus.FAIL,
            failedConstraints = listOf(
                InvariantViolation(
                    constraint = fallbackConstraint,
                    userMessage = "$reason Regenerate the response and satisfy all strict invariants.",
                    source = InvariantViolationSource.LLM,
                ),
            ),
        )
    }

    private fun parseViolationSource(rawSource: String): InvariantViolationSource {
        return when (rawSource.lowercase()) {
            "user" -> InvariantViolationSource.USER
            "llm" -> InvariantViolationSource.LLM
            else -> InvariantViolationSource.LLM
        }
    }

    private fun parseJsonObject(rawContent: String): JsonObject? {
        val trimmed = rawContent.trim()
        if (trimmed.isEmpty()) {
            return null
        }

        val parseObject = { candidate: String ->
            runCatching {
                json.parseToJsonElement(candidate).jsonObject
            }.getOrNull()
        }

        parseObject(trimmed)?.let { return it }

        extractFencedCodeBlocks(trimmed).forEach { fencedContent ->
            val trimmedContent = fencedContent.trim()
            parseObject(trimmedContent)?.let { return it }
            extractFirstJsonObjectCandidate(trimmedContent)?.let { candidate ->
                parseObject(candidate)?.let { return it }
            }
        }

        extractFirstJsonObjectCandidate(trimmed)?.let { candidate ->
            parseObject(candidate)?.let { return it }
        }
        return null
    }

    private fun extractFencedCodeBlocks(text: String): List<String> {
        return FENCED_CODE_BLOCK_REGEX.findAll(text)
            .mapNotNull { match -> match.groups[1]?.value }
            .toList()
    }

    private fun extractFirstJsonObjectCandidate(rawContent: String): String? {
        val start = rawContent.indexOf('{')
        if (start < 0) {
            return null
        }

        var depth = 0
        var inString = false
        var isEscaped = false
        for (index in start until rawContent.length) {
            val char = rawContent[index]
            if (inString) {
                if (isEscaped) {
                    isEscaped = false
                    continue
                }
                when (char) {
                    '\\' -> isEscaped = true
                    '"' -> inString = false
                }
                continue
            }

            when (char) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return rawContent.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    private fun normalizeStrictInvariants(invariants: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return invariants.mapNotNull { invariant ->
            canonicalStrictConstraint(invariant)
        }.filter { normalized ->
            seen.add(normalized.lowercase())
        }
    }

    private fun canonicalStrictConstraint(rawInvariant: String): String? {
        val trimmed = rawInvariant.trim()
        if (trimmed.isEmpty()) {
            return null
        }

        val withoutPrefix = trimmed.replaceFirst(STRICT_PREFIX_REGEX, "").trim()
        if (withoutPrefix.isEmpty()) {
            return null
        }
        return "[Strict] $withoutPrefix"
    }

    companion object {
        private val FENCED_CODE_BLOCK_REGEX = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        private val STRICT_PREFIX_REGEX = Regex("^\\[\\s*strict\\s*\\]\\s*", RegexOption.IGNORE_CASE)
        private val INVARIANT_VALIDATION_SYSTEM_PROMPT = """
            You validate assistant responses against strict invariant constraints.
            Treat every listed invariant as mandatory and non-negotiable.
            Always output strict JSON only, with no markdown or extra text.
        """.trimIndent()
    }
}
