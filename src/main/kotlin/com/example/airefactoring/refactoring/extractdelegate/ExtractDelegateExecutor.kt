package com.example.airefactoring.refactoring.extractdelegate

import com.intellij.openapi.project.Project

interface ExtractDelegateExecutor {
    suspend fun extract(
        project: Project,
        preparation: ExtractDelegatePreparation,
    ): ExtractDelegateExecutionResult
}