package com.example.airefactoring.refactoring.introduceparameterobject

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
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.serialization.json.JsonObject

/**
 * Concrete [RefactoringHandler] that folds a method's parameters into a new parameter object. Its
 * target is the enclosing [PsiMethod]; it applies only when that method has at least [MIN_PARAMS]
 * parameters that may form a cohesive concept.
 */
class IntroduceParameterObjectHandler(
    private val executorFactory: () -> IntroduceParameterObjectExecutor = ::IntellijIntroduceParameterObjectExecutor,
) : RefactoringHandler {
    private val validator = NameValidator()
    override val id = "introduce_parameter_object"
    override val displayName = "method parameters"
    override val notApplicableMessage =
        "Place the caret inside a method with several parameters to introduce a parameter object."

    override fun resolve(file: PsiFile, editor: Editor, caretOffset: Int): RefactorTarget? {
        if (file !is PsiJavaFile) return null
        val leaf = file.findElementAt(caretOffset) ?: return null
        val method = PsiTreeUtil.getParentOfType(leaf, PsiMethod::class.java) ?: return null
        if (method.parameterList.parametersCount < MIN_PARAMS) return null

        val parameters = method.parameterList.parameters.map { "${it.type.presentableText} ${it.name}" }
        val methodSignature = method.name + "(" + parameters.joinToString(", ") + ")"
        val ctx = IntroduceParameterObjectContext(
            data = IntroduceParameterObjectContext.SerializableData(
                language = "java",
                filePath = file.virtualFile?.path ?: file.name,
                enclosingClass = PsiTreeUtil.getParentOfType(method, PsiClass::class.java)?.name,
                methodName = method.name,
                methodSignature = methodSignature,
                parameters = parameters,
            ),
        )
        return RefactorTarget(element = method, context = ctx)
    }

    override fun promptContribution(target: RefactorTarget): PromptContribution {
        val methodName = (target.context as IntroduceParameterObjectContext).data.methodName
        return PromptContribution(
            systemFragment = INTRODUCE_RULES,
            jsonShapeExample = JSON_SHAPE_EXAMPLE,
            question = "What should the parameter object wrapping $methodName's parameters be named?",
        )
    }

    override fun parse(actionJson: JsonObject): RefactorOperation {
        val action = actionJson.stringField("action")
        if (action != id) throw RefactorParseException("introduce_parameter_object handler cannot parse action: $action")
        val className = actionJson.stringField("className")
            ?: throw RefactorParseException("introduce_parameter_object requires a string className.")
        if (className.isBlank()) throw RefactorParseException("className must not be blank.")
        return IntroduceParameterObjectOperation(className.trim(), actionJson.stringField("reason"))
    }

    override fun validate(operation: RefactorOperation, target: RefactorTarget, project: Project): ValidationResult {
        val op = operation as? IntroduceParameterObjectOperation
            ?: return ValidationResult.Invalid("Unsupported operation for introduce_parameter_object.")
        return validator.validate(op.className, SymbolKind.CLASS, currentName = "", project = project)
    }

    override fun execute(
        operation: RefactorOperation,
        target: RefactorTarget,
        project: Project,
        settings: AiRefactoringSettings.State,
    ): String {
        val op = operation as? IntroduceParameterObjectOperation
            ?: error("execute called with non-IntroduceParameterObjectOperation: ${operation::class.simpleName}")
        val method = target.element as PsiMethod
        return executorFactory().introduce(project, method, op.className)
    }

    companion object {
        const val MIN_PARAMS = 3

        const val JSON_SHAPE_EXAMPLE =
            """{"action":"introduce_parameter_object","className":"<TypeName>","reason":"<short explanation>"}"""

        val INTRODUCE_RULES = """
            Introduce-parameter-object rules:
            - Choose an UpperCamelCase class name describing what the group of parameters represents together.
            - Never propose a Java keyword.
            - Decide introduce_parameter_object only when these parameters genuinely form a cohesive concept.
            - When in doubt, return no_action.
        """.trimIndent()
    }
}
