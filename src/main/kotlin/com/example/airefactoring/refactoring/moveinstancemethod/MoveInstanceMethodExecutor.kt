package com.example.airefactoring.refactoring.moveinstancemethod

import com.intellij.openapi.project.Project

/**
 * Executes a fully resolved Move Instance Method request by driving IntelliJ's native
 * [com.intellij.refactoring.move.moveInstanceMethod.MoveInstanceMethodProcessor] headlessly. The
 * executor is the only mutator; it never patches text, rewrites files, or opens a dialog.
 */
interface MoveInstanceMethodExecutor {
    suspend fun move(project: Project, preparation: MoveInstanceMethodPreparation): MoveInstanceMethodExecutionResult
}

/**
 * The outcome of one successfully executed native Move Instance Method refactoring, as understood by
 * the MCP-facing layer. [updatedCallSiteCount] and [affectedFiles] are captured from the native
 * processor's own usage search before mutation; [affectedFiles] is null only when a complete native
 * usage-file inventory cannot be proven.
 */
data class MoveInstanceMethodExecutionResult(
    val methodName: String,
    val targetDescription: String,
    val targetClassQualifiedName: String,
    val newVisibility: String,
    val updatedCallSiteCount: Int,
    val affectedFiles: List<String>?,
    val summary: String,
)

/** Thrown when the native processor reports a conflict that cannot be resolved headlessly. */
class MoveInstanceMethodConflictException(message: String) : RuntimeException(message)

/** Thrown when the resolved method or target is stale against the preparation snapshot. */
class MoveInstanceMethodPreparationException(message: String) : RuntimeException(message)
