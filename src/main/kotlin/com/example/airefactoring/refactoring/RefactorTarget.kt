package com.example.airefactoring.refactoring

import com.example.airefactoring.context.RefactorContext
import com.intellij.psi.PsiNamedElement

/**
 * A resolved location a [RefactoringHandler] can act on. Produced by [RefactoringHandler.resolve].
 * Carries the PSI element to refactor plus the bounded context that will be sent to the LLM.
 */
data class RefactorTarget(
    val element: PsiNamedElement,
    val handlerId: String,
    val displayName: String,
    val context: RefactorContext,
)
