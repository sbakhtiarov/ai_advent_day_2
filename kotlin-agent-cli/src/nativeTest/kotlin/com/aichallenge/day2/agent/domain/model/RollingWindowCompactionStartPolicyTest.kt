package com.aichallenge.day2.agent.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class RollingWindowCompactionStartPolicyTest {
    @Test
    fun selectsFirstTenMessagesWhenThresholdReached() {
        val policy = RollingWindowCompactionStartPolicy(
            threshold = 12,
            compactCount = 10,
            keepCount = 2,
        )
        val messages = (1..12).map { index ->
            if (index % 2 == 1) {
                ConversationMessage.user("u$index")
            } else {
                ConversationMessage.assistant("a$index")
            }
        }

        val candidate = policy.select(messages)

        assertEquals(10, candidate?.compactedCount)
        assertEquals(messages.take(10), candidate?.messagesToCompact)
    }

    @Test
    fun returnsNullWhenThresholdNotReached() {
        val policy = RollingWindowCompactionStartPolicy(
            threshold = 12,
            compactCount = 10,
            keepCount = 2,
        )
        val messages = (1..11).map { index ->
            if (index % 2 == 1) {
                ConversationMessage.user("u$index")
            } else {
                ConversationMessage.assistant("a$index")
            }
        }

        assertEquals(null, policy.select(messages))
    }
}
