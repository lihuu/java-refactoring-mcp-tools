package com.example.airefactoring.refactoring.moveinstancemethod

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiVariable
import com.intellij.psi.SmartPsiElementPointer

data class MoveInstanceMethodPreparation(
    val methodPointer: SmartPsiElementPointer<PsiMethod>,
    val targetPointer: SmartPsiElementPointer<PsiVariable>,
    val pathInProject: String,
    val methodName: String,
    val targetDescription: String,
    val targetClassQualifiedName: String,
    val newVisibility: String,
    val methodTextSnapshot: String,
    val targetTypeSnapshot: String,
)

sealed interface MoveInstanceMethodSelectionResolution {
    data class Success(
        val preparation: MoveInstanceMethodPreparation,
    ) : MoveInstanceMethodSelectionResolution

    data class Failure(
        val code: McpRefactoringErrorCode,
        val message: String,
    ) : MoveInstanceMethodSelectionResolution
}
