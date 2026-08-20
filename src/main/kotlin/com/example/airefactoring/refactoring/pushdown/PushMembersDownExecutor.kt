package com.example.airefactoring.refactoring.pushdown

import com.intellij.openapi.project.Project

interface PushMembersDownExecutor {
    suspend fun push(
        project: Project,
        preparation: PushMembersDownPreparation,
    ): PushMembersDownExecutionResult
}

data class PushMembersDownExecutionResult(
    val sourceClassQualifiedName: String,
    val targetSubclassFqns: List<String>,
    val memberNames: List<String>,
    val nativeUsageCount: Int,
    val affectedFiles: List<String>?,
    val summary: String,
)
