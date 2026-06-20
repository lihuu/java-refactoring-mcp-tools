package com.example.airefactoring.refactoring

import com.intellij.psi.PsiElement

/**
 * A resolved location a [RefactoringHandler] can act on. Produced by [RefactoringHandler.resolve].
 * Carries the PSI element to refactor plus the bounded context that will be sent to the LLM.
 */
data class RefactorTarget(
    val element: PsiElement,
    val handlerId: String,
    val displayName: String,
    val context: RefactorContextData,
)
