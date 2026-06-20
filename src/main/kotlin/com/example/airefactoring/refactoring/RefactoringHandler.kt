package com.example.airefactoring.refactoring

import com.example.airefactoring.settings.AiRefactoringSettings
import com.example.airefactoring.validator.ValidationResult
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * The unit of extension. One implementation = one AI refactoring (rename symbol, extract
 * constant, …). The orchestrator picks the first handler whose [resolve] matches the caret,
 * builds a prompt from [promptContribution], sends it to the LLM, then [parse]s, [validate]s,
 * and [execute]s the result. Adding a refactoring means writing one of these and registering
 * it in [RefactoringRegistry] — no orchestrator changes.
 */
interface RefactoringHandler {
    /** Stable identifier. MUST equal the LLM "action" string this handler emits/consumes. */
    val id: String

    /** Human-readable label for notifications, e.g. "Rename symbol". */
    val displayName: String

    /**
     * Decide whether this handler applies at [caretOffset] in [file], and if so build the target.
     * Return null to mean "I don't apply here" (the orchestrator tries the next handler).
     */
    fun resolve(file: PsiFile, caretOffset: Int): RefactorTarget?

    /** This handler's prompt fragment + JSON shape, specialized for [target] if needed. */
    fun promptContribution(target: RefactorTarget): PromptContribution

    /**
     * Decode the action-specific JSON object the LLM returned into an operation.
     * [actionJson] is the already-parsed top-level JSON object's content for this handler.
     * Throw [com.example.airefactoring.refactoring.RefactorParseException] on malformed input.
     * The shared "no_action" shape is handled by the orchestrator, not here.
     */
    fun parse(actionJson: kotlinx.serialization.json.JsonObject): RefactorOperation

    /** Validate the operation against the target before executing. */
    fun validate(operation: RefactorOperation, target: RefactorTarget, project: Project): ValidationResult

    /** Apply the operation. Must perform its own threading/write-action handling. */
    fun execute(operation: RefactorOperation, target: RefactorTarget, project: Project, settings: AiRefactoringSettings.State)
}
