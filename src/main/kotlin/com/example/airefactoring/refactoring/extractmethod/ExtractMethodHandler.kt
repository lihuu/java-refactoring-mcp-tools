package com.example.airefactoring.refactoring.extractmethod

import com.example.airefactoring.refactoring.PromptContribution
import com.example.airefactoring.refactoring.RefactorOperation
import com.example.airefactoring.refactoring.RefactorParseException
import com.example.airefactoring.refactoring.RefactorTarget
import com.example.airefactoring.refactoring.RefactoringHandler
import com.example.airefactoring.refactoring.stringField
import com.example.airefactoring.resolver.SymbolKind
import com.example.airefactoring.settings.AiRefactoringSettings
import com.example.airefactoring.validator.NameValidator
import com.example.airefactoring.validator.ValidationResult
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiStatement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.extractMethod.ExtractMethodHandler as PlatformExtractMethodHandler
import kotlinx.serialization.json.JsonObject

/**
 * Second concrete [RefactoringHandler]: extracts a selected block (or the statement under the
 * caret) into a new Java method. Proves the abstraction works for a non-symbol refactoring — its
 * target is a selection, not a named element.
 */
class ExtractMethodHandler(
    private val executorFactory: () -> ExtractMethodExecutor = ::IntellijExtractMethodExecutor,
) : RefactoringHandler {
    private val validator = NameValidator()
    override val id = "extract_method"
    override val displayName = "code selection"

    override fun resolve(file: PsiFile, editor: Editor, caretOffset: Int): RefactorTarget? {
        if (file !is PsiJavaFile) return null
        val project = file.project
        val elements: Array<PsiElement> =
            if (editor.selectionModel.hasSelection()) {
                PlatformExtractMethodHandler.getElements(project, editor, file)
            } else {
                val leaf = file.findElementAt(caretOffset) ?: return null
                val stmt = PsiTreeUtil.getParentOfType(leaf, PsiStatement::class.java) ?: return null
                arrayOf<PsiElement>(stmt)
            }
        if (elements.isEmpty()) return null

        val first = elements.first()
        val enclosingClass = PsiTreeUtil.getParentOfType(first, PsiClass::class.java)?.name
        val enclosingMethod = PsiTreeUtil.getParentOfType(first, PsiMethod::class.java)?.name
        val selectedCode = elements.joinToString("\n") { it.text }
            .take(ExtractMethodContext.MAX_SELECTED_CODE_CHARS)
        val ctx = ExtractMethodContext(
            data = ExtractMethodContext.SerializableData(
                language = "java",
                filePath = file.virtualFile?.path ?: file.name,
                enclosingClass = enclosingClass,
                enclosingMethod = enclosingMethod,
                selectedCode = selectedCode,
            ),
            elements = elements,
        )
        return RefactorTarget(element = first, context = ctx)
    }

    override fun promptContribution(target: RefactorTarget): PromptContribution =
        PromptContribution(
            systemFragment = EXTRACT_RULES,
            jsonShapeExample = JSON_SHAPE_EXAMPLE,
            question = "Should this code selection be extracted into a method, and what should it be named?",
        )

    override fun parse(actionJson: JsonObject): RefactorOperation {
        val action = actionJson.stringField("action")
        if (action != id) throw RefactorParseException("extract_method handler cannot parse action: $action")
        val methodName = actionJson.stringField("methodName")
            ?: throw RefactorParseException("extract_method requires a string methodName.")
        if (methodName.isBlank()) throw RefactorParseException("methodName must not be blank.")
        return ExtractMethodOperation(methodName.trim(), actionJson.stringField("reason"))
    }

    override fun validate(operation: RefactorOperation, target: RefactorTarget, project: Project): ValidationResult {
        val op = operation as? ExtractMethodOperation
            ?: return ValidationResult.Invalid("Unsupported operation for extract_method.")
        return validator.validate(op.methodName, SymbolKind.METHOD, currentName = "", project = project)
    }

    override fun execute(
        operation: RefactorOperation,
        target: RefactorTarget,
        project: Project,
        settings: AiRefactoringSettings.State,
    ): String {
        val op = operation as? ExtractMethodOperation
            ?: error("execute called with non-ExtractMethodOperation: ${operation::class.simpleName}")
        val ctx = target.context as ExtractMethodContext
        val file = ctx.elements.first().containingFile
        return executorFactory().extract(project, file, ctx.elements, op.methodName)
    }

    companion object {
        const val JSON_SHAPE_EXAMPLE =
            """{"action":"extract_method","methodName":"<identifier>","reason":"<short explanation>"}"""

        val EXTRACT_RULES = """
            Extract-method rules:
            - Choose a lowerCamelCase method name that describes WHAT the extracted code does.
            - Never propose a Java keyword.
            - Decide extract_method only when the selection is a clean, cohesive unit worth extracting.
            - When in doubt, return no_action.
        """.trimIndent()
    }
}
