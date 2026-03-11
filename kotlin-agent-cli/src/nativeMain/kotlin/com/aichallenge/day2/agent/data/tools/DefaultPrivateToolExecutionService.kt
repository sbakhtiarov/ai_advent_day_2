package com.aichallenge.day2.agent.data.tools

import com.aichallenge.day2.agent.domain.model.PrivateToolBinding
import com.aichallenge.day2.agent.domain.model.PrivateToolResult
import com.aichallenge.day2.agent.domain.model.PrivateToolTarget
import com.aichallenge.day2.agent.domain.service.McpRuntimeService
import com.aichallenge.day2.agent.domain.service.PrivateToolExecutionService
import kotlinx.serialization.json.JsonObject

class DefaultPrivateToolExecutionService(
    private val mcpRuntimeService: McpRuntimeService,
    private val builtInToolRegistry: BuiltInToolRegistry,
) : PrivateToolExecutionService {
    override suspend fun execute(binding: PrivateToolBinding, arguments: JsonObject): PrivateToolResult {
        return when (val target = binding.target) {
            is PrivateToolTarget.Mcp -> {
                val result = mcpRuntimeService.callTool(
                    server = target.server,
                    toolName = target.sourceToolName,
                    arguments = arguments,
                )
                PrivateToolResult(
                    isError = result.isError,
                    content = result.content,
                    structuredContent = result.structuredContent,
                    meta = result.meta,
                )
            }

            is PrivateToolTarget.BuiltIn -> builtInToolRegistry.execute(target.toolId, arguments)
        }
    }
}
