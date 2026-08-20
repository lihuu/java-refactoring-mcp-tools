package com.example.airefactoring.refactoring.converttoinstancemethod

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.SmartPsiElementPointer

enum class ConvertToInstanceMethodTargetKind { PARAMETER, CONTAINING_CLASS }

data class ConvertToInstanceMethodPreparation(
    val methodPointer: SmartPsiElementPointer<PsiMethod>,
    val targetParameterPointer: SmartPsiElementPointer<PsiParameter>?,
    val targetClassPointer: SmartPsiElementPointer<PsiClass>,
    val targetKind: ConvertToInstanceMethodTargetKind,
    val pathInProject: String,
    val methodName: String,
    val targetDescription: String,
    val targetClassQualifiedName: String,
    val newVisibility: String?,
    val confirmInterfaceImplementations: Boolean,
    val methodTextSnapshot: String,
    val methodOwnerQualifiedNameSnapshot: String?,
    val targetParameterTextSnapshot: String?,
    val targetParameterTypeSnapshot: String?,
    val targetClassQualifiedNameSnapshot: String,
)

sealed interface ConvertToInstanceMethodSelectionResolution {
    data class Success(
        val preparation: ConvertToInstanceMethodPreparation,
    ) : ConvertToInstanceMethodSelectionResolution

    data class Failure(
        val code: McpRefactoringErrorCode,
        val message: String,
    ) : ConvertToInstanceMethodSelectionResolution
}
