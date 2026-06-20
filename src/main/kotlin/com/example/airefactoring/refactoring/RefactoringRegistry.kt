package com.example.airefactoring.refactoring

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile

/**
 * Ordered collection of [RefactoringHandler]s. The orchestrator asks [resolve] for the first
 * handler that matches the caret. Default construction will (in a later task) contain the real
 * handlers; for now it accepts an injected list so it is unit-testable and so Task 3 can wire
 * the real handlers in.
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
