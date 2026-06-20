package com.example.airefactoring.refactoring

import com.intellij.psi.PsiElement

/**
 * A resolved location a [RefactoringHandler] can act on. Produced by [RefactoringHandler.resolve].
 * Carries the PSI element to refactor plus the bounded context that will be sent to the LLM.
 *
 * The owning handler's identity and label are not stored here — the registry always pairs a target
 * with the handler that produced it (see [RefactoringRegistry.resolve]), so read [RefactoringHandler.id]
 * / [RefactoringHandler.displayName] from that handler instead of duplicating them on every target.
 */
data class RefactorTarget(
    val element: PsiElement,
    val context: RefactorContextData,
)
