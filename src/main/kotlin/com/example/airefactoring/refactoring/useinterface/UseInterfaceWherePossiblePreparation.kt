package com.example.airefactoring.refactoring.useinterface

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.intellij.psi.PsiClass
import com.intellij.psi.SmartPsiElementPointer

data class UseInterfaceWherePossiblePreparation(
    val sourceClassPointer: SmartPsiElementPointer<PsiClass>,
    val targetInterfacePointer: SmartPsiElementPointer<PsiClass>,
    val sourceQualifiedNameSnapshot: String,
    val targetInterfaceFqnSnapshot: String,
    val pathInProject: String,
    val targetInterfaceFqn: String,
)

sealed interface UseInterfaceWherePossibleSelectionResolution {
    data class Success(val preparation: UseInterfaceWherePossiblePreparation) : UseInterfaceWherePossibleSelectionResolution
    data class Failure(val code: McpRefactoringErrorCode, val message: String) : UseInterfaceWherePossibleSelectionResolution
}

internal class UseInterfaceWherePossiblePreparationException(message: String) : IllegalStateException(message)
internal class UseInterfaceConflictException(message: String) : IllegalStateException(message)
