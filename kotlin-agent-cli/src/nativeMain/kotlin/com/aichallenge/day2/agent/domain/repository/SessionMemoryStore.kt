package com.aichallenge.day2.agent.domain.repository

import com.aichallenge.day2.agent.domain.model.ConversationMessage

interface SessionMemoryStore {
    fun load(): List<ConversationMessage>?

    fun save(messages: List<ConversationMessage>)

    fun clear()
}
