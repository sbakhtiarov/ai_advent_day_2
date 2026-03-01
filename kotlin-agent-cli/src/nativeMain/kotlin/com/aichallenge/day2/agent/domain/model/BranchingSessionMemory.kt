package com.aichallenge.day2.agent.domain.model

class BranchingSessionMemory {
    private val topicsByKey = linkedMapOf<String, TopicMemory>()
    private var activeTopicKey: String = normalizeKey(DEFAULT_TOPIC_NAME)
    private var activeSubtopicKey: String = normalizeKey(DEFAULT_SUBTOPIC_NAME)

    init {
        reset()
    }

    fun reset() {
        topicsByKey.clear()
        val topic = TopicMemory(
            key = normalizeKey(DEFAULT_TOPIC_NAME),
            displayName = DEFAULT_TOPIC_NAME,
            rollingSummary = "",
            subtopicsByKey = linkedMapOf(
                normalizeKey(DEFAULT_SUBTOPIC_NAME) to SubtopicMemory(
                    key = normalizeKey(DEFAULT_SUBTOPIC_NAME),
                    displayName = DEFAULT_SUBTOPIC_NAME,
                    messages = mutableListOf(),
                ),
            ),
        )
        topicsByKey[topic.key] = topic
        activeTopicKey = topic.key
        activeSubtopicKey = normalizeKey(DEFAULT_SUBTOPIC_NAME)
    }

    fun restore(state: BranchingMemoryState?): Boolean {
        if (state == null) {
            reset()
            return false
        }

        val requestedActiveTopicKey = normalizeKey(state.activeTopicKey)
        val requestedActiveSubtopicKey = normalizeKey(state.activeSubtopicKey)
        if (requestedActiveTopicKey.isEmpty() || requestedActiveSubtopicKey.isEmpty() || state.topics.isEmpty()) {
            reset()
            return false
        }

        val loadedTopics = linkedMapOf<String, TopicMemory>()
        for (topicState in state.topics) {
            val topicKey = normalizeKey(topicState.key)
            val topicDisplayName = normalizeDisplayName(topicState.displayName, DEFAULT_TOPIC_NAME)
            if (topicKey.isEmpty() || loadedTopics.containsKey(topicKey)) {
                reset()
                return false
            }

            if (topicState.subtopics.isEmpty()) {
                reset()
                return false
            }

            val loadedSubtopics = linkedMapOf<String, SubtopicMemory>()
            for (subtopicState in topicState.subtopics) {
                val subtopicKey = normalizeKey(subtopicState.key)
                val subtopicDisplayName = normalizeDisplayName(subtopicState.displayName, DEFAULT_SUBTOPIC_NAME)
                val messageCopies = subtopicState.messages.map { it.copy() }

                if (subtopicKey.isEmpty() || loadedSubtopics.containsKey(subtopicKey)) {
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

            loadedTopics[topicKey] = TopicMemory(
                key = topicKey,
                displayName = topicDisplayName,
                rollingSummary = topicState.rollingSummary,
                subtopicsByKey = loadedSubtopics,
            )
        }

        val activeTopic = loadedTopics[requestedActiveTopicKey]
        val activeSubtopic = activeTopic?.subtopicsByKey?.get(requestedActiveSubtopicKey)
        if (activeTopic == null || activeSubtopic == null) {
            reset()
            return false
        }

        topicsByKey.clear()
        topicsByKey.putAll(loadedTopics)
        activeTopicKey = requestedActiveTopicKey
        activeSubtopicKey = requestedActiveSubtopicKey
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
                topic = topic.displayName,
                subtopics = topic.subtopicsByKey.values.map { subtopic -> subtopic.displayName },
            )
        }
    }

    fun activeContextSnapshot(systemPrompt: String): List<ConversationMessage> {
        val topic = activeTopic()
        val subtopic = activeSubtopic(topic)
        val summary = topic.rollingSummary.trim()

        return buildList {
            add(ConversationMessage.system(systemPrompt))
            if (summary.isNotEmpty()) {
                add(
                    ConversationMessage.system(
                        buildTopicSummarySystemMessage(
                            topicDisplayName = topic.displayName,
                            summary = summary,
                        ),
                    ),
                )
            }
            addAll(subtopic.messages.map { message -> message.copy() })
        }
    }

    fun conversationFor(
        prompt: String,
        systemPrompt: String,
        maxEstimatedTokens: Int?,
        estimateTokens: (List<ConversationMessage>) -> Int,
    ): BranchingConversation {
        val topic = activeTopic()
        val subtopic = activeSubtopic(topic)
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
        val normalizedTopicName = normalizeDisplayName(topicName, DEFAULT_TOPIC_NAME)
        val topicKey = normalizeKey(normalizedTopicName)
        val normalizedSubtopicName = normalizeDisplayName(subtopicName, DEFAULT_SUBTOPIC_NAME)
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
        val subtopic = activeSubtopic(activeTopic())
        subtopic.messages += ConversationMessage.user(prompt)
        subtopic.messages += ConversationMessage.assistant(response)
    }

    fun activeTopicSummary(): String? {
        val summary = activeTopic().rollingSummary.trim()
        return summary.ifEmpty { null }
    }

    fun updateActiveTopicSummary(summary: String?) {
        activeTopic().rollingSummary = summary?.trim().orEmpty()
    }

    private fun activeTopic(): TopicMemory = topicsByKey[activeTopicKey]
        ?: error("Missing active topic '$activeTopicKey'.")

    private fun activeSubtopic(topic: TopicMemory): SubtopicMemory = topic.subtopicsByKey[activeSubtopicKey]
        ?: error("Missing active subtopic '$activeSubtopicKey' in topic '${topic.key}'.")

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
        return normalizeDisplayName(value, "").lowercase()
    }

    private fun normalizeDisplayName(
        value: String,
        fallback: String,
    ): String {
        val normalized = value.trim()
            .split(Regex("\\s+"))
            .filter { token -> token.isNotBlank() }
            .joinToString(separator = " ")
        return normalized.ifEmpty { fallback }
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
        private const val DEFAULT_TOPIC_NAME = "General"
        private const val DEFAULT_SUBTOPIC_NAME = "General"
    }
}

data class ActiveBranch(
    val topic: String,
    val subtopic: String,
)

data class BranchTopicCatalogEntry(
    val topic: String,
    val subtopics: List<String>,
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
