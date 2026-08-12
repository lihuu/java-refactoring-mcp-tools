package com.example.airefactoring.refactoring.changesignature

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.intellij.psi.PsiMethod
import com.intellij.psi.SmartPsiElementPointer

data class ChangeSignaturePreparation(
    val methodPointer: SmartPsiElementPointer<PsiMethod>,
    val declarationFilePath: String,
    val methodName: String,
    val originalParameterListText: String,
    val parameterName: String,
    val parameterTypeText: String,
    val canonicalParameterType: String,
    val parameterPosition: Int,
    val defaultCallSiteExpression: String,
    val updatedCallSiteCount: Int,
    val affectedFiles: List<String>,
)

sealed class ChangeSignaturePreparationResolution {
    data class Success(
        val preparation: ChangeSignaturePreparation,
    ) : ChangeSignaturePreparationResolution()

    data class Failure(
        val code: McpRefactoringErrorCode,
        val message: String,
    ) : ChangeSignaturePreparationResolution()
}
