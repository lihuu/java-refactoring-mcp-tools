package com.example.airefactoring.refactoring.makestatic

import com.intellij.openapi.project.Project

/**
 * Executes a fully resolved Java Make Static request by driving IntelliJ's native
 * [com.intellij.refactoring.makeStatic.MakeMethodStaticProcessor] or
 * [com.intellij.refactoring.makeStatic.MakeClassStaticProcessor] headlessly. The executor is the only
 * mutator; it never patches text, rewrites files, or opens a dialog.
 */
interface JavaMakeStaticExecutor {
    suspend fun makeStatic(
        project: Project,
        preparation: JavaMakeStaticPreparation,
    ): JavaMakeStaticExecutionResult
}

/**
 * The outcome of one successfully executed native Java Make Static refactoring, as understood by the
 * MCP-facing layer. [nativeUsageCount] and [affectedFiles] are captured from the native processor's
 * own usage search before mutation; [affectedFiles] is null only when a complete project-relative
 * usage-file inventory cannot be proven.
 */
data class JavaMakeStaticExecutionResult(
    val memberName: String,
    val memberKind: JavaMakeStaticMemberKind,
    val replaceUsages: Boolean,
    val classParameterName: String?,
    val fieldParameterNames: List<String>,
    val generateDelegate: Boolean,
    val nativeUsageCount: Int,
    val affectedFiles: List<String>?,
    val summary: String,
)

/** Thrown when the native processor reports a conflict that cannot be resolved headlessly. */
class JavaMakeStaticConflictException(message: String) : RuntimeException(message)

/** Thrown when the resolved member or a selected field is stale against the preparation snapshot. */
class JavaMakeStaticPreparationException(message: String) : RuntimeException(message)
