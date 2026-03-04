package com.aichallenge.day2.agent.domain.repository

import com.aichallenge.day2.agent.domain.model.UserWorkflowDefinition
import com.aichallenge.day2.agent.domain.model.UserWorkflowOption

interface UserDefinedWorkflowStore {
    fun listWorkflows(): List<UserWorkflowOption>
    fun loadActiveWorkflow(): UserWorkflowDefinition?
    fun activeWorkflowFileName(): String?
    fun setActiveWorkflow(fileName: String): Boolean
}
