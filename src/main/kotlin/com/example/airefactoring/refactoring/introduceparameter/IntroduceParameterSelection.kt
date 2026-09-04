package com.example.airefactoring.refactoring.introduceparameter

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.SmartPsiElementPointer

/**
 * Classifies the exact selected Java source as either a single value-producing expression or a
 * readable local variable, both of which can back a new method parameter.
 */
enum class IntroduceParameterSourceKind {
    EXPRESSION,
    LOCAL_VARIABLE,
}

/**
 * An immutable snapshot of one affected document at resolver handoff time. It makes a source or
 * caller edit between resolution and the EDT-native mutation an explicit preparation failure.
 */
data class IntroduceParameterDocumentSnapshot(
    val path: String,
    val text: String,
    val wasUnsaved: Boolean,
)

/**
 * A successfully resolved introduce-parameter source. The only PSI carried across the read/EDT
 * boundary is held through smart pointers; exactly one source pointer is non-null according to
 * [sourceKind].
 */
data class IntroduceParameterSelection(
    val sourceKind: IntroduceParameterSourceKind,
    val methodPointer: SmartPsiElementPointer<PsiMethod>,
    val expressionPointer: SmartPsiElementPointer<PsiExpression>?,
    val localVariablePointer: SmartPsiElementPointer<PsiLocalVariable>?,
    val sourceTypeCanonicalText: String,
    val sourceDocumentPath: String,
    val methodSignature: String,
    val sourceText: String,
    val updatedCallSiteCount: Int,
    val affectedFiles: List<String>,
    val documentSnapshots: List<IntroduceParameterDocumentSnapshot>,
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
