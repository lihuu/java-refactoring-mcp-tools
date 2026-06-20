package com.example.airefactoring.refactoring.rename

import com.example.airefactoring.context.ContextCollector
import com.example.airefactoring.refactor.IntellijRenameExecutor
import com.example.airefactoring.refactor.RenameExecutor
import com.example.airefactoring.refactoring.PromptContribution
import com.example.airefactoring.refactoring.RefactorOperation
import com.example.airefactoring.refactoring.RefactorParseException
import com.example.airefactoring.refactoring.RefactorTarget
import com.example.airefactoring.refactoring.RefactoringHandler
import com.example.airefactoring.resolver.ResolvedSymbol
import com.example.airefactoring.resolver.SymbolResolver
import com.example.airefactoring.settings.AiRefactoringSettings
import com.example.airefactoring.validator.NameValidator
import com.example.airefactoring.validator.ValidationResult
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * First concrete [RefactoringHandler]: renames a Java local variable or field. Delegates resolution,
 * context collection, validation, and execution to the existing rename-specific stages — this handler
 * only adapts them to the [RefactoringHandler] abstraction.
 */
class RenameSymbolHandler(
    private val executorFactory: () -> RenameExecutor = ::IntellijRenameExecutor,
) : RefactoringHandler {
    private val resolver = SymbolResolver()
    private val collector = ContextCollector()
    private val validator = NameValidator()

    override val id = "rename_symbol"
    override val displayName = "symbol"

    override fun resolve(file: PsiFile, caretOffset: Int): RefactorTarget? {
        val resolved = resolver.resolve(file, caretOffset)
        return when (resolved) {
            is ResolvedSymbol.Resolved -> RefactorTarget(
                element = resolved.element,
                handlerId = id,
                displayName = displayName,
                context = collector.collect(file, resolved.element, resolved.kind),
            )
            is ResolvedSymbol.Unsupported,
            ResolvedSymbol.NotFound,
            ResolvedSymbol.NotJava -> null
        }
    }

    override fun promptContribution(target: RefactorTarget): PromptContribution =
        PromptContribution(
            systemFragment = NAMING_RULES,
            jsonShapeExample = JSON_SHAPE_EXAMPLE,
        )

    override fun parse(actionJson: JsonObject): RefactorOperation {
        val action = actionJson.stringField("action")
        if (action != id) {
            throw RefactorParseException("rename_symbol handler cannot parse action: $action")
        }
        val newName = actionJson.stringField("newName")
            ?: throw RefactorParseException("rename_symbol requires a string newName.")
        if (newName.isBlank()) throw RefactorParseException("newName must not be blank.")
        return RenameOperation(newName.trim(), actionJson.stringField("reason"))
    }

    override fun validate(operation: RefactorOperation, target: RefactorTarget, project: Project): ValidationResult {
        val op = operation as? RenameOperation
            ?: return ValidationResult.Invalid("Unsupported operation for rename_symbol.")
        val currentName = target.element.name ?: ""
        return validator.validate(op.newName, target.context.symbolKind, currentName, project)
    }

    override fun execute(
        operation: RefactorOperation,
        target: RefactorTarget,
        project: Project,
        settings: AiRefactoringSettings.State,
    ): String {
        val op = operation as? RenameOperation
            ?: error("execute called with non-RenameOperation: ${operation::class.simpleName}")
        // Capture the current name BEFORE the rename, since the element's name changes after.
        val currentName = target.element.name ?: ""
        executorFactory().rename(project, target.element, op.newName, settings.enablePreview)
        return "Renamed '$currentName' to '${op.newName}'."
    }

    /** Returns the field's string content, or null if missing or not a JSON string primitive. */
    private fun JsonObject.stringField(name: String): String? {
        val element: JsonElement = this[name] ?: return null
        val primitive = element as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.contentOrNull
    }

    companion object {
        const val JSON_SHAPE_EXAMPLE =
            """{"action":"rename_symbol","newName":"<identifier>","reason":"<short explanation>"}"""

        val NAMING_RULES = """
            Naming rules:
            - Local variables and fields use lowerCamelCase.
            - Classes use UpperCamelCase.
            - Methods use lowerCamelCase.
            - Never propose a Java keyword. Never propose the current name.

            Decide rename_symbol only when the new name is a clear improvement.
            When in doubt, return no_action.
        """.trimIndent()
    }
}
