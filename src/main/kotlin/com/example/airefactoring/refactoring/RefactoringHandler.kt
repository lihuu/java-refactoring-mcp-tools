package com.example.airefactoring.refactoring

import com.example.airefactoring.settings.AiRefactoringSettings
import com.example.airefactoring.validator.ValidationResult
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import kotlinx.serialization.json.JsonObject

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

    /** Shown when resolve() returns null — tells the user how to position the caret/selection for this refactoring. */
    val notApplicableMessage: String

    /**
     * Decide whether this handler applies at [caretOffset] in [file], and if so build the target.
     * The [editor] is provided so a handler can read a selection (e.g. extract method); rename
     * ignores it. Return null to mean "I don't apply here" (the orchestrator tries the next handler).
     */
    fun resolve(file: PsiFile, editor: Editor, caretOffset: Int): RefactorTarget?

    /** This handler's prompt fragment + JSON shape, specialized for [target] if needed. */
    fun promptContribution(target: RefactorTarget): PromptContribution

    /**
     * Decode the LLM's action object into an operation. [actionJson] is the entire top-level
     * JSON object the LLM returned, whose `action` field equals this handler's [id] (not a
     * nested slice). Throw [RefactorParseException] on malformed/unsupported input.
     * The shared "no_action" shape is handled by the orchestrator, not here.
     */
    fun parse(actionJson: JsonObject): RefactorOperation

    /** Validate the operation against the target before executing. */
    fun validate(operation: RefactorOperation, target: RefactorTarget, project: Project): ValidationResult

    /**
     * Apply the operation and return a short success summary for the user notification.
     * Must perform its own threading/write-action handling.
     */
    fun execute(operation: RefactorOperation, target: RefactorTarget, project: Project, settings: AiRefactoringSettings.State): String
}
