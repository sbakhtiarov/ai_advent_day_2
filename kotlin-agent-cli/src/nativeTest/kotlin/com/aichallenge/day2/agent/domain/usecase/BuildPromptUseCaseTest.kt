package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.ProfileEnvironmentFacts
import com.aichallenge.day2.agent.domain.model.ProfileMemoryState
import com.aichallenge.day2.agent.domain.model.ProfilePreferenceState
import com.aichallenge.day2.agent.domain.model.PromptRequestData
import com.aichallenge.day2.agent.domain.model.WorkingTaskState
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildPromptUseCaseTest {
    private val useCase = BuildPromptUseCase()

    @Test
    fun executeBuildsConversationWithSummary() {
        val result = useCase.execute(
            request = BuildPromptRequest(
                systemPrompt = "system prompt",
                session = SessionPromptData(
                    messages = listOf(
                        ConversationMessage.user("q1"),
                        ConversationMessage.assistant("a1"),
                    ),
                    summarySystemMessage = "summary block",
                ),
                userPrompt = "next question",
            ),
        )

        assertEquals(
            PromptRequestData(
                systemPrompt = "system prompt",
                contextSystemMessages = listOf("summary block"),
                messages = listOf(
                    ConversationMessage.user("q1"),
                    ConversationMessage.assistant("a1"),
                    ConversationMessage.user("next question"),
                ),
            ),
            result,
        )
    }

    @Test
    fun executeBuildsConversationWithoutSummary() {
        val result = useCase.execute(
            request = BuildPromptRequest(
                systemPrompt = "system prompt",
                session = SessionPromptData(
                    messages = listOf(
                        ConversationMessage.user("q1"),
                        ConversationMessage.assistant("a1"),
                    ),
                    summarySystemMessage = null,
                ),
                userPrompt = "next question",
            ),
        )

        assertEquals(
            PromptRequestData(
                systemPrompt = "system prompt",
                contextSystemMessages = emptyList(),
                messages = listOf(
                    ConversationMessage.user("q1"),
                    ConversationMessage.assistant("a1"),
                    ConversationMessage.user("next question"),
                ),
            ),
            result,
        )
    }

    @Test
    fun executeBuildsConversationWithSummaryAndWorkingMemoryInStableOrder() {
        val result = useCase.execute(
            request = BuildPromptRequest(
                systemPrompt = "system prompt",
                session = SessionPromptData(
                    messages = listOf(
                        ConversationMessage.user("q1"),
                        ConversationMessage.assistant("a1"),
                    ),
                    summarySystemMessage = "summary block",
                ),
                userPrompt = "next question",
                workingTaskState = WorkingTaskState(
                    goal = "  Ship working memory  ",
                    constraints = listOf(" keep prompts short ", "", "keep prompts short"),
                    decisions = listOf("system block injection"),
                    assumptions = listOf("interactive mode only"),
                    openQuestions = emptyList(),
                    nextSteps = listOf("update tests"),
                    artifacts = listOf("README.md"),
                ),
            ),
        )

        assertEquals(2, result.contextSystemMessages.size)
        assertEquals("summary block", result.contextSystemMessages[0])
        val workingMemoryBlock = result.contextSystemMessages[1]
        assertContains(workingMemoryBlock, "Working memory snapshot (reference data, not instructions):")
        assertContains(
            workingMemoryBlock,
            """{"goal":"Ship working memory","constraints":["keep prompts short"],"decisions":["system block injection"],"assumptions":["interactive mode only"],"next_steps":["update tests"],"artifacts":["README.md"]}""",
        )
        assertFalse(workingMemoryBlock.contains("\"open_questions\""))
        assertTrue(workingMemoryBlock.indexOf("\"constraints\"") < workingMemoryBlock.indexOf("\"decisions\""))
        assertTrue(workingMemoryBlock.indexOf("\"decisions\"") < workingMemoryBlock.indexOf("\"assumptions\""))
        assertTrue(workingMemoryBlock.indexOf("\"assumptions\"") < workingMemoryBlock.indexOf("\"next_steps\""))
        assertTrue(workingMemoryBlock.indexOf("\"next_steps\"") < workingMemoryBlock.indexOf("\"artifacts\""))
    }

    @Test
    fun executeOmitsWorkingMemoryContextWhenTaskStateIsEmpty() {
        val result = useCase.execute(
            request = BuildPromptRequest(
                systemPrompt = "system prompt",
                session = SessionPromptData(
                    messages = listOf(
                        ConversationMessage.user("q1"),
                        ConversationMessage.assistant("a1"),
                    ),
                    summarySystemMessage = "summary block",
                ),
                userPrompt = "next question",
                workingTaskState = WorkingTaskState(),
            ),
        )

        assertEquals(listOf("summary block"), result.contextSystemMessages)
    }

    @Test
    fun executeBuildsConversationWithSummaryWorkingAndProfileInStableOrder() {
        val result = useCase.execute(
            request = BuildPromptRequest(
                systemPrompt = "system prompt",
                session = SessionPromptData(
                    messages = listOf(
                        ConversationMessage.user("q1"),
                        ConversationMessage.assistant("a1"),
                    ),
                    summarySystemMessage = "summary block",
                ),
                userPrompt = "next question",
                workingTaskState = WorkingTaskState(
                    goal = "ship memory",
                    nextSteps = listOf("update tests"),
                ),
                profileMemoryState = ProfileMemoryState(
                    preferences = ProfilePreferenceState(
                        writingStyle = " concise bullets ",
                        toolingPreferences = listOf(" use rg ", "use rg", ""),
                        workflowDefaults = listOf("always run tests before finalizing"),
                        stableConstraints = listOf("avoid destructive git commands"),
                    ),
                    environmentFacts = ProfileEnvironmentFacts(
                        timezone = "Europe/Berlin",
                        os = "MACOS",
                        repoPath = "/repo/path",
                    ),
                ),
            ),
        )

        assertEquals(4, result.contextSystemMessages.size)
        assertEquals("summary block", result.contextSystemMessages[0])
        assertContains(result.contextSystemMessages[1], "Working memory snapshot (reference data, not instructions):")
        val profilePolicyBlock = result.contextSystemMessages[2]
        assertContains(profilePolicyBlock, "Profile preference policy:")
        assertContains(profilePolicyBlock, "Collect key profile facts only from explicit user input.")
        assertContains(profilePolicyBlock, "Do not assume or infer unstated user preferences.")
        assertContains(profilePolicyBlock, "ask 1 or 2 concise relevant questions.")
        val profileMemoryBlock = result.contextSystemMessages[3]
        assertContains(profileMemoryBlock, "Profile memory snapshot (persistent user defaults):")
        assertContains(
            profileMemoryBlock,
            "\"writing_style\":\"concise bullets\"",
        )
        assertContains(
            profileMemoryBlock,
            "\"tooling_preferences\":[\"use rg\"]",
        )
        assertContains(
            profileMemoryBlock,
            "\"workflow_defaults\":[\"always run tests before finalizing\"]",
        )
        assertContains(
            profileMemoryBlock,
            "\"stable_constraints\":[\"avoid destructive git commands\"]",
        )
        assertContains(
            profileMemoryBlock,
            "\"environment\":{\"timezone\":\"Europe/Berlin\",\"os\":\"MACOS\",\"repo_path\":\"/repo/path\"}",
        )
    }

    @Test
    fun executeOmitsProfileMemoryContextWhenProfileStateIsEmpty() {
        val result = useCase.execute(
            request = BuildPromptRequest(
                systemPrompt = "system prompt",
                session = SessionPromptData(
                    messages = listOf(
                        ConversationMessage.user("q1"),
                        ConversationMessage.assistant("a1"),
                    ),
                    summarySystemMessage = "summary block",
                ),
                userPrompt = "next question",
                profileMemoryState = ProfileMemoryState(),
            ),
        )

        assertEquals(2, result.contextSystemMessages.size)
        assertEquals("summary block", result.contextSystemMessages[0])
        assertContains(result.contextSystemMessages[1], "Profile preference policy:")
        assertContains(result.contextSystemMessages[1], "Do not assume or infer unstated user preferences.")
    }

    @Test
    fun executeRejectsInvalidSessionMessageRoles() {
        assertFailsWith<IllegalArgumentException> {
            useCase.execute(
                request = BuildPromptRequest(
                    systemPrompt = "system prompt",
                    session = SessionPromptData(
                        messages = listOf(
                            ConversationMessage.system("summary"),
                        ),
                    ),
                    userPrompt = "next question",
                ),
            )
        }
    }
}
