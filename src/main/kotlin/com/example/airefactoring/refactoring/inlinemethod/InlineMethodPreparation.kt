package com.example.airefactoring.refactoring.inlinemethod

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.SmartPsiElementPointer

data class InlineMethodUsageSnapshot(
    val filePath: String,
    val startOffset: Int,
)

data class InlineMethodPreparation(
    val methodPointer: SmartPsiElementPointer<PsiMethod>,
    val usagePointers: List<SmartPsiElementPointer<PsiReferenceExpression>>,
    val sourceVirtualFile: VirtualFile,
    val affectedVirtualFiles: Set<VirtualFile>,
    val pathInProject: String,
    val methodName: String,
    val methodTextSnapshot: String,
    val ownerQualifiedNameSnapshot: String?,
    val usageOffsetsSnapshot: List<Int>,
    val usageSnapshots: List<InlineMethodUsageSnapshot>,
)

sealed interface InlineMethodSelectionResolution {
    data class Success(val preparation: InlineMethodPreparation) : InlineMethodSelectionResolution

    data class Failure(
        val code: McpRefactoringErrorCode,
        val message: String,
    ) : InlineMethodSelectionResolution
}
