package com.example.airefactoring.refactoring.introduceparameter

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType

/**
 * Classifies the exact selected Java source as either a single value-producing expression or a
 * readable local variable, both of which can back a new method parameter.
 */
enum class IntroduceParameterSourceKind {
    EXPRESSION,
    LOCAL_VARIABLE,
}

/**
 * A successfully resolved introduce-parameter source. Exactly one of [expression] and
 * [localVariable] is non-null depending on [sourceKind].
 */
data class IntroduceParameterSelection(
    val file: PsiJavaFile,
    val document: Document,
    val sourceKind: IntroduceParameterSourceKind,
    val method: PsiMethod,
    val sourceType: PsiType,
    val expression: PsiExpression?,
    val localVariable: PsiLocalVariable?,
    val affectedFiles: List<String>,
)

sealed interface IntroduceParameterSelectionResolution {
    data class Success(
        val selection: IntroduceParameterSelection,
    ) : IntroduceParameterSelectionResolution

    data class Failure(
        val code: McpRefactoringErrorCode,
        val message: String,
    ) : IntroduceParameterSelectionResolution
}
