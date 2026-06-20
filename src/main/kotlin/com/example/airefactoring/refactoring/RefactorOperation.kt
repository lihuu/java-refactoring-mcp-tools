package com.example.airefactoring.refactoring

/**
 * A parsed refactoring instruction returned by the LLM and decoded by a [RefactoringHandler].
 * Each handler defines its own implementation(s). [reason] is the LLM's short rationale, if any.
 */
interface RefactorOperation {
    val reason: String?
}
