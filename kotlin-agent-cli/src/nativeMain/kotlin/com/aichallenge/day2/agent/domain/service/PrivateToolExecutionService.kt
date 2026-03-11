package com.aichallenge.day2.agent.domain.service

import com.aichallenge.day2.agent.domain.model.PrivateToolBinding
import com.aichallenge.day2.agent.domain.model.PrivateToolResult
import kotlinx.serialization.json.JsonObject

interface PrivateToolExecutionService {
    suspend fun execute(binding: PrivateToolBinding, arguments: JsonObject): PrivateToolResult
}
