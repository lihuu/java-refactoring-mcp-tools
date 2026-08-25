package com.example.airefactoring.refactoring.inlinemethod

import com.intellij.openapi.project.Project

interface InlineMethodExecutor {
    suspend fun inline(project: Project, preparation: InlineMethodPreparation): InlineMethodExecutionResult
}

data class InlineMethodExecutionResult(
    val methodName: String,
    val inlinedOccurrenceCount: Int,
    val affectedFiles: List<String>,
    val summary: String,
)

class InlineMethodPreparationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class InlineMethodConflictException(message: String) : RuntimeException(message)
