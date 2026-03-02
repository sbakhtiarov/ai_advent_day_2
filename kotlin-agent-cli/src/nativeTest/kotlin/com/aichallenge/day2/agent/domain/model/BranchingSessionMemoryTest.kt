package com.aichallenge.day2.agent.domain.model

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BranchingSessionMemoryTest {
    @Test
    fun resetProducesEmptyCatalogAndSystemOnlyContext() {
        val memory = BranchingSessionMemory()

        val catalog = memory.topicCatalog()
        val context = memory.activeContextSnapshot(systemPrompt = "system")

        assertTrue(catalog.isEmpty())
        assertEquals(listOf(ConversationMessage.system("system")), context)
        val snapshot = memory.snapshot()
        assertEquals("", snapshot.activeTopicKey)
        assertEquals("", snapshot.activeSubtopicKey)
        assertTrue(snapshot.topics.isEmpty())
    }

    @Test
    fun topicCatalogExposesStableKeysAndActiveBranchFlags() {
        val memory = BranchingSessionMemory()
        memory.resolveAndActivate(topicName = "Game Development", subtopicName = "Game Design")
        memory.resolveAndActivate(topicName = "Game Development", subtopicName = "Combat Loop")

        val catalog = memory.topicCatalog()
        val gameDev = catalog.first { topic -> topic.key == "game development" }
        val gameDesign = gameDev.subtopics.first { subtopic -> subtopic.key == "game design" }
        val combatLoop = gameDev.subtopics.first { subtopic -> subtopic.key == "combat loop" }

        assertEquals(true, gameDev.isActive)
        assertEquals(false, gameDesign.isActive)
        assertEquals(true, combatLoop.isActive)
    }

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

    @Test
    fun conversationForWithoutActiveBranchBuildsSystemAndUserOnly() {
        val memory = BranchingSessionMemory()

        val conversation = memory.conversationFor(
            prompt = "next",
            systemPrompt = "system",
            maxEstimatedTokens = 1,
            estimateTokens = { messages -> messages.size * 10 },
        )

        assertEquals(
            listOf(
                ConversationMessage.system("system"),
                ConversationMessage.user("next"),
            ),
            conversation.conversation,
        )
        assertEquals(0, conversation.truncatedTurns)
    }

    @Test
    fun resolveAndActivateRejectsBlankNames() {
        val memory = BranchingSessionMemory()

        assertFailsWith<IllegalArgumentException> {
            memory.resolveAndActivate(
                topicName = "   ",
                subtopicName = "Architecture",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            memory.resolveAndActivate(
                topicName = "Building new application",
                subtopicName = "   ",
            )
        }
    }

    @Test
    fun restoreDropsLegacyGeneralAndKeepsConcreteBranches() {
        val memory = BranchingSessionMemory()

        val restored = memory.restore(
            BranchingMemoryState(
                activeTopicKey = "general",
                activeSubtopicKey = "general",
                topics = listOf(
                    TopicBranchState(
                        key = "general",
                        displayName = "General",
                        rollingSummary = "legacy",
                        subtopics = listOf(
                            SubtopicBranchState(
                                key = "general",
                                displayName = "General",
                                messages = emptyList(),
                            ),
                        ),
                    ),
                    TopicBranchState(
                        key = "building new application",
                        displayName = "Building new application",
                        rollingSummary = "summary",
                        subtopics = listOf(
                            SubtopicBranchState(
                                key = "architecture",
                                displayName = "Architecture",
                                messages = listOf(
                                    ConversationMessage.user("q1"),
                                    ConversationMessage.assistant("a1"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(restored)
        val snapshot = memory.snapshot()
        assertEquals("building new application", snapshot.activeTopicKey)
        assertEquals("architecture", snapshot.activeSubtopicKey)
        assertEquals(1, snapshot.topics.size)
        assertEquals("building new application", snapshot.topics.single().key)
    }

    @Test
    fun restoreLegacyGeneralOnlyProducesEmptyValidState() {
        val memory = BranchingSessionMemory()

        val restored = memory.restore(
            BranchingMemoryState(
                activeTopicKey = "general",
                activeSubtopicKey = "general",
                topics = listOf(
                    TopicBranchState(
                        key = "general",
                        displayName = "General",
                        rollingSummary = "legacy",
                        subtopics = listOf(
                            SubtopicBranchState(
                                key = "general",
                                displayName = "General",
                                messages = emptyList(),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(restored)
        val snapshot = memory.snapshot()
        assertEquals("", snapshot.activeTopicKey)
        assertEquals("", snapshot.activeSubtopicKey)
        assertTrue(snapshot.topics.isEmpty())
    }
}
