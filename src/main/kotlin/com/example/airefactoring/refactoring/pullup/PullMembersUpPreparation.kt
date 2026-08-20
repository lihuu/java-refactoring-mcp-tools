package com.example.airefactoring.refactoring.pullup

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMember
import com.intellij.psi.SmartPsiElementPointer

data class PullMembersUpPreparation(
    val sourceSubclassPointer: SmartPsiElementPointer<PsiClass>,
    val targetSuperclassPointer: SmartPsiElementPointer<PsiClass>,
    val memberPointers: List<SmartPsiElementPointer<PsiMember>>,
    val sourceQualifiedNameSnapshot: String,
    val targetSuperclassFqnSnapshot: String,
    val memberNameSnapshots: List<String>,
    val memberSignatureSnapshots: List<String>,
    val pathInProject: String,
    val targetSuperclassFqn: String,
)

sealed interface PullMembersUpSelectionResolution {
    data class Success(val preparation: PullMembersUpPreparation) : PullMembersUpSelectionResolution
    data class Failure(val code: McpRefactoringErrorCode, val message: String) : PullMembersUpSelectionResolution
}

internal class PullMembersUpPreparationException(message: String) : IllegalStateException(message)
internal class PullMembersUpConflictException(message: String) : IllegalStateException(message)
