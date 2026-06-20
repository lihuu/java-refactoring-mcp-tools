package com.example.airefactoring.refactoring

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile

/**
 * Ordered collection of [RefactoringHandler]s with first-applicable-wins [resolve].
 *
 * RESERVED: this is not used by the manual per-refactoring actions (each of those binds exactly
 * one handler via [com.example.airefactoring.action.AbstractAiRefactorAction]). It is kept for a
 * future auto-analysis / dispatch entry point that would pick a handler automatically from the
 * caret context.
 */
class RefactoringRegistry(private val handlers: List<RefactoringHandler>) {

    /** All registered handlers, in priority order. */
    fun all(): List<RefactoringHandler> = handlers

    /**
     * Return the first handler whose [RefactoringHandler.resolve] matches at [caretOffset],
     * paired with the target it produced; or null if none apply.
     */
    fun resolve(file: PsiFile, editor: Editor, caretOffset: Int): Pair<RefactoringHandler, RefactorTarget>? {
        for (handler in handlers) {
            val target = handler.resolve(file, editor, caretOffset)
            if (target != null) return handler to target
        }
        return null
    }
}
