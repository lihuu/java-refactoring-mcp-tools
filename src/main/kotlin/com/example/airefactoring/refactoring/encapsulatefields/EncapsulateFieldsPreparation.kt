package com.example.airefactoring.refactoring.encapsulatefields

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.SmartPsiElementPointer

data class EncapsulateFieldsPreparation(
    val fieldPointers: List<SmartPsiElementPointer<PsiField>>,
    val containingClassPointer: SmartPsiElementPointer<PsiClass>,
    val fieldTextSnapshots: List<String>,
    val fieldTypeSnapshots: List<String>,
    val containingClassQualifiedNameSnapshot: String,
    val containingClassTextSnapshot: String,
    val pathInProject: String,
    val fieldNames: List<String>,
    val getterNames: List<String>,
    val setterNames: List<String>,
    val fieldsVisibility: String?,
    val accessorsVisibility: String,
    val encapsulateGet: Boolean,
    val encapsulateSet: Boolean,
    val useAccessorsWhenAccessible: Boolean,
)

sealed interface EncapsulateFieldsSelectionResolution {
    data class Success(
        val preparation: EncapsulateFieldsPreparation,
    ) : EncapsulateFieldsSelectionResolution

    data class Failure(
        val code: McpRefactoringErrorCode,
        val message: String,
    ) : EncapsulateFieldsSelectionResolution
}

internal class EncapsulateFieldsPreparationException(message: String) : IllegalStateException(message)
internal class EncapsulateFieldsConflictException(message: String) : IllegalStateException(message)
