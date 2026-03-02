package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.BranchSubtopicCatalogEntry
import com.aichallenge.day2.agent.domain.model.BranchTopicCatalogEntry
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.repository.AgentRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BranchClassificationUseCaseTest {
    @Test
    fun classifyAcceptsExistingTopicAndSubtopicDecisionWithoutValidation() = runBlocking {
        val repository = RecordingClassifierRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "topic": {"kind": "existing", "key": "game development", "name": "Game Development"},
                          "subtopic": {"kind": "existing", "key": "game design", "name": "Game Design"}
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val useCase = BranchClassificationUseCase(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val result = useCase.classify(
            existingTopics = existingGameCatalog(),
            userPrompt = "Let's keep discussing balancing.",
            assistantResponse = "Sure, we can tune mechanics.",
            model = "gpt-4.1-mini",
        )

        assertEquals("game development", result.topicKey)
        assertEquals("Game Development", result.topicName)
        assertEquals(BranchReferenceKind.EXISTING, result.topicKind)
        assertEquals("game design", result.subtopicKey)
        assertEquals("Game Design", result.subtopicName)
        assertEquals(BranchReferenceKind.EXISTING, result.subtopicKind)
        assertEquals(false, result.usedFallback)
        assertEquals(false, result.usedValidation)
        assertEquals(1, repository.conversations.size)
        assertEquals(listOf<Double?>(0.0), repository.temperatures)
        assertEquals(listOf<String?>("gpt-4.1-mini"), repository.models)
    }

    @Test
    fun classifySupportsLegacySimpleTopicSubtopicJson() = runBlocking {
        val repository = RecordingClassifierRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = """{"topic":"Game Development","subtopic":"Game Design"}""",
                    ),
                ),
            ),
        )
        val useCase = BranchClassificationUseCase(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val result = useCase.classify(
            existingTopics = existingGameCatalog(),
            userPrompt = "Talk about character traits in current design scope.",
            assistantResponse = "Let's keep this in game design.",
            model = "gpt-4.1-mini",
        )

        assertEquals("game development", result.topicKey)
        assertEquals(BranchReferenceKind.EXISTING, result.topicKind)
        assertEquals("game design", result.subtopicKey)
        assertEquals(BranchReferenceKind.EXISTING, result.subtopicKind)
        assertEquals(false, result.usedFallback)
    }

    @Test
    fun classifySupportsLegacyNestedNameObjects() = runBlocking {
        val repository = RecordingClassifierRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "topic": {"name": "Game Development"},
                          "subtopic": {"name": "Game Design"}
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val useCase = BranchClassificationUseCase(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val result = useCase.classify(
            existingTopics = existingGameCatalog(),
            userPrompt = "Continue discussing character traits.",
            assistantResponse = "This stays in game design.",
            model = "gpt-4.1-mini",
        )

        assertEquals("game development", result.topicKey)
        assertEquals(BranchReferenceKind.EXISTING, result.topicKind)
        assertEquals("game design", result.subtopicKey)
        assertEquals(BranchReferenceKind.EXISTING, result.subtopicKind)
        assertEquals(false, result.usedFallback)
    }

    @Test
    fun classifyRejectsStructuredGeneralAndUsesRetry() = runBlocking {
        val repository = RecordingClassifierRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "topic": {"kind": "existing", "key": "general", "name": "General"},
                          "subtopic": {"kind": "existing", "key": "general", "name": "General"}
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "topic": {"kind": "existing", "key": "game development", "name": "Game Development"},
                          "subtopic": {"kind": "existing", "key": "game design", "name": "Game Design"}
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val useCase = BranchClassificationUseCase(sendPromptUseCase = SendPromptUseCase(repository))

        val result = useCase.classify(
            existingTopics = existingGameCatalog(),
            userPrompt = "Continue.",
            assistantResponse = "Okay.",
            model = "gpt-4.1-mini",
        )

        assertEquals("game development", result.topicKey)
        assertEquals("game design", result.subtopicKey)
        assertEquals(false, result.usedFallback)
        assertEquals(2, repository.conversations.size)
    }

    @Test
    fun classifyRejectsLegacyGeneralAndUsesRetry() = runBlocking {
        val repository = RecordingClassifierRepository(
            responses = listOf(
                Result.success(AgentResponse(content = """{"topic":"General","subtopic":"General"}""")),
                Result.success(
                    AgentResponse(
                        content = """{"topic":"Game Development","subtopic":"Game Design"}""",
                    ),
                ),
            ),
        )
        val useCase = BranchClassificationUseCase(sendPromptUseCase = SendPromptUseCase(repository))

        val result = useCase.classify(
            existingTopics = existingGameCatalog(),
            userPrompt = "Continue.",
            assistantResponse = "Okay.",
            model = "gpt-4.1-mini",
        )

        assertEquals("game development", result.topicKey)
        assertEquals("game design", result.subtopicKey)
        assertEquals(false, result.usedFallback)
        assertEquals(2, repository.conversations.size)
    }

    @Test
    fun classifyCreatesNewTopicWhenTopicShiftDetectorSignalsDomainSwitch() = runBlocking {
        val repository = RecordingClassifierRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "topic": {"kind": "existing", "key": "game development", "name": "Game Development"},
                          "subtopic": {"kind": "existing", "key": "game design", "name": "Game Design"}
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "createNewTopic": true,
                          "topic": "Apartment Painting",
                          "subtopic": "Wall Preparation"
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val useCase = BranchClassificationUseCase(sendPromptUseCase = SendPromptUseCase(repository))

        val result = useCase.classify(
            existingTopics = listOf(
                BranchTopicCatalogEntry(
                    key = "game development",
                    topic = "Game Development",
                    isActive = true,
                    subtopics = listOf(
                        BranchSubtopicCatalogEntry(
                            key = "game design",
                            subtopic = "Game Design",
                            isActive = true,
                        ),
                    ),
                ),
            ),
            userPrompt = "Need to repaint apartment walls and pick primer.",
            assistantResponse = "Let's plan wall cleaning and preparation first.",
            model = "gpt-4.1-mini",
        )

        assertEquals("apartment painting", result.topicKey)
        assertEquals(BranchReferenceKind.NEW, result.topicKind)
        assertEquals("wall preparation", result.subtopicKey)
        assertEquals(BranchReferenceKind.NEW, result.subtopicKind)
        assertEquals(false, result.usedFallback)
        assertEquals(true, result.usedValidation)
        assertEquals(2, repository.conversations.size)
    }

    @Test
    fun classifyTopicShiftProbeAlsoRunsWhenPrimaryProposesNewSubtopicInSingleTopicCatalog() = runBlocking {
        val repository = RecordingClassifierRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "topic": {"kind": "existing", "key": "game development", "name": "Game Development"},
                          "subtopic": {"kind": "new", "key": "", "name": "Wall Painting"}
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "createNewTopic": true,
                          "topic": "Apartment Painting",
                          "subtopic": "Wall Preparation"
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val useCase = BranchClassificationUseCase(sendPromptUseCase = SendPromptUseCase(repository))

        val result = useCase.classify(
            existingTopics = listOf(
                BranchTopicCatalogEntry(
                    key = "game development",
                    topic = "Game Development",
                    isActive = true,
                    subtopics = listOf(
                        BranchSubtopicCatalogEntry(
                            key = "game design",
                            subtopic = "Game Design",
                            isActive = true,
                        ),
                    ),
                ),
            ),
            userPrompt = "Need to repaint apartment walls.",
            assistantResponse = "Let's plan primer and roller steps.",
            model = "gpt-4.1-mini",
        )

        assertEquals("apartment painting", result.topicKey)
        assertEquals(BranchReferenceKind.NEW, result.topicKind)
        assertEquals("wall preparation", result.subtopicKey)
        assertEquals(BranchReferenceKind.NEW, result.subtopicKind)
        assertEquals(false, result.usedFallback)
        assertEquals(true, result.usedValidation)
        assertEquals(2, repository.conversations.size)
    }

    @Test
    fun classifySupportsEmptyCatalogWithNewBranchDecision() = runBlocking {
        val repository = RecordingClassifierRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "topic": {"kind": "new", "key": "", "name": "Building new application"},
                          "subtopic": {"kind": "new", "key": "", "name": "Architecture"}
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "allowNewTopic": true,
                          "reuseTopicKey": "",
                          "allowNewSubtopic": true,
                          "reuseSubtopicKey": ""
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val useCase = BranchClassificationUseCase(sendPromptUseCase = SendPromptUseCase(repository))

        val result = useCase.classify(
            existingTopics = emptyList(),
            userPrompt = "Let's design a new app architecture.",
            assistantResponse = "Start from service boundaries.",
            model = "gpt-4.1-mini",
        )

        assertEquals("building new application", result.topicKey)
        assertEquals(BranchReferenceKind.NEW, result.topicKind)
        assertEquals("architecture", result.subtopicKey)
        assertEquals(BranchReferenceKind.NEW, result.subtopicKind)
        assertEquals(false, result.usedFallback)
        assertEquals(true, result.usedValidation)
    }

    @Test
    fun classifyDowngradesNewSubtopicWhenValidatorRejectsNovelty() = runBlocking {
        val repository = RecordingClassifierRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "topic": {"kind": "existing", "key": "game development", "name": "Game Development"},
                          "subtopic": {"kind": "new", "key": "", "name": "Characters System"}
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "allowNewTopic": false,
                          "reuseTopicKey": "game development",
                          "allowNewSubtopic": false,
                          "reuseSubtopicKey": "game design"
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val useCase = BranchClassificationUseCase(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val result = useCase.classify(
            existingTopics = existingGameCatalog(),
            userPrompt = "Let's define character traits.",
            assistantResponse = "Character traits should align with overall design pillars.",
            model = "gpt-4.1-mini",
        )

        assertEquals("game development", result.topicKey)
        assertEquals(BranchReferenceKind.EXISTING, result.topicKind)
        assertEquals("game design", result.subtopicKey)
        assertEquals("Game Design", result.subtopicName)
        assertEquals(BranchReferenceKind.EXISTING, result.subtopicKind)
        assertEquals(false, result.usedFallback)
        assertEquals(true, result.usedValidation)
        assertEquals(2, repository.conversations.size)
    }

    @Test
    fun classifyKeepsNewSubtopicWhenValidatorAllowsNovelty() = runBlocking {
        val repository = RecordingClassifierRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "topic": {"kind": "existing", "key": "game development", "name": "Game Development"},
                          "subtopic": {"kind": "new", "key": "", "name": "Characters System"}
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "allowNewTopic": false,
                          "reuseTopicKey": "game development",
                          "allowNewSubtopic": true,
                          "reuseSubtopicKey": ""
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val useCase = BranchClassificationUseCase(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val result = useCase.classify(
            existingTopics = existingGameCatalog(),
            userPrompt = "Let's create a dedicated character pipeline.",
            assistantResponse = "This may deserve a separate track.",
            model = "gpt-4.1-mini",
        )

        assertEquals("game development", result.topicKey)
        assertEquals(BranchReferenceKind.EXISTING, result.topicKind)
        assertEquals("characters system", result.subtopicKey)
        assertEquals("Characters System", result.subtopicName)
        assertEquals(BranchReferenceKind.NEW, result.subtopicKind)
        assertEquals(false, result.usedFallback)
        assertEquals(true, result.usedValidation)
        assertEquals(2, repository.conversations.size)
    }

    @Test
    fun classifyUsesStrictExistingFallbackWhenValidationResponseIsInvalid() = runBlocking {
        val repository = RecordingClassifierRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "topic": {"kind": "existing", "key": "game development", "name": "Game Development"},
                          "subtopic": {"kind": "new", "key": "", "name": "Characters System"}
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(AgentResponse(content = "not json")),
            ),
        )
        val useCase = BranchClassificationUseCase(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val result = useCase.classify(
            existingTopics = existingGameCatalog(),
            userPrompt = "Let's discuss traits again.",
            assistantResponse = "Still connected to game design.",
            model = "gpt-4.1-mini",
        )

        assertEquals("game development", result.topicKey)
        assertEquals(BranchReferenceKind.EXISTING, result.topicKind)
        assertEquals("game design", result.subtopicKey)
        assertEquals(BranchReferenceKind.EXISTING, result.subtopicKind)
        assertEquals(false, result.usedFallback)
        assertEquals(true, result.usedValidation)
        assertEquals(2, repository.conversations.size)
    }

    @Test
    fun classifyRejectsMismatchedExistingSubtopicKeyAndUsesRetry() = runBlocking {
        val repository = RecordingClassifierRepository(
            responses = listOf(
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "topic": {"kind": "existing", "key": "game development", "name": "Game Development"},
                          "subtopic": {"kind": "existing", "key": "network api", "name": "Network API"}
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "topic": {"kind": "existing", "key": "game development", "name": "Game Development"},
                          "subtopic": {"kind": "existing", "key": "game design", "name": "Game Design"}
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val useCase = BranchClassificationUseCase(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val result = useCase.classify(
            existingTopics = existingGameCatalog(),
            userPrompt = "Character abilities should follow current style.",
            assistantResponse = "Let's keep this in design scope.",
            model = "gpt-4.1-mini",
        )

        assertEquals("game design", result.subtopicKey)
        assertEquals(BranchReferenceKind.EXISTING, result.subtopicKind)
        assertEquals(false, result.usedFallback)
        assertEquals(false, result.usedValidation)
        assertEquals(2, repository.conversations.size)
    }

    @Test
    fun classifyFallsBackToActiveBranchAfterTwoPrimaryFailures() = runBlocking {
        val repository = RecordingClassifierRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "bad")),
                Result.success(AgentResponse(content = "still bad")),
            ),
        )
        val useCase = BranchClassificationUseCase(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val result = useCase.classify(
            existingTopics = existingGameCatalog(),
            userPrompt = "Need direction",
            assistantResponse = "Let's continue current flow.",
            model = "gpt-4.1-mini",
        )

        assertEquals("game development", result.topicKey)
        assertEquals("game design", result.subtopicKey)
        assertEquals(true, result.usedFallback)
        assertEquals(false, result.usedValidation)
    }

    @Test
    fun classifyFallsBackToDerivedSpecificBranchWhenNoActiveBranchExists() = runBlocking {
        val repository = RecordingClassifierRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "bad")),
                Result.success(AgentResponse(content = "still bad")),
            ),
        )
        val useCase = BranchClassificationUseCase(
            sendPromptUseCase = SendPromptUseCase(repository),
        )

        val result = useCase.classify(
            existingTopics = emptyList(),
            userPrompt = "Need payment retry queue implementation",
            assistantResponse = "Let's define queue policy and retry strategy.",
            model = "gpt-4.1-mini",
        )

        assertEquals(true, result.usedFallback)
        assertEquals(BranchReferenceKind.NEW, result.topicKind)
        assertEquals(BranchReferenceKind.NEW, result.subtopicKind)
        assertTrue(result.topicKey.isNotBlank())
        assertTrue(result.subtopicKey.isNotBlank())
        assertNotEquals("general", result.topicKey)
        assertNotEquals("general", result.subtopicKey)
    }

    private fun existingGameCatalog(): List<BranchTopicCatalogEntry> {
        return listOf(
            BranchTopicCatalogEntry(
                key = "game development",
                topic = "Game Development",
                isActive = true,
                subtopics = listOf(
                    BranchSubtopicCatalogEntry(
                        key = "game design",
                        subtopic = "Game Design",
                        isActive = true,
                    ),
                    BranchSubtopicCatalogEntry(
                        key = "combat loop",
                        subtopic = "Combat Loop",
                        isActive = false,
                    ),
                ),
            ),
        )
    }
}

private class RecordingClassifierRepository(
    responses: List<Result<AgentResponse>>,
) : AgentRepository {
    private val queuedResponses = ArrayDeque(responses)
    val conversations = mutableListOf<List<ConversationMessage>>()
    val temperatures = mutableListOf<Double?>()
    val models = mutableListOf<String?>()

    override suspend fun complete(
        conversation: List<ConversationMessage>,
        temperature: Double?,
        model: String?,
    ): AgentResponse {
        conversations += conversation
        temperatures += temperature
        models += model

        val response = queuedResponses.removeFirstOrNull()
            ?: error("No prepared classifier response for call #${conversations.size}")
        return response.getOrThrow()
    }
}
