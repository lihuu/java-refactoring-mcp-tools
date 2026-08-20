package com.example.airefactoring.refactoring.extractinterface

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMember
import com.intellij.psi.SmartPsiElementPointer

data class ExtractInterfacePreparation(
    val sourceClassPointer: SmartPsiElementPointer<PsiClass>,
    val memberPointers: List<SmartPsiElementPointer<PsiMember>>,
    val sourceClassQualifiedNameSnapshot: String,
    val sourceClassPackageSnapshot: String,
    val memberNameSnapshots: List<String>,
    val memberSignatureSnapshots: List<String>,
    val pathInProject: String,
    val interfaceName: String,
    val targetPackage: String?, // null means same as source
    val effectiveQualifiedNameSnapshot: String,
)

sealed interface ExtractInterfaceSelectionResolution {
    data class Success(
        val preparation: ExtractInterfacePreparation,
    ) : ExtractInterfaceSelectionResolution

    data class Failure(
        val code: McpRefactoringErrorCode,
        val message: String,
    ) : ExtractInterfaceSelectionResolution
}

internal class ExtractInterfacePreparationException(message: String) : IllegalStateException(message)
internal class ExtractInterfaceConflictException(message: String) : IllegalStateException(message)
