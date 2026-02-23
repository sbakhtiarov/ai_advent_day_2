package com.aichallenge.day2.agent.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionMemoryTest {
    @Test
    fun initializesWithSystemMessageOnly() {
        val memory = SessionMemory(initialSystemPrompt = "system one")

        assertEquals(
            listOf(ConversationMessage.system("system one")),
            memory.snapshot(),
        )
    }

    @Test
    fun conversationForAppendsUserPromptWithoutMutatingStoredHistory() {
        val memory = SessionMemory(initialSystemPrompt = "system one")
        memory.recordSuccessfulTurn(prompt = "hello", response = "hi")

        val conversation = memory.conversationFor("what now?")

        assertEquals(
            listOf(
                ConversationMessage.system("system one"),
                ConversationMessage.user("hello"),
                ConversationMessage.assistant("hi"),
                ConversationMessage.user("what now?"),
            ),
            conversation,
        )
        assertEquals(
            listOf(
                ConversationMessage.system("system one"),
                ConversationMessage.user("hello"),
                ConversationMessage.assistant("hi"),
            ),
            memory.snapshot(),
        )
    }

    @Test
    fun recordSuccessfulTurnStoresUserThenAssistant() {
        val memory = SessionMemory(initialSystemPrompt = "system one")

        memory.recordSuccessfulTurn(prompt = "question", response = "answer")

        assertEquals(
            listOf(
                ConversationMessage.system("system one"),
                ConversationMessage.user("question"),
                ConversationMessage.assistant("answer"),
            ),
            memory.snapshot(),
        )
    }

    @Test
    fun resetClearsPriorTurnsAndKeepsOnlyNewSystemMessage() {
        val memory = SessionMemory(initialSystemPrompt = "system one")
        memory.recordSuccessfulTurn(prompt = "q1", response = "a1")

        memory.reset("system two")

        assertEquals(
            listOf(ConversationMessage.system("system two")),
            memory.snapshot(),
        )
    }
}
