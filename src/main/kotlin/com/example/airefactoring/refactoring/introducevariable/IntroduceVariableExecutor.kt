package com.example.airefactoring.refactoring.introducevariable

import com.intellij.openapi.project.Project

data class IntroduceVariableExecutionResult(
    val actualVariableName: String,
    val variableType: String,
    val summary: String,
)

interface IntroduceVariableExecutor {
    suspend fun introduce(
        project: Project,
        selection: IntroduceVariableSelection,
        preferredVariableName: String,
    ): IntroduceVariableExecutionResult
}

class IntroduceVariablePreparationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
