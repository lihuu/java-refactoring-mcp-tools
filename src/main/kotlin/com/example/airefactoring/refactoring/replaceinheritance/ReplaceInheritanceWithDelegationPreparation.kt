package com.example.airefactoring.refactoring.replaceinheritance

import com.intellij.psi.PsiClass
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.openapi.vfs.VirtualFile

/**
 * Data passed from the SelectionResolver to the Executor.
 */
data class ReplaceInheritanceWithDelegationPreparation(
    val classPointer: SmartPsiElementPointer<PsiClass>,
    val classTextSnapshot: String,
    val sourceClassFqn: String,
    val targetBaseClassFqn: String,
    val fieldName: String,
    val delegateOtherMembers: Boolean,
    val generateGetter: Boolean,
    val affectedVirtualFiles: Set<VirtualFile>,
)
