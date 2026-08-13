package com.example.airefactoring.refactoring.inlinevariable

import com.intellij.openapi.project.Project

data class InlineVariableExecutionResult(
    val variableName: String,
    val inlinedOccurrenceCount: Int,
    val summary: String,
)

interface InlineVariableExecutor {
    suspend fun inline(
        project: Project,
        selection: InlineVariableSelection,
    ): InlineVariableExecutionResult
}

class InlineVariablePreparationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class InlineVariableConflictException(
    message: String,
) : RuntimeException(message)
