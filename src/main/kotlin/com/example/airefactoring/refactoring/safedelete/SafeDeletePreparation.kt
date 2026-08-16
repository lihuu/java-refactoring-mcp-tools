package com.example.airefactoring.refactoring.safedelete

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.intellij.model.Pointer
import com.intellij.refactoring.safeDelete.api.SafeDeleteTarget

/**
 * A successfully resolved safe-delete target. The only PSI carried across the read/EDT boundary is
 * held through the native [SafeDeleteTarget] pointer, which the executor dereferences later.
 */
data class SafeDeletePreparation(
    val targetPointer: Pointer<out SafeDeleteTarget>,
    val sourceDocumentPath: String,
    val targetDescription: String,
)

sealed interface SafeDeleteTargetResolution {
    data class Success(
        val preparation: SafeDeletePreparation,
    ) : SafeDeleteTargetResolution

    data class Failure(
        val code: McpRefactoringErrorCode,
        val message: String,
    ) : SafeDeleteTargetResolution
}
