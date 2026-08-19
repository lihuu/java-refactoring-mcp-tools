package com.example.airefactoring.refactoring.makestatic

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.intellij.psi.PsiField
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiTypeParameterListOwner
import com.intellij.psi.SmartPsiElementPointer

enum class JavaMakeStaticMemberKind { METHOD, CLASS }

/**
 * The resolver-to-executor handoff for one Java Make Static request. Carries smart pointers for the
 * target member and the explicitly selected fields, immutable text snapshots, the requested native
 * settings, and the resolved member facts. The executor de-references and verifies the pointers
 * against the snapshots immediately before the single native mutation.
 */
data class JavaMakeStaticPreparation(
    val memberPointer: SmartPsiElementPointer<out PsiTypeParameterListOwner>,
    val memberOwnerPointer: SmartPsiElementPointer<PsiClass>,
    val fieldPointers: List<SmartPsiElementPointer<PsiField>>,
    val memberTextSnapshot: String,
    val fieldTextSnapshots: List<String>,
    val fieldTypeSnapshots: List<String>,
    val pathInProject: String,
    val memberKind: JavaMakeStaticMemberKind,
    val memberName: String,
    val replaceUsages: Boolean,
    val classParameterName: String?,
    val fieldParameterNames: List<String>,
    val generateDelegate: Boolean,
)

sealed interface JavaMakeStaticSelectionResolution {
    data class Success(
        val preparation: JavaMakeStaticPreparation,
    ) : JavaMakeStaticSelectionResolution

    data class Failure(
        val code: McpRefactoringErrorCode,
        val message: String,
    ) : JavaMakeStaticSelectionResolution
}
