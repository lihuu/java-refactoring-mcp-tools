package com.example.airefactoring.refactoring.safedelete

import com.intellij.openapi.project.Project

/**
 * The outcome of one successfully executed native Safe Delete refactoring, as understood by the
 * MCP-facing layer. [nativeUsageCount] is the number of usages the native processor found before
 * deletion (zero for a conflict-free, unreferenced target).
 */
data class SafeDeleteExecutionResult(
    val targetDescription: String,
    val nativeUsageCount: Int,
    val summary: String,
)

/**
 * Executes a fully resolved safe-delete request by driving IntelliJ's native
 * [com.intellij.refactoring.safeDelete.SafeDeleteProcessor] headlessly. The executor is the only
 * mutator; it never patches text or rewrites files.
 */
interface SafeDeleteExecutor {
    suspend fun delete(project: Project, preparation: SafeDeletePreparation): SafeDeleteExecutionResult
}

/** Thrown when the resolved target is no longer valid or the native processor refuses it. */
class SafeDeletePreparationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Thrown when the native refactoring reports a conflict that cannot be resolved headlessly. */
class SafeDeleteConflictException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
