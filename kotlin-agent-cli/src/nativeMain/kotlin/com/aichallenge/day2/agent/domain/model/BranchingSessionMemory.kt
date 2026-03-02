package com.aichallenge.day2.agent.domain.model

class BranchingSessionMemory {
    private val topicsByKey = linkedMapOf<String, TopicMemory>()
    private var activeTopicKey: String = ""
    private var activeSubtopicKey: String = ""

    init {
        reset()
    }

    fun reset() {
        topicsByKey.clear()
        activeTopicKey = ""
        activeSubtopicKey = ""
    }

    fun restore(state: BranchingMemoryState?): Boolean {
        if (state == null) {
            reset()
            return false
        }

        val requestedActiveTopicKey = normalizeKey(state.activeTopicKey)
        val requestedActiveSubtopicKey = normalizeKey(state.activeSubtopicKey)

        val loadedTopics = linkedMapOf<String, TopicMemory>()
        for (topicState in state.topics) {
            val topicKey = normalizeKey(topicState.key)
            if (topicKey.isEmpty() || topicKey == LEGACY_GENERAL_KEY) {
                continue
            }

            val topicDisplayName = normalizeDisplayName(topicState.displayName)
            if (topicDisplayName.isEmpty() || loadedTopics.containsKey(topicKey)) {
                reset()
                return false
            }

            if (topicState.subtopics.isEmpty()) {
                continue
            }

            val loadedSubtopics = linkedMapOf<String, SubtopicMemory>()
            for (subtopicState in topicState.subtopics) {
                val subtopicKey = normalizeKey(subtopicState.key)
                if (subtopicKey.isEmpty() || subtopicKey == LEGACY_GENERAL_KEY) {
                    continue
                }

                val subtopicDisplayName = normalizeDisplayName(subtopicState.displayName)
                val messageCopies = subtopicState.messages.map { it.copy() }

                if (subtopicDisplayName.isEmpty() || loadedSubtopics.containsKey(subtopicKey)) {
                    reset()
                    return false
                }
                if (!isValidSubtopicMessages(messageCopies)) {
                    reset()
                    return false
                }

                loadedSubtopics[subtopicKey] = SubtopicMemory(
                    key = subtopicKey,
                    displayName = subtopicDisplayName,
                    messages = messageCopies.toMutableList(),
                )
            }
            if (loadedSubtopics.isEmpty()) {
                continue
            }

            loadedTopics[topicKey] = TopicMemory(
                key = topicKey,
                displayName = topicDisplayName,
                rollingSummary = topicState.rollingSummary,
                subtopicsByKey = loadedSubtopics,
            )
        }

        topicsByKey.clear()
        topicsByKey.putAll(loadedTopics)
        if (loadedTopics.isEmpty()) {
            activeTopicKey = ""
            activeSubtopicKey = ""
            return true
        }

        val resolvedActiveTopicKey = requestedActiveTopicKey
            .takeIf { key -> key.isNotEmpty() && loadedTopics.containsKey(key) }
            ?: loadedTopics.keys.first()
        val resolvedTopic = loadedTopics.getValue(resolvedActiveTopicKey)
        val resolvedActiveSubtopicKey = requestedActiveSubtopicKey
            .takeIf { key -> key.isNotEmpty() && resolvedTopic.subtopicsByKey.containsKey(key) }
            ?: resolvedTopic.subtopicsByKey.keys.first()

        activeTopicKey = resolvedActiveTopicKey
        activeSubtopicKey = resolvedActiveSubtopicKey
        return true
    }

    fun snapshot(): BranchingMemoryState {
        return BranchingMemoryState(
            activeTopicKey = activeTopicKey,
            activeSubtopicKey = activeSubtopicKey,
            topics = topicsByKey.values.map { topic ->
                TopicBranchState(
                    key = topic.key,
                    displayName = topic.displayName,
                    rollingSummary = topic.rollingSummary,
                    subtopics = topic.subtopicsByKey.values.map { subtopic ->
                        SubtopicBranchState(
                            key = subtopic.key,
                            displayName = subtopic.displayName,
                            messages = subtopic.messages.map { message -> message.copy() },
                        )
                    },
                )
            },
        )
    }

    fun activeBranch(): ActiveBranch {
        val topic = activeTopic()
        val subtopic = activeSubtopic(topic)
        return ActiveBranch(
            topic = topic.displayName,
            subtopic = subtopic.displayName,
        )
    }

    fun topicCatalog(): List<BranchTopicCatalogEntry> {
        return topicsByKey.values.map { topic ->
            BranchTopicCatalogEntry(
                key = topic.key,
                topic = topic.displayName,
                isActive = topic.key == activeTopicKey,
                subtopics = topic.subtopicsByKey.values.map { subtopic ->
                    BranchSubtopicCatalogEntry(
                        key = subtopic.key,
                        subtopic = subtopic.displayName,
                        isActive = topic.key == activeTopicKey && subtopic.key == activeSubtopicKey,
                    )
                },
            )
        }
    }

    fun activeContextSnapshot(systemPrompt: String): List<ConversationMessage> {
        val topic = activeTopicOrNull()
        val subtopic = topic?.let { activeSubtopicOrNull(it) }
        val summary = topic?.rollingSummary?.trim().orEmpty()

        return buildList {
            add(ConversationMessage.system(systemPrompt))
            if (summary.isNotEmpty()) {
                val topicDisplayName = topic?.displayName ?: "Unknown Topic"
                add(
                    ConversationMessage.system(
                        buildTopicSummarySystemMessage(
                            topicDisplayName = topicDisplayName,
                            summary = summary,
                        ),
                    ),
                )
            }
            if (subtopic != null) {
                addAll(subtopic.messages.map { message -> message.copy() })
            }
        }
    }

    fun conversationFor(
        prompt: String,
        systemPrompt: String,
        maxEstimatedTokens: Int?,
        estimateTokens: (List<ConversationMessage>) -> Int,
    ): BranchingConversation {
        val topic = activeTopicOrNull()
        val subtopic = topic?.let { activeSubtopicOrNull(it) }
        if (topic == null || subtopic == null) {
            return BranchingConversation(
                conversation = listOf(
                    ConversationMessage.system(systemPrompt),
                    ConversationMessage.user(prompt),
                ),
                truncatedTurns = 0,
            )
        }

        var visibleSubtopicMessages = subtopic.messages.map { message -> message.copy() }
        var truncatedTurns = 0

        while (true) {
            val conversation = buildConversation(
                systemPrompt = systemPrompt,
                topicDisplayName = topic.displayName,
                topicSummary = topic.rollingSummary,
                subtopicMessages = visibleSubtopicMessages,
                prompt = prompt,
            )

            if (maxEstimatedTokens == null || maxEstimatedTokens <= 0) {
                return BranchingConversation(conversation = conversation, truncatedTurns = truncatedTurns)
            }

            if (estimateTokens(conversation) <= maxEstimatedTokens || visibleSubtopicMessages.size < 2) {
                return BranchingConversation(conversation = conversation, truncatedTurns = truncatedTurns)
            }

            visibleSubtopicMessages = visibleSubtopicMessages.drop(2)
            truncatedTurns += 1
        }
    }

    fun resolveAndActivate(
        topicName: String,
        subtopicName: String,
    ): BranchActivationResult {
        val normalizedTopicName = normalizeDisplayName(topicName)
        require(normalizedTopicName.isNotEmpty()) {
            "Branch topic name must not be blank."
        }
        val topicKey = normalizeKey(normalizedTopicName)
        val normalizedSubtopicName = normalizeDisplayName(subtopicName)
        require(normalizedSubtopicName.isNotEmpty()) {
            "Branch subtopic name must not be blank."
        }
        val subtopicKey = normalizeKey(normalizedSubtopicName)

        val previousTopicKey = activeTopicKey
        val previousSubtopicKey = activeSubtopicKey

        val topic = topicsByKey[topicKey]
        val isNewTopic = topic == null
        val resolvedTopic = topic ?: TopicMemory(
            key = topicKey,
            displayName = normalizedTopicName,
            rollingSummary = "",
            subtopicsByKey = linkedMapOf(),
        ).also { createdTopic ->
            topicsByKey[topicKey] = createdTopic
        }

        val subtopic = resolvedTopic.subtopicsByKey[subtopicKey]
        val isNewSubtopic = subtopic == null
        val resolvedSubtopic = subtopic ?: SubtopicMemory(
            key = subtopicKey,
            displayName = normalizedSubtopicName,
            messages = mutableListOf(),
        ).also { createdSubtopic ->
            resolvedTopic.subtopicsByKey[subtopicKey] = createdSubtopic
        }

        activeTopicKey = resolvedTopic.key
        activeSubtopicKey = resolvedSubtopic.key

        val switchedToExisting = !isNewTopic && !isNewSubtopic && (
            previousTopicKey != activeTopicKey || previousSubtopicKey != activeSubtopicKey
            )

        return BranchActivationResult(
            topic = resolvedTopic.displayName,
            subtopic = resolvedSubtopic.displayName,
            isNewTopic = isNewTopic,
            isNewSubtopic = isNewSubtopic,
            switchedToExistingBranch = switchedToExisting,
        )
    }

    fun recordSuccessfulTurn(
        prompt: String,
        response: String,
    ) {
        val topic = activeTopicOrNull()
            ?: error("Cannot store turn without an active topic.")
        val subtopic = activeSubtopicOrNull(topic)
            ?: error("Cannot store turn without an active subtopic.")
        subtopic.messages += ConversationMessage.user(prompt)
        subtopic.messages += ConversationMessage.assistant(response)
    }

    fun activeTopicSummary(): String? {
        val summary = activeTopicOrNull()?.rollingSummary?.trim().orEmpty()
        return summary.ifEmpty { null }
    }

    fun updateActiveTopicSummary(summary: String?) {
        val topic = activeTopicOrNull() ?: return
        topic.rollingSummary = summary?.trim().orEmpty()
    }

    private fun activeTopic(): TopicMemory = topicsByKey[activeTopicKey]
        ?: error("Missing active topic '$activeTopicKey'.")

    private fun activeTopicOrNull(): TopicMemory? {
        if (activeTopicKey.isEmpty()) {
            return null
        }
        return topicsByKey[activeTopicKey]
    }

    private fun activeSubtopic(topic: TopicMemory): SubtopicMemory = topic.subtopicsByKey[activeSubtopicKey]
        ?: error("Missing active subtopic '$activeSubtopicKey' in topic '${topic.key}'.")

    private fun activeSubtopicOrNull(topic: TopicMemory): SubtopicMemory? {
        if (activeSubtopicKey.isEmpty()) {
            return null
        }
        return topic.subtopicsByKey[activeSubtopicKey]
    }

    private fun buildConversation(
        systemPrompt: String,
        topicDisplayName: String,
        topicSummary: String,
        subtopicMessages: List<ConversationMessage>,
        prompt: String,
    ): List<ConversationMessage> {
        val trimmedSummary = topicSummary.trim()
        return buildList {
            add(ConversationMessage.system(systemPrompt))
            if (trimmedSummary.isNotEmpty()) {
                add(
                    ConversationMessage.system(
                        buildTopicSummarySystemMessage(
                            topicDisplayName = topicDisplayName,
                            summary = trimmedSummary,
                        ),
                    ),
                )
            }
            addAll(subtopicMessages)
            add(ConversationMessage.user(prompt))
        }
    }

    private fun isValidSubtopicMessages(messages: List<ConversationMessage>): Boolean {
        if (messages.size % 2 != 0) {
            return false
        }

        for (index in messages.indices) {
            val message = messages[index]
            if (message.content.isBlank()) {
                return false
            }

            val expectedRole = if (index % 2 == 0) {
                MessageRole.USER
            } else {
                MessageRole.ASSISTANT
            }
            if (message.role != expectedRole) {
                return false
            }
        }

        return true
    }

    private fun buildTopicSummarySystemMessage(
        topicDisplayName: String,
        summary: String,
    ): String = """
        Topic summary for '$topicDisplayName':
        $summary
    """.trimIndent()

    private fun normalizeKey(value: String): String {
        return normalizeDisplayName(value).lowercase()
    }

    private fun normalizeDisplayName(
        value: String,
    ): String {
        val normalized = value.trim()
            .split(Regex("\\s+"))
            .filter { token -> token.isNotBlank() }
            .joinToString(separator = " ")
        return normalized
    }

    private data class TopicMemory(
        val key: String,
        val displayName: String,
        var rollingSummary: String,
        val subtopicsByKey: LinkedHashMap<String, SubtopicMemory>,
    )

    private data class SubtopicMemory(
        val key: String,
        val displayName: String,
        val messages: MutableList<ConversationMessage>,
    )

    companion object {
        private const val LEGACY_GENERAL_KEY = "general"
    }
}

data class ActiveBranch(
    val topic: String,
    val subtopic: String,
)

data class BranchTopicCatalogEntry(
    val key: String,
    val topic: String,
    val isActive: Boolean,
    val subtopics: List<BranchSubtopicCatalogEntry>,
)

data class BranchSubtopicCatalogEntry(
    val key: String,
    val subtopic: String,
    val isActive: Boolean,
)

data class BranchingConversation(
    val conversation: List<ConversationMessage>,
    val truncatedTurns: Int,
)

data class BranchActivationResult(
    val topic: String,
    val subtopic: String,
    val isNewTopic: Boolean,
    val isNewSubtopic: Boolean,
    val switchedToExistingBranch: Boolean,
)
