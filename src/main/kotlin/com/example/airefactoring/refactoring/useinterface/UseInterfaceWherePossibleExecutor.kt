package com.example.airefactoring.refactoring.useinterface

import com.intellij.openapi.project.Project

interface UseInterfaceWherePossibleExecutor {
    suspend fun useInterface(
        project: Project,
        preparation: UseInterfaceWherePossiblePreparation,
    ): UseInterfaceWherePossibleExecutionResult
}

data class UseInterfaceWherePossibleExecutionResult(
    val sourceClassQualifiedName: String,
    val targetInterfaceFqn: String,
    val nativeUsageCount: Int,
    val affectedFiles: List<String>?,
    val renamedVariables: List<String>?,
    val summary: String,
)
