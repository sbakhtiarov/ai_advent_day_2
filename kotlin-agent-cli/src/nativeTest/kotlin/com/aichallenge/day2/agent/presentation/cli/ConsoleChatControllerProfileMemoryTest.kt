package com.aichallenge.day2.agent.presentation.cli

import com.aichallenge.day2.agent.core.config.ModelPricing
import com.aichallenge.day2.agent.core.config.ModelProperties
import com.aichallenge.day2.agent.core.config.ProfileEnvironmentFactsProvider
import com.aichallenge.day2.agent.domain.model.AgentResponse
import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.MessageRole
import com.aichallenge.day2.agent.domain.model.ProfileEnvironmentFacts
import com.aichallenge.day2.agent.domain.model.ProfileMemoryState
import com.aichallenge.day2.agent.domain.model.ProfilePreferenceState
import com.aichallenge.day2.agent.domain.model.PromptRequestData
import com.aichallenge.day2.agent.domain.model.SessionCompactionMode
import com.aichallenge.day2.agent.domain.model.UserProfileOption
import com.aichallenge.day2.agent.domain.repository.AgentRepository
import com.aichallenge.day2.agent.domain.repository.ProfileMemoryStore
import com.aichallenge.day2.agent.domain.repository.UserDefinedProfileStore
import com.aichallenge.day2.agent.domain.usecase.ProfileMemoryDistillationUseCase
import com.aichallenge.day2.agent.domain.usecase.SendPromptUseCase
import com.aichallenge.day2.agent.domain.usecase.SessionMemoryCompactionCoordinator
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleChatControllerProfileMemoryTest {
    @Test
    fun interactiveModeLoadsPersistedProfileMemory() = runBlocking {
        val repository = ProfileMemoryControllerTestAgentRepository(responses = emptyList())
        val profileStore = RecordingProfileMemoryStore(
            loadedState = ProfileMemoryState(
                preferences = ProfilePreferenceState(
                    writingStyle = "concise",
                ),
                environmentFacts = ProfileEnvironmentFacts(
                    timezone = "UTC",
                    os = "LEGACY_OS",
                    repoPath = "/legacy/repo",
                ),
            ),
        )
        val controller = createController(
            repository = repository,
            io = ProfileMemoryControllerTestCliIO(inputs = listOf("/exit")),
            profileMemoryStore = profileStore,
            profileEnvironmentFactsProvider = FixedProfileEnvironmentFactsProvider(
                ProfileEnvironmentFacts(
                    timezone = "Europe/Berlin",
                    os = "MACOS",
                    repoPath = "/repo/path",
                ),
            ),
        )

        controller.runInteractive()

        assertEquals(1, profileStore.loadCalls)
        assertEquals(0, profileStore.saveStates.size)
        assertEquals(0, profileStore.clearCalls)
    }

    @Test
    fun firstInteractivePromptInjectsPersistedProfileMemoryContext() = runBlocking {
        val repository = ProfileMemoryControllerTestAgentRepository(
            responses = listOf(Result.success(AgentResponse(content = "assistant answer"))),
        )
        val profileStore = RecordingProfileMemoryStore(
            loadedState = ProfileMemoryState(
                preferences = ProfilePreferenceState(
                    writingStyle = "concise bullets",
                    toolingPreferences = listOf("use rg"),
                ),
                environmentFacts = ProfileEnvironmentFacts(
                    timezone = "UTC",
                    os = "LEGACY_OS",
                    repoPath = "/legacy/repo",
                ),
            ),
        )
        val controller = createController(
            repository = repository,
            io = ProfileMemoryControllerTestCliIO(inputs = listOf("Prompt one", "/exit")),
            profileMemoryStore = profileStore,
            profileEnvironmentFactsProvider = FixedProfileEnvironmentFactsProvider(
                ProfileEnvironmentFacts(
                    timezone = "Europe/Berlin",
                    os = "MACOS",
                    repoPath = "/repo/path",
                ),
            ),
        )

        controller.runInteractive()

        assertEquals(1, profileStore.loadCalls)
        assertEquals(1, repository.conversations.size)
        val firstRequest = repository.conversations.single()
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.SYSTEM, MessageRole.SYSTEM, MessageRole.USER),
            firstRequest.map { message -> message.role },
        )
        assertContains(firstRequest[1].content, "Profile preference policy:")
        assertContains(firstRequest[1].content, "Collect key profile facts only from explicit user input.")
        assertContains(firstRequest[1].content, "ask 1 or 2 concise relevant questions.")
        assertContains(
            firstRequest[2].content,
            "Profile memory snapshot (persistent user defaults):",
        )
        assertContains(firstRequest[2].content, "\"writing_style\":\"concise bullets\"")
        assertContains(firstRequest[2].content, "\"tooling_preferences\":[\"use rg\"]")
        assertContains(
            firstRequest[2].content,
            "\"environment\":{\"timezone\":\"Europe/Berlin\",\"os\":\"MACOS\",\"repo_path\":\"/repo/path\"}",
        )
    }

    @Test
    fun firstInteractivePromptInjectsUserDefinedProfileIntoSystemPrompt() = runBlocking {
        val repository = ProfileMemoryControllerTestAgentRepository(
            responses = listOf(Result.success(AgentResponse(content = "assistant answer"))),
        )
        val userDefinedProfileStore = RecordingUserDefinedProfileStore(
            loadedState = ProfilePreferenceState(
                writingStyle = "concise bullets",
                toolingPreferences = listOf("use rg"),
            ),
        )
        val controller = createController(
            repository = repository,
            io = ProfileMemoryControllerTestCliIO(inputs = listOf("Prompt one", "/exit")),
            profileMemoryStore = RecordingProfileMemoryStore(
                loadedState = ProfileMemoryState(
                    preferences = ProfilePreferenceState(),
                    environmentFacts = ProfileEnvironmentFacts(
                        timezone = "UTC",
                        os = "MACOS",
                        repoPath = "/repo/path",
                    ),
                ),
            ),
            userDefinedProfileStore = userDefinedProfileStore,
        )

        controller.runInteractive()

        assertEquals(1, userDefinedProfileStore.loadCalls)
        val firstRequest = repository.conversations.single()
        assertContains(firstRequest[0].content, "User-defined profile defaults (highest priority):")
        assertContains(firstRequest[0].content, "\"writing_style\":\"concise bullets\"")
        assertContains(firstRequest[0].content, "\"tooling_preferences\":[\"use rg\"]")
    }

    @Test
    fun profileMemoryContextUsesUserDefinedOverridesOverDistilledState() = runBlocking {
        val repository = ProfileMemoryControllerTestAgentRepository(
            responses = listOf(Result.success(AgentResponse(content = "assistant answer"))),
        )
        val profileStore = RecordingProfileMemoryStore(
            loadedState = ProfileMemoryState(
                preferences = ProfilePreferenceState(
                    writingStyle = "verbose style",
                    toolingPreferences = listOf("use grep"),
                    workflowDefaults = listOf("wait for confirmation"),
                ),
                environmentFacts = ProfileEnvironmentFacts(
                    timezone = "Europe/Berlin",
                    os = "MACOS",
                    repoPath = "/repo/path",
                ),
            ),
        )
        val userDefinedProfileStore = RecordingUserDefinedProfileStore(
            loadedState = ProfilePreferenceState(
                writingStyle = "concise bullets",
                toolingPreferences = listOf("use rg"),
                work = "Mobile platform at Wire",
            ),
        )
        val controller = createController(
            repository = repository,
            io = ProfileMemoryControllerTestCliIO(inputs = listOf("Prompt one", "/exit")),
            profileMemoryStore = profileStore,
            userDefinedProfileStore = userDefinedProfileStore,
            profileEnvironmentFactsProvider = FixedProfileEnvironmentFactsProvider(
                ProfileEnvironmentFacts(
                    timezone = "Europe/Berlin",
                    os = "MACOS",
                    repoPath = "/repo/path",
                ),
            ),
        )

        controller.runInteractive()

        val firstRequest = repository.conversations.single()
        assertContains(firstRequest[2].content, "\"writing_style\":\"concise bullets\"")
        assertContains(firstRequest[2].content, "\"tooling_preferences\":[\"use rg\"]")
        assertContains(firstRequest[2].content, "\"workflow_defaults\":[\"wait for confirmation\"]")
        assertContains(firstRequest[2].content, "\"work\":\"Mobile platform at Wire\"")
        assertFalse(firstRequest[2].content.contains("\"writing_style\":\"verbose style\""))
        assertFalse(firstRequest[2].content.contains("\"tooling_preferences\":[\"use grep\"]"))
    }

    @Test
    fun successfulTurnDistillsAndSavesProfileMemory() = runBlocking {
        val repository = ProfileMemoryControllerTestAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "assistant answer")),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "writing_style": "concise bullets",
                          "tooling_preferences": ["use rg", "prefer TypeScript"],
                          "workflow_defaults": ["always run tests before finalizing"],
                          "stable_constraints": ["avoid destructive git commands"],
                          "name": "Alex",
                          "work": "Mobile platform at Wire",
                          "profession": "Staff Engineer",
                          "other_facts": ["based in Berlin"]
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val profileStore = RecordingProfileMemoryStore()
        val environmentFacts = ProfileEnvironmentFacts(
            timezone = "Europe/Berlin",
            os = "MACOS",
            repoPath = "/repo/path",
        )
        val controller = createController(
            repository = repository,
            io = ProfileMemoryControllerTestCliIO(inputs = listOf("Implement this", "/exit")),
            profileMemoryStore = profileStore,
            profileMemoryDistillationUseCase = ProfileMemoryDistillationUseCase(SendPromptUseCase(repository)),
            profileEnvironmentFactsProvider = FixedProfileEnvironmentFactsProvider(environmentFacts),
        )

        controller.runInteractive()

        assertEquals(1, profileStore.saveStates.size)
        val savedState = profileStore.saveStates.single()
        assertEquals("concise bullets", savedState.preferences.writingStyle)
        assertEquals(listOf("use rg", "prefer TypeScript"), savedState.preferences.toolingPreferences)
        assertEquals(listOf("always run tests before finalizing"), savedState.preferences.workflowDefaults)
        assertEquals(listOf("avoid destructive git commands"), savedState.preferences.stableConstraints)
        assertEquals("Alex", savedState.preferences.name)
        assertEquals("Mobile platform at Wire", savedState.preferences.work)
        assertEquals("Staff Engineer", savedState.preferences.profession)
        assertEquals(listOf("based in Berlin"), savedState.preferences.otherFacts)
        assertEquals(environmentFacts, savedState.environmentFacts)
        assertEquals(2, repository.conversations.size)
        assertContains(repository.conversations[1][1].content, "USER: Implement this")
        assertFalse(repository.conversations[1][1].content.contains("ASSISTANT:"))
    }

    @Test
    fun distilledProfileStateIsSavedButUserDefinedOverridesRemainEffectiveInPrompt() = runBlocking {
        val repository = ProfileMemoryControllerTestAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "assistant one")),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "writing_style": "verbose distilled style",
                          "tooling_preferences": ["use grep"],
                          "workflow_defaults": [],
                          "stable_constraints": [],
                          "name": "",
                          "work": "",
                          "profession": "",
                          "other_facts": []
                        }
                        """.trimIndent(),
                    ),
                ),
                Result.success(AgentResponse(content = "assistant two")),
            ),
        )
        val profileStore = RecordingProfileMemoryStore()
        val userDefinedProfileStore = RecordingUserDefinedProfileStore(
            loadedState = ProfilePreferenceState(
                writingStyle = "concise bullets",
                toolingPreferences = listOf("use rg"),
            ),
        )
        val controller = createController(
            repository = repository,
            io = ProfileMemoryControllerTestCliIO(inputs = listOf("Prompt one", "Prompt two", "/exit")),
            profileMemoryStore = profileStore,
            profileMemoryDistillationUseCase = ProfileMemoryDistillationUseCase(SendPromptUseCase(repository)),
            userDefinedProfileStore = userDefinedProfileStore,
            profileEnvironmentFactsProvider = FixedProfileEnvironmentFactsProvider(
                ProfileEnvironmentFacts(
                    timezone = "Europe/Berlin",
                    os = "MACOS",
                    repoPath = "/repo/path",
                ),
            ),
        )

        controller.runInteractive()

        assertEquals(1, profileStore.saveStates.size)
        val savedDistilledState = profileStore.saveStates.single()
        assertEquals("verbose distilled style", savedDistilledState.preferences.writingStyle)
        assertEquals(listOf("use grep"), savedDistilledState.preferences.toolingPreferences)
        val secondMainRequest = repository.conversations[2]
        assertContains(secondMainRequest[2].content, "\"writing_style\":\"concise bullets\"")
        assertContains(secondMainRequest[2].content, "\"tooling_preferences\":[\"use rg\"]")
        assertFalse(secondMainRequest[2].content.contains("\"writing_style\":\"verbose distilled style\""))
    }

    @Test
    fun distillationFailureDoesNotFailTurn() = runBlocking {
        val repository = ProfileMemoryControllerTestAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "assistant answer")),
                Result.success(AgentResponse(content = "{ malformed json")),
            ),
        )
        val io = ProfileMemoryControllerTestCliIO(inputs = listOf("Prompt one", "/exit"))
        val profileStore = RecordingProfileMemoryStore()
        val controller = createController(
            repository = repository,
            io = io,
            profileMemoryStore = profileStore,
            profileMemoryDistillationUseCase = ProfileMemoryDistillationUseCase(SendPromptUseCase(repository)),
        )

        controller.runInteractive()

        assertEquals(0, profileStore.saveStates.size)
        assertTrue(io.outputText().contains("⏺ assistant answer"))
        assertFalse(io.outputText().contains("error>"))
    }

    @Test
    fun resetCommandDoesNotClearProfileMemory() = runBlocking {
        val repository = ProfileMemoryControllerTestAgentRepository(
            responses = listOf(
                Result.success(AgentResponse(content = "assistant answer")),
                Result.success(
                    AgentResponse(
                        content = """
                        {
                          "writing_style": "concise",
                          "tooling_preferences": [],
                          "workflow_defaults": [],
                          "stable_constraints": [],
                          "name": "",
                          "work": "",
                          "profession": "",
                          "other_facts": []
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val profileStore = RecordingProfileMemoryStore()
        val controller = createController(
            repository = repository,
            io = ProfileMemoryControllerTestCliIO(inputs = listOf("Prompt one", "/reset", "/exit")),
            profileMemoryStore = profileStore,
            profileMemoryDistillationUseCase = ProfileMemoryDistillationUseCase(SendPromptUseCase(repository)),
        )

        controller.runInteractive()

        assertEquals(0, profileStore.clearCalls)
        assertEquals(1, profileStore.saveStates.size)
    }

    @Test
    fun oneShotModeDoesNotLoadOrSaveProfileMemory() = runBlocking {
        val repository = ProfileMemoryControllerTestAgentRepository(
            responses = listOf(Result.success(AgentResponse(content = "one-shot answer"))),
        )
        val profileStore = RecordingProfileMemoryStore(
            loadedState = ProfileMemoryState(
                preferences = ProfilePreferenceState(writingStyle = "concise"),
                environmentFacts = ProfileEnvironmentFacts(
                    timezone = "Europe/Berlin",
                    os = "MACOS",
                    repoPath = "/repo/path",
                ),
            ),
        )
        val userDefinedProfileStore = RecordingUserDefinedProfileStore(
            loadedState = ProfilePreferenceState(
                writingStyle = "concise bullets",
                toolingPreferences = listOf("use rg"),
            ),
        )
        val controller = createController(
            repository = repository,
            io = ProfileMemoryControllerTestCliIO(inputs = emptyList()),
            profileMemoryStore = profileStore,
            profileMemoryDistillationUseCase = ProfileMemoryDistillationUseCase(SendPromptUseCase(repository)),
            userDefinedProfileStore = userDefinedProfileStore,
        )

        val exitCode = controller.runSinglePrompt("One-shot prompt")

        assertEquals(0, exitCode)
        assertEquals(0, profileStore.loadCalls)
        assertEquals(0, profileStore.saveStates.size)
        assertEquals(1, userDefinedProfileStore.loadCalls)
        assertEquals(1, repository.conversations.size)
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER),
            repository.conversations.single().map { it.role },
        )
        assertContains(
            repository.conversations.single().first().content,
            "User-defined profile defaults (highest priority):",
        )
        assertFalse(
            repository.conversations.single().any { message ->
                message.content.contains("Profile memory snapshot (persistent user defaults):")
            },
        )
    }

    private fun createController(
        repository: ProfileMemoryControllerTestAgentRepository,
        io: CliIO,
        profileMemoryStore: ProfileMemoryStore? = null,
        userDefinedProfileStore: UserDefinedProfileStore? = null,
        profileMemoryDistillationUseCase: ProfileMemoryDistillationUseCase? = null,
        profileEnvironmentFactsProvider: ProfileEnvironmentFactsProvider = FixedProfileEnvironmentFactsProvider(
            ProfileEnvironmentFacts(
                timezone = "unknown",
                os = "unknown",
                repoPath = "unknown",
            ),
        ),
        compactionCoordinators: Map<SessionCompactionMode, SessionMemoryCompactionCoordinator> = mapOf(
            SessionCompactionMode.ROLLING_SUMMARY to SessionMemoryCompactionCoordinator.disabled(),
        ),
    ): ConsoleChatController {
        return ConsoleChatController(
            sendPromptUseCase = SendPromptUseCase(repository),
            initialSystemPrompt = "Base system prompt",
            initialModel = "gpt-4.1-mini",
            models = listOf(
                ModelProperties(
                    id = "gpt-4.1-mini",
                    pricing = ModelPricing(
                        inputUsdPer1M = 0.40,
                        outputUsdPer1M = 1.60,
                    ),
                    contextWindowTokens = 1_047_576,
                ),
            ),
            io = io,
            profileMemoryStore = profileMemoryStore,
            userDefinedProfileStore = userDefinedProfileStore,
            profileMemoryDistillationUseCase = profileMemoryDistillationUseCase,
            profileEnvironmentFactsProvider = profileEnvironmentFactsProvider,
            compactionCoordinators = compactionCoordinators,
            defaultCompactionMode = SessionCompactionMode.ROLLING_SUMMARY,
        )
    }
}

private class ProfileMemoryControllerTestAgentRepository(
    responses: List<Result<AgentResponse>>,
) : AgentRepository {
    private val queuedResponses = ArrayDeque(responses)
    val conversations = mutableListOf<List<ConversationMessage>>()

    override suspend fun complete(
        prompt: PromptRequestData,
        temperature: Double?,
        model: String?,
    ): AgentResponse {
        conversations += prompt.toConversation()
        val response = queuedResponses.removeFirstOrNull()
            ?: error("No prepared response for conversation #${conversations.size}")
        return response.getOrThrow()
    }
}

private class RecordingProfileMemoryStore(
    private val loadedState: ProfileMemoryState? = null,
) : ProfileMemoryStore {
    var loadCalls: Int = 0
        private set
    var clearCalls: Int = 0
        private set
    val saveStates = mutableListOf<ProfileMemoryState>()

    override fun load(): ProfileMemoryState? {
        loadCalls += 1
        return loadedState?.copy(
            preferences = loadedState.preferences.copy(
                toolingPreferences = loadedState.preferences.toolingPreferences.toList(),
                workflowDefaults = loadedState.preferences.workflowDefaults.toList(),
                stableConstraints = loadedState.preferences.stableConstraints.toList(),
                otherFacts = loadedState.preferences.otherFacts.toList(),
            ),
            environmentFacts = loadedState.environmentFacts.copy(),
        )
    }

    override fun save(state: ProfileMemoryState) {
        saveStates += state.copy(
            preferences = state.preferences.copy(
                toolingPreferences = state.preferences.toolingPreferences.toList(),
                workflowDefaults = state.preferences.workflowDefaults.toList(),
                stableConstraints = state.preferences.stableConstraints.toList(),
                otherFacts = state.preferences.otherFacts.toList(),
            ),
            environmentFacts = state.environmentFacts.copy(),
        )
    }

    override fun clear() {
        clearCalls += 1
    }
}

private class RecordingUserDefinedProfileStore(
    private val loadedState: ProfilePreferenceState? = null,
) : UserDefinedProfileStore {
    var loadCalls: Int = 0
        private set

    override fun load(): ProfilePreferenceState? {
        loadCalls += 1
        return loadedState?.copy(
            toolingPreferences = loadedState.toolingPreferences.toList(),
            workflowDefaults = loadedState.workflowDefaults.toList(),
            stableConstraints = loadedState.stableConstraints.toList(),
            otherFacts = loadedState.otherFacts.toList(),
        )
    }

    override fun listProfiles(): List<UserProfileOption> = emptyList()

    override fun activeProfileFileName(): String? = null

    override fun setActiveProfile(fileName: String): Boolean = false
}

private class FixedProfileEnvironmentFactsProvider(
    private val environmentFacts: ProfileEnvironmentFacts,
) : ProfileEnvironmentFactsProvider() {
    override fun read(): ProfileEnvironmentFacts = environmentFacts
}

private class ProfileMemoryControllerTestCliIO(
    inputs: List<String>,
    private val compactionSelections: List<Int?> = emptyList(),
) : CliIO {
    private val queuedInputs = ArrayDeque<String?>(inputs)
    private var nextCompactionSelectionIndex = 0
    private val lines = mutableListOf<String>()

    override fun clearScreen() = Unit

    override fun hideCursor() = Unit

    override fun showCursor() = Unit

    override fun writeLine(text: String) {
        lines += text
    }

    override fun readLine(prompt: String): String? = queuedInputs.removeFirstOrNull()

    override fun readLineInFooter(prompt: String, divider: String): String? =
        queuedInputs.removeFirstOrNull()

    override fun openConfigMenu(
        tabs: List<String>,
        descriptions: List<String>,
        currentSelection: ConfigMenuSelection,
    ): ConfigMenuSelection = currentSelection

    override fun openCompactionMenu(options: List<String>, currentSelection: Int): Int? {
        val selection = compactionSelections.getOrNull(nextCompactionSelectionIndex)
        if (nextCompactionSelectionIndex < compactionSelections.size) {
            nextCompactionSelectionIndex += 1
            return selection
        }
        return currentSelection
    }

    override fun openProfileMenu(options: List<String>, currentSelection: Int): Int? = currentSelection

    fun outputText(): String = lines.joinToString(separator = "\n")
}
