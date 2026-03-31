package com.aichallenge.day2.agent.domain.service

import com.aichallenge.day2.agent.domain.model.RagRetrievedChunk

fun interface WireAppRagRetriever {
    suspend fun retrieve(question: String): List<RagRetrievedChunk>
}
