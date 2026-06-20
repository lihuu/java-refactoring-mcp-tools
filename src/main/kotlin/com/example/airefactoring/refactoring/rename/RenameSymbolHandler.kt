package com.example.airefactoring.refactoring.rename

import com.example.airefactoring.context.ContextCollector
import com.example.airefactoring.context.RefactorContext
import com.example.airefactoring.refactor.IntellijRenameExecutor
import com.example.airefactoring.refactor.RenameExecutor
import com.example.airefactoring.refactoring.PromptContribution
import com.example.airefactoring.refactoring.RefactorOperation
import com.example.airefactoring.refactoring.RefactorParseException
import com.example.airefactoring.refactoring.RefactorTarget
import com.example.airefactoring.refactoring.RefactoringHandler
import com.example.airefactoring.refactoring.stringField
import com.example.airefactoring.resolver.ResolvedSymbol
import com.example.airefactoring.resolver.SymbolResolver
import com.example.airefactoring.settings.AiRefactoringSettings
import com.example.airefactoring.validator.NameValidator
import com.example.airefactoring.validator.ValidationResult
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import kotlinx.serialization.json.JsonObject

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
                context = collector.collect(file, resolved.element, resolved.kind),
            )
            is ResolvedSymbol.Unsupported,
            ResolvedSymbol.NotFound,
            ResolvedSymbol.NotJava -> null
        }
    }

    override fun promptContribution(target: RefactorTarget): PromptContribution {
        val symbolName = (target.context as RefactorContext).symbolName
        return PromptContribution(
            systemFragment = NAMING_RULES,
            jsonShapeExample = JSON_SHAPE_EXAMPLE,
            question = "Should the symbol \"$symbolName\" be renamed?",
        )
    }

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
        val named = target.element as? PsiNamedElement
            ?: error("rename_symbol requires a named element, got ${target.element::class.simpleName}")
        val currentName = named.name ?: ""
        val kind = (target.context as RefactorContext).symbolKind
        return validator.validate(op.newName, kind, currentName, project)
    }

    override fun execute(
        operation: RefactorOperation,
        target: RefactorTarget,
        project: Project,
        settings: AiRefactoringSettings.State,
    ): String {
        val op = operation as? RenameOperation
            ?: error("execute called with non-RenameOperation: ${operation::class.simpleName}")
        val named = target.element as? PsiNamedElement
            ?: error("rename_symbol requires a named element, got ${target.element::class.simpleName}")
        // Capture the current name BEFORE the rename, since the element's name changes after.
        val currentName = named.name ?: ""
        executorFactory().rename(project, named, op.newName, settings.enablePreview)
        return "Renamed '$currentName' to '${op.newName}'."
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
