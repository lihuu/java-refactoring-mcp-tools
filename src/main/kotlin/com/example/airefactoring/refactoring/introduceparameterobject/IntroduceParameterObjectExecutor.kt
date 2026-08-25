package com.example.airefactoring.refactoring.introduceparameterobject

import com.intellij.openapi.project.Project

interface IntroduceParameterObjectExecutor {
    suspend fun introduce(
        project: Project,
        preparation: IntroduceParameterObjectPreparation,
    ): IntroduceParameterObjectExecutionResult
}
