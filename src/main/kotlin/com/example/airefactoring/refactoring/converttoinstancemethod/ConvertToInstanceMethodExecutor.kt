package com.example.airefactoring.refactoring.converttoinstancemethod

import com.intellij.openapi.project.Project

interface ConvertToInstanceMethodExecutor {
    suspend fun convert(
        project: Project,
        preparation: ConvertToInstanceMethodPreparation,
    ): ConvertToInstanceMethodExecutionResult
}

data class ConvertToInstanceMethodExecutionResult(
    val methodName: String,
    val targetKind: String,
    val targetDescription: String,
    val targetClassQualifiedName: String,
    val newVisibility: String?,
    val nativeUsageCount: Int,
    val affectedFiles: List<String>?,
    val summary: String,
)

class ConvertToInstanceMethodConflictException(message: String) : RuntimeException(message)

class ConvertToInstanceMethodPreparationException(message: String) : RuntimeException(message)
