package com.example.airefactoring.refactoring.changesignature

import com.intellij.openapi.project.Project

data class ChangeSignatureExecutionResult(
    val methodName: String,
    val declarationFilePath: String,
    val parameterName: String,
    val parameterType: String,
    val parameterPosition: Int,
    val defaultCallSiteExpression: String,
    val updatedCallSiteCount: Int,
    val affectedFiles: List<String>,
    val summary: String,
)

interface ChangeSignatureExecutor {
    suspend fun addParameter(
        project: Project,
        preparation: ChangeSignaturePreparation,
    ): ChangeSignatureExecutionResult
}

class ChangeSignaturePreparationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class ChangeSignatureConflictException(
    message: String,
) : RuntimeException(message)
