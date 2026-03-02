package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.BranchTopicCatalogEntry
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

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
        model: String,
    ): BranchClassificationResult {
        val catalog = CatalogIndex.from(existingTopics)

        repeat(MAX_PRIMARY_ATTEMPTS) {
            val primaryDecision = runCatching {
                requestPrimaryDecision(
                    catalog = catalog,
                    userPrompt = userPrompt,
                    assistantResponse = assistantResponse,
                    model = model,
                )
            }.getOrNull()

            if (primaryDecision != null) {
                var routedDecision = primaryDecision
                var forcedByTopicShift = false
                if (catalog.shouldProbeForTopicShift(primaryDecision)) {
                    val shiftDecision = runCatching {
                        requestTopicShiftDecision(
                            catalog = catalog,
                            userPrompt = userPrompt,
                            assistantResponse = assistantResponse,
                            model = model,
                        )
                    }.getOrNull()
                    if (shiftDecision?.createNewTopic == true) {
                        routedDecision = PrimaryClassificationDecision(
                            topic = ParsedPrimaryReference(
                                kind = BranchReferenceKind.NEW,
                                key = normalizeKey(shiftDecision.topicName),
                                name = shiftDecision.topicName,
                            ),
                            subtopic = ParsedPrimaryReference(
                                kind = BranchReferenceKind.NEW,
                                key = normalizeKey(shiftDecision.subtopicName),
                                name = shiftDecision.subtopicName,
                            ),
                        )
                        forcedByTopicShift = true
                    }
                }

                val noveltyValidation = if (routedDecision.requiresValidation) {
                    if (forcedByTopicShift) {
                        ParsedNoveltyValidation(
                            allowNewTopic = true,
                            reuseTopicKey = "",
                            allowNewSubtopic = true,
                            reuseSubtopicKey = "",
                        )
                    } else {
                    runCatching {
                        requestNoveltyValidation(
                            catalog = catalog,
                            primaryDecision = routedDecision,
                            userPrompt = userPrompt,
                            assistantResponse = assistantResponse,
                            model = model,
                        )
                    }.getOrNull()
                    }
                } else {
                    null
                }

                return resolvePrimaryDecision(
                    catalog = catalog,
                    primaryDecision = routedDecision,
                    noveltyValidation = noveltyValidation,
                    usedFallback = false,
                )
            }
        }

        return catalog.defaultFallbackResult(
            userPrompt = userPrompt,
            assistantResponse = assistantResponse,
            usedFallback = true,
            deriveSpecificNames = ::deriveSpecificFallbackNames,
        )
    }

    private suspend fun requestPrimaryDecision(
        catalog: CatalogIndex,
        userPrompt: String,
        assistantResponse: String,
        model: String,
    ): PrimaryClassificationDecision {
        val response = sendPromptUseCase.execute(
            conversation = listOf(
                ConversationMessage.system(PRIMARY_CLASSIFICATION_SYSTEM_PROMPT),
                ConversationMessage.user(
                    buildPrimaryClassificationPrompt(
                        catalog = catalog,
                        userPrompt = userPrompt,
                        assistantResponse = assistantResponse,
                    ),
                ),
            ),
            temperature = 0.0,
            model = model,
        )

        return parsePrimaryDecision(response.content, catalog)
    }

    private suspend fun requestNoveltyValidation(
        catalog: CatalogIndex,
        primaryDecision: PrimaryClassificationDecision,
        userPrompt: String,
        assistantResponse: String,
        model: String,
    ): ParsedNoveltyValidation {
        val response = sendPromptUseCase.execute(
            conversation = listOf(
                ConversationMessage.system(NOVELTY_VALIDATION_SYSTEM_PROMPT),
                ConversationMessage.user(
                    buildNoveltyValidationPrompt(
                        catalog = catalog,
                        primaryDecision = primaryDecision,
                        userPrompt = userPrompt,
                        assistantResponse = assistantResponse,
                    ),
                ),
            ),
            temperature = 0.0,
            model = model,
        )

        return parseNoveltyValidation(response.content)
    }

    private suspend fun requestTopicShiftDecision(
        catalog: CatalogIndex,
        userPrompt: String,
        assistantResponse: String,
        model: String,
    ): ParsedTopicShiftDecision {
        val response = sendPromptUseCase.execute(
            conversation = listOf(
                ConversationMessage.system(TOPIC_SHIFT_SYSTEM_PROMPT),
                ConversationMessage.user(
                    buildTopicShiftPrompt(
                        catalog = catalog,
                        userPrompt = userPrompt,
                        assistantResponse = assistantResponse,
                    ),
                ),
            ),
            temperature = 0.0,
            model = model,
        )

        return parseTopicShiftDecision(response.content)
    }

    private fun parsePrimaryDecision(
        rawContent: String,
        catalog: CatalogIndex,
    ): PrimaryClassificationDecision {
        val root = parseJsonObject(rawContent, "Primary classification response")
        val structuredResult = runCatching {
            parseStructuredPrimaryDecision(root = root, catalog = catalog)
        }
        val structured = structuredResult.getOrNull()
        if (structured != null) {
            return structured
        }

        if (looksLikeStructuredPrimaryResponse(root)) {
            throw structuredResult.exceptionOrNull()
                ?: IllegalArgumentException("Invalid structured primary classification response.")
        }

        return parseLegacyPrimaryDecision(root = root, catalog = catalog)
    }

    private fun parseStructuredPrimaryDecision(
        root: JsonObject,
        catalog: CatalogIndex,
    ): PrimaryClassificationDecision {
        if (!root.containsKey(TOPIC_KEY) || !root.containsKey(SUBTOPIC_KEY)) {
            throw IllegalArgumentException("Primary classification response must contain '$TOPIC_KEY' and '$SUBTOPIC_KEY'.")
        }

        val topicDecision = parsePrimaryReference(
            element = root.getValue(TOPIC_KEY),
            path = TOPIC_KEY,
        )
        val subtopicDecision = parsePrimaryReference(
            element = root.getValue(SUBTOPIC_KEY),
            path = SUBTOPIC_KEY,
        )

        validateStructuredPrimaryDecision(
            topicDecision = topicDecision,
            subtopicDecision = subtopicDecision,
            catalog = catalog,
        )

        return PrimaryClassificationDecision(topic = topicDecision, subtopic = subtopicDecision)
    }

    private fun validateStructuredPrimaryDecision(
        topicDecision: ParsedPrimaryReference,
        subtopicDecision: ParsedPrimaryReference,
        catalog: CatalogIndex,
    ) {
        when (topicDecision.kind) {
            BranchReferenceKind.EXISTING -> {
                val topic = catalog.topicByKey(topicDecision.key)
                    ?: throw IllegalArgumentException("Unknown existing topic key '${topicDecision.key}'.")

                when (subtopicDecision.kind) {
                    BranchReferenceKind.EXISTING -> {
                        if (topic.subtopicByKey(subtopicDecision.key) == null) {
                            throw IllegalArgumentException(
                                "Existing subtopic key '${subtopicDecision.key}' does not belong to topic '${topic.key}'.",
                            )
                        }
                    }

                    BranchReferenceKind.NEW -> Unit
                }
            }

            BranchReferenceKind.NEW -> {
                if (subtopicDecision.kind != BranchReferenceKind.NEW) {
                    throw IllegalArgumentException("A new topic requires a new subtopic.")
                }
            }
        }
    }

    private fun parseLegacyPrimaryDecision(
        root: JsonObject,
        catalog: CatalogIndex,
    ): PrimaryClassificationDecision {
        val topicRaw = root[TOPIC_KEY]
        val subtopicRaw = root[SUBTOPIC_KEY]
        val topicName = ensureSpecificName(
            raw = extractLegacyName(topicRaw)
                ?: throw IllegalArgumentException("Legacy primary response requires '$TOPIC_KEY' string."),
            path = TOPIC_KEY,
        )
        val subtopicName = ensureSpecificName(
            raw = extractLegacyName(subtopicRaw)
                ?: throw IllegalArgumentException("Legacy primary response requires '$SUBTOPIC_KEY' string."),
            path = SUBTOPIC_KEY,
        )

        val topicByName = catalog.topicByName(topicName)
        val topicDecision = if (topicByName != null) {
            ParsedPrimaryReference(
                kind = BranchReferenceKind.EXISTING,
                key = topicByName.key,
                name = topicByName.name,
            )
        } else {
            ParsedPrimaryReference(
                kind = BranchReferenceKind.NEW,
                key = normalizeKey(topicName),
                name = topicName,
            )
        }

        val subtopicByName = topicByName?.subtopicByName(subtopicName)
        val subtopicDecision = if (subtopicByName != null) {
            ParsedPrimaryReference(
                kind = BranchReferenceKind.EXISTING,
                key = subtopicByName.key,
                name = subtopicByName.name,
            )
        } else {
            ParsedPrimaryReference(
                kind = BranchReferenceKind.NEW,
                key = normalizeKey(subtopicName),
                name = subtopicName,
            )
        }

        return PrimaryClassificationDecision(topic = topicDecision, subtopic = subtopicDecision)
    }

    private fun parsePrimaryReference(
        element: JsonElement,
        path: String,
    ): ParsedPrimaryReference {
        val jsonObject = element as? JsonObject
            ?: throw IllegalArgumentException("$path must be a JSON object.")

        val kindRaw = extractStringNullable(jsonObject[REF_KIND_KEY], "$path.$REF_KIND_KEY")
            ?: throw IllegalArgumentException("$path.$REF_KIND_KEY must be provided.")
        val keyRaw = extractStringNullable(jsonObject[REF_KEY_KEY], "$path.$REF_KEY_KEY").orEmpty()
        val nameRaw = extractStringNullable(jsonObject[REF_NAME_KEY], "$path.$REF_NAME_KEY").orEmpty()

        return when (kindRaw.trim().lowercase()) {
            "existing" -> {
                val key = normalizeKey(keyRaw)
                if (key.isEmpty()) {
                    throw IllegalArgumentException("$path existing reference requires non-empty key.")
                }
                if (key == GENERAL_KEY) {
                    throw IllegalArgumentException("$path existing key '$GENERAL_KEY' is not allowed.")
                }
                ParsedPrimaryReference(
                    kind = BranchReferenceKind.EXISTING,
                    key = key,
                    name = normalizeName(nameRaw, fallback = ""),
                )
            }

            "new" -> {
                val name = ensureSpecificName(
                    raw = nameRaw,
                    path = "$path.$REF_NAME_KEY",
                )
                ParsedPrimaryReference(
                    kind = BranchReferenceKind.NEW,
                    key = normalizeKey(name),
                    name = name,
                )
            }

            else -> throw IllegalArgumentException("$path.$REF_KIND_KEY must be 'existing' or 'new'.")
        }
    }

    private fun parseNoveltyValidation(rawContent: String): ParsedNoveltyValidation {
        val root = parseJsonObject(rawContent, "Novelty validation response")

        val allowNewTopic = extractBooleanFlexible(root[ALLOW_NEW_TOPIC_KEY], ALLOW_NEW_TOPIC_KEY)
            ?: throw IllegalArgumentException("$ALLOW_NEW_TOPIC_KEY must be provided.")
        val reuseTopicKey = normalizeKey(extractStringNullable(root[REUSE_TOPIC_KEY], REUSE_TOPIC_KEY).orEmpty())
        val allowNewSubtopic = extractBooleanFlexible(root[ALLOW_NEW_SUBTOPIC_KEY], ALLOW_NEW_SUBTOPIC_KEY)
            ?: throw IllegalArgumentException("$ALLOW_NEW_SUBTOPIC_KEY must be provided.")
        val reuseSubtopicKey = normalizeKey(extractStringNullable(root[REUSE_SUBTOPIC_KEY], REUSE_SUBTOPIC_KEY).orEmpty())

        return ParsedNoveltyValidation(
            allowNewTopic = allowNewTopic,
            reuseTopicKey = reuseTopicKey,
            allowNewSubtopic = allowNewSubtopic,
            reuseSubtopicKey = reuseSubtopicKey,
        )
    }

    private fun parseTopicShiftDecision(rawContent: String): ParsedTopicShiftDecision {
        val root = parseJsonObject(rawContent, "Topic-shift response")
        val createNewTopic = extractBooleanFlexible(root[CREATE_NEW_TOPIC_KEY], CREATE_NEW_TOPIC_KEY)
            ?: throw IllegalArgumentException("$CREATE_NEW_TOPIC_KEY must be provided.")
        if (!createNewTopic) {
            return ParsedTopicShiftDecision(
                createNewTopic = false,
                topicName = "",
                subtopicName = "",
            )
        }

        val topicName = ensureSpecificName(
            raw = extractStringNullable(root[TOPIC_KEY], TOPIC_KEY).orEmpty(),
            path = TOPIC_KEY,
        )
        val subtopicName = ensureSpecificName(
            raw = extractStringNullable(root[SUBTOPIC_KEY], SUBTOPIC_KEY).orEmpty(),
            path = SUBTOPIC_KEY,
        )
        return ParsedTopicShiftDecision(
            createNewTopic = true,
            topicName = topicName,
            subtopicName = subtopicName,
        )
    }

    private fun resolvePrimaryDecision(
        catalog: CatalogIndex,
        primaryDecision: PrimaryClassificationDecision,
        noveltyValidation: ParsedNoveltyValidation?,
        usedFallback: Boolean,
    ): BranchClassificationResult {
        val finalTopic = resolveTopic(
            catalog = catalog,
            primaryDecision = primaryDecision,
            noveltyValidation = noveltyValidation,
        )
        val finalSubtopic = resolveSubtopic(
            catalog = catalog,
            finalTopic = finalTopic,
            primaryDecision = primaryDecision,
            noveltyValidation = noveltyValidation,
        )

        return BranchClassificationResult(
            topicKey = finalTopic.key,
            topicName = finalTopic.name,
            topicKind = finalTopic.kind,
            subtopicKey = finalSubtopic.key,
            subtopicName = finalSubtopic.name,
            subtopicKind = finalSubtopic.kind,
            usedFallback = usedFallback,
            usedValidation = primaryDecision.requiresValidation,
        )
    }

    private fun resolveTopic(
        catalog: CatalogIndex,
        primaryDecision: PrimaryClassificationDecision,
        noveltyValidation: ParsedNoveltyValidation?,
    ): FinalReference {
        val topicDecision = primaryDecision.topic

        return when (topicDecision.kind) {
            BranchReferenceKind.EXISTING -> {
                val topic = catalog.topicByKey(topicDecision.key)
                    ?: throw IllegalArgumentException("Unknown existing topic key '${topicDecision.key}'.")
                FinalReference(
                    kind = BranchReferenceKind.EXISTING,
                    key = topic.key,
                    name = topic.name,
                )
            }

            BranchReferenceKind.NEW -> {
                if (noveltyValidation?.allowNewTopic == true) {
                    FinalReference(
                        kind = BranchReferenceKind.NEW,
                        key = topicDecision.key,
                        name = topicDecision.name,
                    )
                } else {
                    val reusedTopic = catalog.reuseTopicOrFallback(noveltyValidation?.reuseTopicKey)
                    if (reusedTopic != null) {
                        FinalReference(
                            kind = BranchReferenceKind.EXISTING,
                            key = reusedTopic.key,
                            name = reusedTopic.name,
                        )
                    } else {
                        FinalReference(
                            kind = BranchReferenceKind.NEW,
                            key = topicDecision.key,
                            name = topicDecision.name,
                        )
                    }
                }
            }
        }
    }

    private fun resolveSubtopic(
        catalog: CatalogIndex,
        finalTopic: FinalReference,
        primaryDecision: PrimaryClassificationDecision,
        noveltyValidation: ParsedNoveltyValidation?,
    ): FinalReference {
        val subtopicDecision = primaryDecision.subtopic

        if (finalTopic.kind == BranchReferenceKind.NEW) {
            val subtopicName = if (subtopicDecision.kind == BranchReferenceKind.NEW) {
                subtopicDecision.name
            } else {
                finalTopic.name
            }
            return FinalReference(
                kind = BranchReferenceKind.NEW,
                key = normalizeKey(subtopicName),
                name = subtopicName,
            )
        }

        val topic = catalog.topicByKey(finalTopic.key)
            ?: throw IllegalStateException("Resolved topic '${finalTopic.key}' is missing from catalog.")

        if (
            primaryDecision.topic.kind == BranchReferenceKind.EXISTING &&
            primaryDecision.topic.key == topic.key &&
            subtopicDecision.kind == BranchReferenceKind.EXISTING
        ) {
            val existingSubtopic = topic.subtopicByKey(subtopicDecision.key)
            if (existingSubtopic != null) {
                return FinalReference(
                    kind = BranchReferenceKind.EXISTING,
                    key = existingSubtopic.key,
                    name = existingSubtopic.name,
                )
            }
        }

        if (subtopicDecision.kind == BranchReferenceKind.NEW && noveltyValidation?.allowNewSubtopic == true) {
            return FinalReference(
                kind = BranchReferenceKind.NEW,
                key = subtopicDecision.key,
                name = subtopicDecision.name,
            )
        }

        val reusedSubtopic = topic.reuseSubtopicOrFallback(
            requestedKey = noveltyValidation?.reuseSubtopicKey,
            activeTopicKey = catalog.activeTopicKey,
            activeSubtopicKey = catalog.activeSubtopicKey,
        )
        if (reusedSubtopic != null) {
            return FinalReference(
                kind = BranchReferenceKind.EXISTING,
                key = reusedSubtopic.key,
                name = reusedSubtopic.name,
            )
        }

        if (subtopicDecision.kind == BranchReferenceKind.NEW) {
            return FinalReference(
                kind = BranchReferenceKind.NEW,
                key = subtopicDecision.key,
                name = subtopicDecision.name,
            )
        }

        val firstSubtopic = topic.subtopicsByKey.values.firstOrNull()
            ?: throw IllegalStateException("Resolved topic '${topic.key}' does not contain subtopics.")
        return FinalReference(
            kind = BranchReferenceKind.EXISTING,
            key = firstSubtopic.key,
            name = firstSubtopic.name,
        )
    }

    private fun buildPrimaryClassificationPrompt(
        catalog: CatalogIndex,
        userPrompt: String,
        assistantResponse: String,
    ): String {
        val serializedCatalog = catalog.serializeForPrompt()
        val activeTopicKey = catalog.activeTopicKey ?: "(none)"
        val activeSubtopicKey = catalog.activeSubtopicKey ?: "(none)"

        return buildString {
            appendLine("Existing topic/subtopic map (use keys for existing references):")
            appendLine(serializedCatalog)
            appendLine()
            appendLine("Active topic key: $activeTopicKey")
            appendLine("Active subtopic key: $activeSubtopicKey")
            appendLine()
            appendLine("Latest user prompt:")
            appendLine(userPrompt)
            appendLine()
            appendLine("Latest assistant response:")
            appendLine(assistantResponse)
            appendLine()
            appendLine("Routing policy:")
            appendLine("- Reuse existing topic/subtopic whenever any reasonable semantic match exists.")
            appendLine("- If uncertain and active branch exists, reuse the active topic/subtopic.")
            appendLine("- Feature details within the same design scope should stay in the current subtopic.")
            appendLine("- Never use topic or subtopic name 'General'.")
            appendLine("- Always choose concrete specific topic/subtopic names.")
            appendLine("- Mark as 'new' only if no existing candidate fits.")
            appendLine()
            appendLine("Return JSON only with this exact schema:")
            appendLine("{\"topic\":{\"kind\":\"existing|new\",\"key\":\"...\",\"name\":\"...\"},\"subtopic\":{\"kind\":\"existing|new\",\"key\":\"...\",\"name\":\"...\"}}")
            appendLine("For existing references: provide the exact existing key and existing display name.")
            appendLine("For new references: provide empty key and a concise new name.")
            appendLine("Compatibility: if you cannot follow the full schema, return legacy JSON {\"topic\":\"...\",\"subtopic\":\"...\"}.")
        }.trim()
    }

    private fun buildNoveltyValidationPrompt(
        catalog: CatalogIndex,
        primaryDecision: PrimaryClassificationDecision,
        userPrompt: String,
        assistantResponse: String,
    ): String {
        return buildString {
            appendLine("Existing topic/subtopic map:")
            appendLine(catalog.serializeForPrompt())
            appendLine()
            appendLine("Primary routing proposal:")
            appendLine(primaryDecision.toPromptLine())
            appendLine()
            appendLine("Latest user prompt:")
            appendLine(userPrompt)
            appendLine()
            appendLine("Latest assistant response:")
            appendLine(assistantResponse)
            appendLine()
            appendLine("Decide whether new topic/subtopic is truly necessary.")
            appendLine("If any existing candidate is a reasonable match, do NOT allow new and provide reuse keys.")
            appendLine("If uncertain and active branch exists, prefer reusing the active branch.")
            appendLine("Never suggest key or name 'general'.")
            appendLine()
            appendLine("Return JSON only with this exact schema:")
            appendLine("{\"allowNewTopic\":true|false,\"reuseTopicKey\":\"...\",\"allowNewSubtopic\":true|false,\"reuseSubtopicKey\":\"...\"}")
            appendLine("When allowing new, reuse keys can be empty strings.")
            appendLine("When denying new, reuse keys must be existing keys from the map.")
        }.trim()
    }

    private fun buildTopicShiftPrompt(
        catalog: CatalogIndex,
        userPrompt: String,
        assistantResponse: String,
    ): String {
        return buildString {
            appendLine("Existing topic/subtopic map:")
            appendLine(catalog.serializeForPrompt())
            appendLine()
            appendLine("Active topic key: ${catalog.activeTopicKey ?: "(none)"}")
            appendLine("Active subtopic key: ${catalog.activeSubtopicKey ?: "(none)"}")
            appendLine()
            appendLine("Latest user prompt:")
            appendLine(userPrompt)
            appendLine()
            appendLine("Latest assistant response:")
            appendLine(assistantResponse)
            appendLine()
            appendLine("Decide whether the turn is clearly a different domain/workstream from the active branch.")
            appendLine("If yes, create a concrete new topic and subtopic.")
            appendLine("If no, keep current branch.")
            appendLine("Never use topic or subtopic name 'General'.")
            appendLine()
            appendLine("Return JSON only with this exact schema:")
            appendLine("{\"createNewTopic\":true|false,\"topic\":\"...\",\"subtopic\":\"...\"}")
            appendLine("When createNewTopic=false, topic/subtopic can be empty strings.")
            appendLine("When createNewTopic=true, provide concrete specific names.")
        }.trim()
    }

    private fun parseJsonObject(
        rawContent: String,
        errorPrefix: String,
    ): JsonObject {
        val candidate = extractJsonObject(rawContent)
        val parsed = json.parseToJsonElement(candidate) as? JsonObject
            ?: throw IllegalArgumentException("$errorPrefix must be a JSON object.")
        return parsed
    }

    private fun extractStringNullable(
        element: JsonElement?,
        path: String,
    ): String? {
        if (element == null) {
            return null
        }
        val primitive = element as? JsonPrimitive
            ?: throw IllegalArgumentException("$path must be a string.")
        if (!primitive.isString) {
            return null
        }
        return primitive.content
    }

    private fun extractLegacyName(
        element: JsonElement?,
    ): String? {
        if (element == null) {
            return null
        }

        val primitive = element as? JsonPrimitive
        if (primitive != null) {
            return if (primitive.isString) {
                primitive.content
            } else {
                null
            }
        }

        val jsonObject = element as? JsonObject ?: return null
        val candidateKeys = listOf(
            REF_NAME_KEY,
            TOPIC_KEY,
            SUBTOPIC_KEY,
            "title",
            "label",
            "value",
        )
        candidateKeys.forEach { key ->
            val value = jsonObject[key] as? JsonPrimitive
            if (value != null && value.isString) {
                val content = value.content.trim()
                if (content.isNotEmpty()) {
                    return content
                }
            }
        }

        return null
    }

    private fun extractBooleanFlexible(
        element: JsonElement?,
        path: String,
    ): Boolean? {
        if (element == null) {
            return null
        }
        val primitive = element as? JsonPrimitive
            ?: throw IllegalArgumentException("$path must be a boolean.")

        primitive.booleanOrNull?.let { return it }

        if (primitive.isString) {
            return when (primitive.content.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("$path must be true/false.")
            }
        }

        throw IllegalArgumentException("$path must be a boolean.")
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

    private fun looksLikeStructuredPrimaryResponse(root: JsonObject): Boolean {
        val topicElement = root[TOPIC_KEY] as? JsonObject
        val subtopicElement = root[SUBTOPIC_KEY] as? JsonObject

        val topicHasKind = topicElement?.containsKey(REF_KIND_KEY) == true
        val subtopicHasKind = subtopicElement?.containsKey(REF_KIND_KEY) == true

        return topicHasKind || subtopicHasKind
    }

    private fun ensureSpecificName(
        raw: String,
        path: String,
    ): String {
        val name = normalizeName(raw, fallback = "")
        if (name.isEmpty()) {
            throw IllegalArgumentException("$path must not be blank.")
        }
        if (normalizeKey(name) == GENERAL_KEY) {
            throw IllegalArgumentException("$path must not be 'General'.")
        }
        return name
    }

    private fun deriveSpecificFallbackNames(
        userPrompt: String,
        assistantResponse: String,
    ): Pair<String, String> {
        val topic = deriveSpecificNameFromText(
            text = userPrompt,
            fallbackName = FALLBACK_TOPIC_NAME,
        )
        val subtopicRaw = deriveSpecificNameFromText(
            text = assistantResponse,
            fallbackName = FALLBACK_SUBTOPIC_NAME,
        )
        val subtopic = if (normalizeKey(subtopicRaw) == normalizeKey(topic)) {
            FALLBACK_SUBTOPIC_NAME
        } else {
            subtopicRaw
        }
        return topic to subtopic
    }

    private fun deriveSpecificNameFromText(
        text: String,
        fallbackName: String,
    ): String {
        val tokens = TOKEN_REGEX.findAll(text)
            .map { match -> match.value.lowercase() }
            .toList()
        val significantTokens = tokens
            .filter { token -> token.length > 2 && token !in STOP_WORDS }
            .take(MAX_DERIVED_WORDS)
        val chosenTokens = if (significantTokens.isNotEmpty()) {
            significantTokens
        } else {
            tokens.take(MAX_DERIVED_WORDS)
        }
        val rawName = if (chosenTokens.isEmpty()) {
            fallbackName
        } else {
            chosenTokens.joinToString(separator = " ") { token -> titleCaseWord(token) }
        }

        return ensureSpecificName(
            raw = rawName,
            path = "fallback",
        )
    }

    private fun titleCaseWord(raw: String): String {
        if (raw.isEmpty()) {
            return raw
        }
        val lower = raw.lowercase()
        return lower.replaceFirstChar { first ->
            first.uppercase()
        }
    }

    private data class PrimaryClassificationDecision(
        val topic: ParsedPrimaryReference,
        val subtopic: ParsedPrimaryReference,
    ) {
        val requiresValidation: Boolean
            get() = topic.kind == BranchReferenceKind.NEW || subtopic.kind == BranchReferenceKind.NEW

        fun toPromptLine(): String {
            return "topic={kind=${topic.kind.name.lowercase()},key='${topic.key}',name='${topic.name}'}; " +
                "subtopic={kind=${subtopic.kind.name.lowercase()},key='${subtopic.key}',name='${subtopic.name}'}"
        }
    }

    private data class ParsedPrimaryReference(
        val kind: BranchReferenceKind,
        val key: String,
        val name: String,
    )

    private data class ParsedNoveltyValidation(
        val allowNewTopic: Boolean,
        val reuseTopicKey: String,
        val allowNewSubtopic: Boolean,
        val reuseSubtopicKey: String,
    )

    private data class ParsedTopicShiftDecision(
        val createNewTopic: Boolean,
        val topicName: String,
        val subtopicName: String,
    )

    private data class FinalReference(
        val kind: BranchReferenceKind,
        val key: String,
        val name: String,
    )

    private data class CatalogIndex(
        val topicsByKey: LinkedHashMap<String, CatalogTopic>,
        val activeTopicKey: String?,
        val activeSubtopicKey: String?,
    ) {
        fun topicByKey(key: String): CatalogTopic? = topicsByKey[key]

        fun activeTopicOrNull(): CatalogTopic? {
            val key = activeTopicKey ?: return null
            return topicsByKey[key]
        }

        fun activeSubtopicOrNull(): CatalogSubtopic? {
            val topic = activeTopicOrNull() ?: return null
            val key = activeSubtopicKey ?: return null
            return topic.subtopicByKey(key)
        }

        fun reuseTopicOrFallback(requestedKey: String?): CatalogTopic? {
            val normalizedRequestedKey = normalizeKey(requestedKey.orEmpty())
            if (normalizedRequestedKey.isNotEmpty()) {
                val requested = topicsByKey[normalizedRequestedKey]
                if (requested != null) {
                    return requested
                }
            }

            return activeTopicOrNull() ?: topicsByKey.values.firstOrNull()
        }

        fun defaultFallbackResult(
            userPrompt: String,
            assistantResponse: String,
            usedFallback: Boolean,
            deriveSpecificNames: (String, String) -> Pair<String, String>,
        ): BranchClassificationResult {
            val activeTopic = activeTopicOrNull()
            val activeSubtopic = activeSubtopicOrNull()
            if (activeTopic != null && activeSubtopic != null) {
                return BranchClassificationResult(
                    topicKey = activeTopic.key,
                    topicName = activeTopic.name,
                    topicKind = BranchReferenceKind.EXISTING,
                    subtopicKey = activeSubtopic.key,
                    subtopicName = activeSubtopic.name,
                    subtopicKind = BranchReferenceKind.EXISTING,
                    usedFallback = usedFallback,
                    usedValidation = false,
                )
            }

            val (topicName, subtopicName) = deriveSpecificNames(userPrompt, assistantResponse)

            return BranchClassificationResult(
                topicKey = normalizeKey(topicName),
                topicName = topicName,
                topicKind = BranchReferenceKind.NEW,
                subtopicKey = normalizeKey(subtopicName),
                subtopicName = subtopicName,
                subtopicKind = BranchReferenceKind.NEW,
                usedFallback = usedFallback,
                usedValidation = false,
            )
        }

        fun serializeForPrompt(): String {
            if (topicsByKey.isEmpty()) {
                return "(none)"
            }

            return topicsByKey.values.joinToString(separator = "\n") { topic ->
                val topicPrefix = if (topic.key == activeTopicKey) "(active topic)" else ""
                val subtopicLines = topic.subtopicsByKey.values.joinToString(separator = "\n") { subtopic ->
                    val subtopicPrefix = if (topic.key == activeTopicKey && subtopic.key == activeSubtopicKey) {
                        " (active subtopic)"
                    } else {
                        ""
                    }
                    "  - subtopic key='${subtopic.key}' name='${subtopic.name}'$subtopicPrefix"
                }
                "- topic key='${topic.key}' name='${topic.name}' $topicPrefix\n$subtopicLines".trimEnd()
            }
        }

        companion object {
            fun from(entries: List<BranchTopicCatalogEntry>): CatalogIndex {
                val topicsByKey = linkedMapOf<String, CatalogTopic>()
                entries.forEach entryLoop@ { entry ->
                    val topicKey = normalizeKey(entry.key)
                    val topicName = normalizeName(entry.topic, fallback = "")
                    if (topicKey.isEmpty() || topicName.isEmpty()) {
                        return@entryLoop
                    }
                    if (topicKey == GENERAL_KEY || normalizeKey(topicName) == GENERAL_KEY) {
                        return@entryLoop
                    }
                    if (topicsByKey.containsKey(topicKey)) {
                        return@entryLoop
                    }

                    val subtopicsByKey = linkedMapOf<String, CatalogSubtopic>()
                    entry.subtopics.forEach subtopicLoop@ { subtopicEntry ->
                        val subtopicKey = normalizeKey(subtopicEntry.key)
                        val subtopicName = normalizeName(subtopicEntry.subtopic, fallback = "")
                        if (subtopicKey.isEmpty() || subtopicName.isEmpty()) {
                            return@subtopicLoop
                        }
                        if (subtopicKey == GENERAL_KEY || normalizeKey(subtopicName) == GENERAL_KEY) {
                            return@subtopicLoop
                        }
                        if (subtopicsByKey.containsKey(subtopicKey)) {
                            return@subtopicLoop
                        }

                        subtopicsByKey[subtopicKey] = CatalogSubtopic(
                            key = subtopicKey,
                            name = subtopicName,
                        )
                    }
                    if (subtopicsByKey.isEmpty()) {
                        return@entryLoop
                    }

                    topicsByKey[topicKey] = CatalogTopic(
                        key = topicKey,
                        name = topicName,
                        subtopicsByKey = subtopicsByKey,
                    )
                }

                val activeTopicKey = entries.firstOrNull { topic -> topic.isActive }
                    ?.let { active -> normalizeKey(active.key) }
                    ?.takeIf { key -> topicsByKey.containsKey(key) }
                    ?: topicsByKey.keys.firstOrNull()

                val activeTopicEntry = entries.firstOrNull { entry -> normalizeKey(entry.key) == activeTopicKey }
                val activeSubtopicKey = if (activeTopicKey == null) {
                    null
                } else {
                    activeTopicEntry
                        ?.subtopics
                        ?.firstOrNull { subtopic -> subtopic.isActive }
                        ?.let { subtopic -> normalizeKey(subtopic.key) }
                        ?.takeIf { subtopicKey ->
                            topicsByKey.getValue(activeTopicKey).subtopicsByKey.containsKey(subtopicKey)
                        }
                        ?: topicsByKey.getValue(activeTopicKey).subtopicsByKey.keys.firstOrNull()
                }

                return CatalogIndex(
                    topicsByKey = topicsByKey,
                    activeTopicKey = activeTopicKey,
                    activeSubtopicKey = activeSubtopicKey,
                )
            }
        }

        fun shouldProbeForTopicShift(decision: PrimaryClassificationDecision): Boolean {
            if (decision.topic.kind != BranchReferenceKind.EXISTING) {
                return false
            }

            val activeTopicKey = activeTopicKey ?: return false
            val activeSubtopicKey = activeSubtopicKey ?: return false
            if (decision.topic.key != activeTopicKey) {
                return false
            }

            if (topicsByKey.size != 1) {
                return false
            }

            val activeTopic = topicsByKey[activeTopicKey] ?: return false
            if (activeTopic.subtopicsByKey.size != 1) {
                return false
            }

            return when (decision.subtopic.kind) {
                BranchReferenceKind.NEW -> true
                BranchReferenceKind.EXISTING -> decision.subtopic.key == activeSubtopicKey
            }
        }
    }

    private data class CatalogTopic(
        val key: String,
        val name: String,
        val subtopicsByKey: LinkedHashMap<String, CatalogSubtopic>,
    ) {
        fun subtopicByKey(key: String): CatalogSubtopic? = subtopicsByKey[key]

        fun subtopicByName(name: String): CatalogSubtopic? {
            val normalized = normalizeKey(name)
            return subtopicsByKey.values.firstOrNull { subtopic ->
                subtopic.key == normalized || normalizeKey(subtopic.name) == normalized
            }
        }

        fun reuseSubtopicOrFallback(
            requestedKey: String?,
            activeTopicKey: String?,
            activeSubtopicKey: String?,
        ): CatalogSubtopic? {
            val normalizedRequestedKey = normalizeKey(requestedKey.orEmpty())
            if (normalizedRequestedKey.isNotEmpty()) {
                val requested = subtopicsByKey[normalizedRequestedKey]
                if (requested != null) {
                    return requested
                }
            }

            if (activeTopicKey != null && activeSubtopicKey != null && key == activeTopicKey) {
                val active = subtopicsByKey[activeSubtopicKey]
                if (active != null) {
                    return active
                }
            }

            return subtopicsByKey.values.firstOrNull()
        }
    }

    private data class CatalogSubtopic(
        val key: String,
        val name: String,
    )

    private fun CatalogIndex.topicByName(name: String): CatalogTopic? {
        val normalized = normalizeKey(name)
        return topicsByKey.values.firstOrNull { topic ->
            topic.key == normalized || normalizeKey(topic.name) == normalized
        }
    }

    companion object {
        private const val TOPIC_KEY = "topic"
        private const val SUBTOPIC_KEY = "subtopic"

        private const val REF_KIND_KEY = "kind"
        private const val REF_KEY_KEY = "key"
        private const val REF_NAME_KEY = "name"

        private const val ALLOW_NEW_TOPIC_KEY = "allowNewTopic"
        private const val REUSE_TOPIC_KEY = "reuseTopicKey"
        private const val ALLOW_NEW_SUBTOPIC_KEY = "allowNewSubtopic"
        private const val REUSE_SUBTOPIC_KEY = "reuseSubtopicKey"
        private const val CREATE_NEW_TOPIC_KEY = "createNewTopic"

        private const val GENERAL_KEY = "general"
        private const val MAX_PRIMARY_ATTEMPTS = 2
        private const val MAX_DERIVED_WORDS = 4
        private const val FALLBACK_TOPIC_NAME = "Conversation Topic"
        private const val FALLBACK_SUBTOPIC_NAME = "Current Focus"
        private val TOKEN_REGEX = Regex("[A-Za-z0-9]+")
        private val STOP_WORDS = setOf(
            "the",
            "and",
            "for",
            "with",
            "that",
            "this",
            "from",
            "into",
            "about",
            "your",
            "you",
            "are",
            "was",
            "were",
            "have",
            "has",
            "had",
            "will",
            "would",
            "should",
            "could",
            "can",
            "let",
            "lets",
            "please",
            "need",
            "want",
            "make",
            "build",
            "create",
            "discuss",
            "talk",
            "then",
            "than",
        )

        private val PRIMARY_CLASSIFICATION_SYSTEM_PROMPT = """
            You classify conversation turns into topic and subtopic for AI memory routing.
            Prioritize branch reuse: create new branches only when no existing branch is a reasonable semantic match.
            Never use topic/subtopic name "General".
            Always output strict JSON only, with no markdown or extra text.
        """.trimIndent()

        private val NOVELTY_VALIDATION_SYSTEM_PROMPT = """
            You validate whether creating a new topic or subtopic is truly necessary.
            If any existing branch is a reasonable match, reject the new branch and provide reuse keys.
            Never suggest key or name "general".
            Always output strict JSON only, with no markdown or extra text.
        """.trimIndent()

        private val TOPIC_SHIFT_SYSTEM_PROMPT = """
            You detect whether the latest turn should move to a new topic because domain/workstream changed.
            Mark createNewTopic=true only for clearly different domain/workstream.
            Never output topic/subtopic name "General".
            Always output strict JSON only, with no markdown or extra text.
        """.trimIndent()
    }
}

enum class BranchReferenceKind {
    EXISTING,
    NEW,
}

data class BranchClassificationResult(
    val topicKey: String,
    val topicName: String,
    val topicKind: BranchReferenceKind,
    val subtopicKey: String,
    val subtopicName: String,
    val subtopicKind: BranchReferenceKind,
    val usedFallback: Boolean,
    val usedValidation: Boolean,
)

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

private fun normalizeKey(raw: String): String {
    return normalizeName(raw, "").lowercase()
}
