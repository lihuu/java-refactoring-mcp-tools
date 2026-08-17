package com.example.airefactoring.refactoring.safedelete

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer

/**
 * A successfully resolved safe-delete target. The only PSI carried across the read/EDT boundary is
 * held through a [SmartPsiElementPointer] to the resolved declaration, which the executor
 * dereferences later.
 */
data class SafeDeletePreparation(
    val elementPointer: SmartPsiElementPointer<PsiElement>,
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
