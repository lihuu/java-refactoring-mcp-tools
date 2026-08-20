package com.example.airefactoring.refactoring.pushdown

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMember
import com.intellij.psi.SmartPsiElementPointer

data class PushMembersDownPreparation(
    val sourceSuperclassPointer: SmartPsiElementPointer<PsiClass>,
    val targetSubclassPointers: List<SmartPsiElementPointer<PsiClass>>,
    val memberPointers: List<SmartPsiElementPointer<PsiMember>>,
    val sourceQualifiedNameSnapshot: String,
    val targetSubclassFqnsSnapshot: List<String>,
    val memberNameSnapshots: List<String>,
    val memberSignatureSnapshots: List<String>,
    val pathInProject: String,
    val targetSubclassFqns: List<String>,
)

sealed interface PushMembersDownSelectionResolution {
    data class Success(val preparation: PushMembersDownPreparation) : PushMembersDownSelectionResolution
    data class Failure(val code: McpRefactoringErrorCode, val message: String) : PushMembersDownSelectionResolution
}

internal class PushMembersDownPreparationException(message: String) : IllegalStateException(message)
internal class PushMembersDownConflictException(message: String) : IllegalStateException(message)
