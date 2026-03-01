package com.aichallenge.day2.agent.domain.model

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BranchingSessionMemoryTest {
    @Test
    fun resolveAndActivateMatchesTopicAndSubtopicCaseInsensitively() {
        val memory = BranchingSessionMemory()

        val first = memory.resolveAndActivate(
            topicName = "Building new application",
            subtopicName = "Architecture",
        )
        memory.recordSuccessfulTurn(prompt = "q1", response = "a1")

        val second = memory.resolveAndActivate(
            topicName = " building   NEW application ",
            subtopicName = " architecture ",
        )

        assertEquals(true, first.isNewTopic)
        assertEquals(true, first.isNewSubtopic)
        assertEquals(false, second.isNewTopic)
        assertEquals(false, second.isNewSubtopic)
        assertEquals(false, second.switchedToExistingBranch)
        assertEquals(
            ActiveBranch(
                topic = "Building new application",
                subtopic = "Architecture",
            ),
            memory.activeBranch(),
        )
    }

    @Test
    fun storesTurnsOnlyInSubtopicHistory() {
        val memory = BranchingSessionMemory()
        memory.resolveAndActivate(topicName = "Building new application", subtopicName = "Feature A")
        memory.recordSuccessfulTurn(prompt = "feature prompt", response = "feature answer")

        val switched = memory.resolveAndActivate(
            topicName = "Building new application",
            subtopicName = "Network API",
        )
        val context = memory.activeContextSnapshot(systemPrompt = "system")

        assertEquals(false, switched.isNewTopic)
        assertEquals(true, switched.isNewSubtopic)
        assertEquals(
            listOf(
                MessageRole.SYSTEM,
            ),
            context.map { message -> message.role },
        )
        assertFalse(context.any { message -> message.content.contains("feature prompt") })
    }

    @Test
    fun topicSummaryIsStoredSeparatelyAndInjectedIntoContext() {
        val memory = BranchingSessionMemory()
        memory.resolveAndActivate(topicName = "Building new application", subtopicName = "Feature A")
        memory.recordSuccessfulTurn(prompt = "feature prompt", response = "feature answer")
        memory.updateActiveTopicSummary("topic-level summary")

        memory.resolveAndActivate(topicName = "Building new application", subtopicName = "Network API")
        val context = memory.activeContextSnapshot(systemPrompt = "system")

        assertEquals(
            listOf(
                MessageRole.SYSTEM,
                MessageRole.SYSTEM,
            ),
            context.map { message -> message.role },
        )
        assertContains(context[1].content, "topic-level summary")

        val snapshot = memory.snapshot()
        val topic = snapshot.topics.first { branch -> branch.displayName == "Building new application" }
        assertEquals("topic-level summary", topic.rollingSummary)
        val featureSubtopic = topic.subtopics.first { subtopic -> subtopic.displayName == "Feature A" }
        assertEquals(2, featureSubtopic.messages.size)
    }

    @Test
    fun conversationForTruncatesOldestTurnsWithoutMutatingStoredSubtopicHistory() {
        val memory = BranchingSessionMemory()
        memory.resolveAndActivate(topicName = "Building new application", subtopicName = "Architecture")
        memory.recordSuccessfulTurn(prompt = "q1", response = "a1")
        memory.recordSuccessfulTurn(prompt = "q2", response = "a2")
        memory.recordSuccessfulTurn(prompt = "q3", response = "a3")

        val conversation = memory.conversationFor(
            prompt = "next",
            systemPrompt = "system",
            maxEstimatedTokens = 1,
            estimateTokens = { messages -> messages.size * 100 },
        )

        assertEquals(3, conversation.truncatedTurns)
        assertEquals(
            listOf(
                ConversationMessage.system("system"),
                ConversationMessage.user("next"),
            ),
            conversation.conversation,
        )

        val snapshot = memory.snapshot()
        val topic = snapshot.topics.first { branch -> branch.displayName == "Building new application" }
        val subtopic = topic.subtopics.first { branch -> branch.displayName == "Architecture" }
        assertEquals(6, subtopic.messages.size)
    }
}
