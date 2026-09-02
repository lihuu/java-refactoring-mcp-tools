package com.example.airefactoring.refactoring.replaceinheritance

import com.intellij.openapi.project.Project

interface ReplaceInheritanceWithDelegationExecutor {
    suspend fun execute(
        project: Project,
        preparation: ReplaceInheritanceWithDelegationPreparation,
    ): ReplaceInheritanceWithDelegationExecutionResult
}

data class ReplaceInheritanceWithDelegationExecutionResult(
    val sourceClass: String,
    val affectedFiles: List<String>,
    val summary: String,
)
