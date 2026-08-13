package com.example.airefactoring.refactoring.inlinevariable

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.JavaSourcePointTargetResolution
import com.example.airefactoring.refactoring.JavaSourceTargetResolver
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiResourceVariable
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil

class InlineVariableSelection(
    val file: PsiJavaFile,
    val document: Document,
    val variable: PsiLocalVariable,
    val references: List<PsiReferenceExpression>,
    val targetOffset: Int,
)

sealed class InlineVariableSelectionResolution {
    data class Success(
        val selection: InlineVariableSelection,
    ) : InlineVariableSelectionResolution()

    data class Failure(
        val code: McpRefactoringErrorCode,
        val message: String,
    ) : InlineVariableSelectionResolution()
}

/** Resolves a point on a declaration name or reference to one supported Java local variable. */
class InlineVariableSelectionResolver(
    private val targetResolver: JavaSourceTargetResolver = JavaSourceTargetResolver(),
) {

    fun resolve(
        project: Project,
        pathInProject: String,
        line: Int,
        column: Int,
    ): InlineVariableSelectionResolution {
        val target = when (
            val resolution = targetResolver.resolvePoint(project, pathInProject, line, column)
        ) {
            is JavaSourcePointTargetResolution.Failure -> return failure(
                resolution.code,
                resolution.message,
            )
            is JavaSourcePointTargetResolution.Success -> resolution.target
        }

        val leaf = target.file.findElementAt(target.offset)
            ?: return noTarget("The position does not identify a Java local variable.")
        val declaration = PsiTreeUtil.getParentOfType(
            leaf,
            PsiLocalVariable::class.java,
            false,
        )?.takeIf { it.nameIdentifier?.textRange?.containsOffset(target.offset) == true }
        val selectedReference = PsiTreeUtil.getParentOfType(
            leaf,
            PsiReferenceExpression::class.java,
            false,
        )?.takeIf { it.textRange.containsOffset(target.offset) }
        val variable = declaration ?: selectedReference?.resolve() as? PsiLocalVariable
            ?: return noTarget(
                "The position must be on a Java local-variable name or resolved reference.",
            )

        if (variable is PsiResourceVariable) {
            return unsupported("Resource variables are not supported.")
        }
        if (variable.initializer == null) {
            return unsupported("The local variable must have an initializer.")
        }
        val references = ReferencesSearch.search(variable, variable.useScope)
            .findAll()
            .mapNotNull { it.element as? PsiReferenceExpression }
            .sortedBy { it.textRange.startOffset }
        if (references.isEmpty()) {
            return unsupported("The local variable has no references to inline.")
        }
        if (references.any { it.containingFile != target.file }) {
            return unsupported("All local-variable references must be in the target Java file.")
        }
        if (references.any { PsiUtil.isAccessedForWriting(it) }) {
            return unsupported("A local variable that is written or reassigned is not supported.")
        }

        return InlineVariableSelectionResolution.Success(
            InlineVariableSelection(
                target.file,
                target.document,
                variable,
                references,
                target.offset,
            ),
        )
    }

    private fun noTarget(message: String): InlineVariableSelectionResolution = failure(
        McpRefactoringErrorCode.NO_TARGET_VARIABLE,
        message,
    )

    private fun unsupported(message: String): InlineVariableSelectionResolution = failure(
        McpRefactoringErrorCode.UNSUPPORTED_VARIABLE,
        message,
    )

    private fun failure(
        code: McpRefactoringErrorCode,
        message: String,
    ): InlineVariableSelectionResolution = InlineVariableSelectionResolution.Failure(code, message)
}
