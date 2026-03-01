package com.aichallenge.day2.agent.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SlidingWindowCompactionStartPolicyTest {
    @Test
    fun returnsNullWhenMessageCountIsWithinWindow() {
        val policy = SlidingWindowCompactionStartPolicy(maxMessages = 10)
        val messages = (1..10).map { index ->
            if (index % 2 == 1) {
                ConversationMessage.user("u$index")
            } else {
                ConversationMessage.assistant("a$index")
            }
        }

        assertEquals(null, policy.select(messages))
    }

    @Test
    fun compactsOldestMessagesToKeepLastTen() {
        val policy = SlidingWindowCompactionStartPolicy(maxMessages = 10)
        val messages = (1..12).map { index ->
            if (index % 2 == 1) {
                ConversationMessage.user("u$index")
            } else {
                ConversationMessage.assistant("a$index")
            }
        }

        val candidate = policy.select(messages)

        assertEquals(2, candidate?.compactedCount)
        assertEquals(messages.take(2), candidate?.messagesToCompact)
    }

    @Test
    fun roundsCompactedCountToEvenWhenOverflowIsOdd() {
        val policy = SlidingWindowCompactionStartPolicy(maxMessages = 10)
        val messages = (1..13).map { index ->
            if (index % 2 == 1) {
                ConversationMessage.user("u$index")
            } else {
                ConversationMessage.assistant("a$index")
            }
        }

        val candidate = policy.select(messages)

        assertEquals(4, candidate?.compactedCount)
        assertEquals(messages.take(4), candidate?.messagesToCompact)
    }
}
