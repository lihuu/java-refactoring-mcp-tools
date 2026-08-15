package com.example.airefactoring.refactoring.introduceparameter

import com.intellij.openapi.project.Project

/**
 * The outcome of one successfully executed native Introduce Parameter refactoring, as understood
 * by the MCP-facing layer. [parameterPosition] is 1-based; the new parameter is always appended
 * after every existing parameter.
 */
data class IntroduceParameterExecutionResult(
    val methodName: String,
    val parameterName: String,
    val parameterType: String,
    val parameterPosition: Int,
    val sourceKind: IntroduceParameterSourceKind,
    val updatedCallSiteCount: Int,
    val affectedFiles: List<String>,
    val summary: String,
)

/**
 * Executes a fully resolved introduce-parameter request by driving IntelliJ's native
 * [com.intellij.refactoring.introduceParameter.IntroduceParameterProcessor] headlessly. The
 * executor is the only mutator; it never patches text or rewrites files.
 */
interface IntroduceParameterExecutor {
    suspend fun introduceParameter(
        project: Project,
        selection: IntroduceParameterSelection,
        parameterName: String,
    ): IntroduceParameterExecutionResult
}

/** Thrown when the resolved source is no longer valid or the native processor refuses it. */
class IntroduceParameterPreparationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
