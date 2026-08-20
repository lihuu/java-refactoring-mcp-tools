package com.example.airefactoring.refactoring.extractsuperclass

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMember
import com.intellij.psi.SmartPsiElementPointer

data class ExtractSuperclassPreparation(
    val sourceClassPointer: SmartPsiElementPointer<PsiClass>,
    val memberPointers: List<SmartPsiElementPointer<PsiMember>>,
    val sourceClassQualifiedNameSnapshot: String,
    val sourceClassPackageSnapshot: String,
    val memberNameSnapshots: List<String>,
    val memberSignatureSnapshots: List<String>,
    val pathInProject: String,
    val superclassName: String,
    val targetPackage: String?, // null means same as source
    val effectiveQualifiedNameSnapshot: String,
)

sealed interface ExtractSuperclassSelectionResolution {
    data class Success(
        val preparation: ExtractSuperclassPreparation,
    ) : ExtractSuperclassSelectionResolution

    data class Failure(
        val code: McpRefactoringErrorCode,
        val message: String,
    ) : ExtractSuperclassSelectionResolution
}

internal class ExtractSuperclassPreparationException(message: String) : IllegalStateException(message)
internal class ExtractSuperclassConflictException(message: String) : IllegalStateException(message)
