package com.example.airefactoring.refactoring.extractmethodobject

import com.intellij.openapi.project.Project

interface ExtractMethodObjectExecutor {
    suspend fun replace(
        project: Project,
        preparation: ExtractMethodObjectPreparation,
    ): ExtractMethodObjectExecutionResult
}
