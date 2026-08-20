package com.example.airefactoring.refactoring.pullup

import com.intellij.openapi.project.Project

interface PullMembersUpExecutor {
    suspend fun pull(
        project: Project,
        preparation: PullMembersUpPreparation,
    ): PullMembersUpExecutionResult
}

data class PullMembersUpExecutionResult(
    val sourceClassQualifiedName: String,
    val targetSuperclassFqn: String,
    val memberNames: List<String>,
    val nativeUsageCount: Int,
    val affectedFiles: List<String>?,
    val summary: String,
)
