package com.aichallenge.day2.agent.domain.usecase

import com.aichallenge.day2.agent.domain.model.ConversationMessage
import com.aichallenge.day2.agent.domain.model.LlmToolCapabilities
import com.aichallenge.day2.agent.domain.model.ProfileEnvironmentFacts
import com.aichallenge.day2.agent.domain.model.ProfileMemoryState
import com.aichallenge.day2.agent.domain.model.ProfilePreferenceState
import com.aichallenge.day2.agent.domain.model.PrivateToolBinding
import com.aichallenge.day2.agent.domain.model.PrivateToolTarget
import com.aichallenge.day2.agent.domain.model.PromptRequestData
import com.aichallenge.day2.agent.domain.model.WorkingTaskState
import kotlinx.serialization.json.buildJsonObject
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
    fun executeStripsVolatileCurrentTimeFactsAndAddsSchedulerTimePolicy() {
        val result = useCase.execute(
            request = BuildPromptRequest(
                systemPrompt = "system prompt",
                session = SessionPromptData(
                    messages = listOf(
                        ConversationMessage.user("What time is it?"),
                        ConversationMessage.assistant("The current local time is 2026-03-12 00:34:14 Europe/Berlin."),
                    ),
                    summarySystemMessage = """
                        Conversation summary from previous compacted turns:
                        User requested the current time, which is 2026-03-12 00:34:14 Europe/Berlin. User scheduled a Berlin weather update for 01:06 Europe/Berlin time.
                    """.trimIndent(),
                ),
                userPrompt = "What is my current local time?",
                workingTaskState = WorkingTaskState(
                    goal = "Provide accurate current time and weather information upon request",
                    decisions = listOf(
                        "Scheduled Berlin weather update notification for 01:06 Europe/Berlin time",
                        "Provided current local time response: 2026-03-12 00:34:14 Europe/Berlin",
                    ),
                    artifacts = listOf(
                        "Current local time response: 2026-03-12 00:34:14 Europe/Berlin",
                        "Scheduled Berlin weather update notification for 01:06 Europe/Berlin time",
                    ),
                ),
                toolCapabilities = LlmToolCapabilities(
                    privateTools = listOf(
                        PrivateToolBinding(
                            modelToolName = "scheduler",
                            target = PrivateToolTarget.BuiltIn(toolId = "scheduler"),
                            parametersSchema = buildJsonObject {},
                        ),
                    ),
                ),
            ),
        )

        assertEquals(3, result.contextSystemMessages.size)
        assertContains(
            result.contextSystemMessages[0],
            "User scheduled a Berlin weather update for 01:06 Europe/Berlin time.",
        )
        assertFalse(result.contextSystemMessages[0].contains("00:34:14"))
        assertContains(
            result.contextSystemMessages[1],
            "\"decisions\":[\"Scheduled Berlin weather update notification for 01:06 Europe/Berlin time\"]",
        )
        assertContains(
            result.contextSystemMessages[1],
            "\"artifacts\":[\"Scheduled Berlin weather update notification for 01:06 Europe/Berlin time\"]",
        )
        assertFalse(result.contextSystemMessages[1].contains("00:34:14"))
        assertContains(result.contextSystemMessages[2], "Time handling policy:")
        assertContains(result.contextSystemMessages[2], "`scheduler` tool")
        assertContains(result.contextSystemMessages[2], "\"current_time\"")
        assertContains(result.contextSystemMessages[2], "\"delay\"")
        assertContains(result.contextSystemMessages[2], "delay_amount")
        assertContains(result.contextSystemMessages[2], "delay_unit")
    }

    @Test
    fun executeAddsSchedulerTimePolicyForLocalScheduleTimeWithoutTimezone() {
        val result = useCase.execute(
            request = BuildPromptRequest(
                systemPrompt = "system prompt",
                session = SessionPromptData(
                    messages = emptyList(),
                ),
                userPrompt = "Show me test notification at 07:55.",
                toolCapabilities = LlmToolCapabilities(
                    privateTools = listOf(
                        PrivateToolBinding(
                            modelToolName = "scheduler",
                            target = PrivateToolTarget.BuiltIn(toolId = "scheduler"),
                            parametersSchema = buildJsonObject {},
                        ),
                    ),
                ),
            ),
        )

        assertEquals(1, result.contextSystemMessages.size)
        assertContains(result.contextSystemMessages.single(), "Time handling policy:")
        assertContains(result.contextSystemMessages.single(), "at 07:55")
        assertContains(result.contextSystemMessages.single(), "Do not ask the user for timezone")
    }

    @Test
    fun executeAddsSchedulerTimePolicyForRelativeSchedulePrompt() {
        val result = useCase.execute(
            request = BuildPromptRequest(
                systemPrompt = "system prompt",
                session = SessionPromptData(
                    messages = emptyList(),
                ),
                userPrompt = "Schedule notification in 5 minutes.",
                toolCapabilities = LlmToolCapabilities(
                    privateTools = listOf(
                        PrivateToolBinding(
                            modelToolName = "scheduler",
                            target = PrivateToolTarget.BuiltIn(toolId = "scheduler"),
                            parametersSchema = buildJsonObject {},
                        ),
                    ),
                ),
            ),
        )

        assertEquals(1, result.contextSystemMessages.size)
        assertContains(result.contextSystemMessages.single(), "Time handling policy:")
        assertContains(result.contextSystemMessages.single(), "in 5 minutes")
        assertContains(result.contextSystemMessages.single(), "\"delay\"")
        assertContains(result.contextSystemMessages.single(), "delay_amount")
        assertContains(result.contextSystemMessages.single(), "delay_unit")
        assertContains(result.contextSystemMessages.single(), "omit `schedule_type`")
        assertContains(result.contextSystemMessages.single(), "never reject the request as \"already passed\"")
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
                        name = "  Alex ",
                        work = "Mobile platform at Wire",
                        profession = "Staff Engineer",
                        otherFacts = listOf("based in Berlin", "based in Berlin", ""),
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
            "\"name\":\"Alex\"",
        )
        assertContains(
            profileMemoryBlock,
            "\"work\":\"Mobile platform at Wire\"",
        )
        assertContains(
            profileMemoryBlock,
            "\"profession\":\"Staff Engineer\"",
        )
        assertContains(
            profileMemoryBlock,
            "\"other_facts\":[\"based in Berlin\"]",
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
