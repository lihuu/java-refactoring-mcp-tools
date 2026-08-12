package com.example.airefactoring.refactoring.introducevariable

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.JavaSourceTargetResolution
import com.example.airefactoring.refactoring.JavaSourceTargetResolver
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.util.PsiUtil
import com.intellij.refactoring.IntroduceVariableUtil

class IntroduceVariableSelection(
    val file: PsiJavaFile,
    val document: Document,
    val expression: PsiExpression,
    val variableType: PsiType,
)

sealed class IntroduceVariableSelectionResolution {
    data class Success(
        val selection: IntroduceVariableSelection,
    ) : IntroduceVariableSelectionResolution()

    data class Failure(
        val code: McpRefactoringErrorCode,
        val message: String,
    ) : IntroduceVariableSelectionResolution()
}

/** Resolves an MCP range to one exact readable, non-void Java expression. */
class IntroduceVariableSelectionResolver(
    private val targetResolver: JavaSourceTargetResolver = JavaSourceTargetResolver(),
) {

    fun resolve(
        project: Project,
        pathInProject: String,
        range: SourceRange,
    ): IntroduceVariableSelectionResolution {
        val target = when (val resolution = targetResolver.resolve(project, pathInProject, range)) {
            is JavaSourceTargetResolution.Failure -> return failure(
                resolution.code,
                resolution.message,
            )
            is JavaSourceTargetResolution.Success -> resolution.target
        }

        val expression = IntroduceVariableUtil.findExpressionInRange(
            project,
            target.file,
            target.startOffset,
            target.endOffset,
        ) ?: return failure(
            McpRefactoringErrorCode.NO_INTRODUCIBLE_EXPRESSION,
            "The selected range does not resolve to one complete Java expression.",
        )
        if (
            expression.textRange.startOffset != target.startOffset ||
            expression.textRange.endOffset != target.endOffset
        ) {
            return failure(
                McpRefactoringErrorCode.NO_INTRODUCIBLE_EXPRESSION,
                "The selected range must exactly match one complete Java expression.",
            )
        }
        val variableType = expression.type ?: return unsupported(
            "The selected expression has no known Java type.",
        )
        if (variableType == PsiTypes.voidType()) {
            return unsupported("A void expression cannot initialize a local variable.")
        }
        if (PsiUtil.isAccessedForWriting(expression)) {
            return unsupported("An assignment target or other L-value is not supported.")
        }
        return IntroduceVariableSelectionResolution.Success(
            IntroduceVariableSelection(
                target.file,
                target.document,
                expression,
                variableType,
            ),
        )
    }

    private fun unsupported(message: String): IntroduceVariableSelectionResolution = failure(
        McpRefactoringErrorCode.UNSUPPORTED_EXPRESSION,
        message,
    )

    private fun failure(
        code: McpRefactoringErrorCode,
        message: String,
    ): IntroduceVariableSelectionResolution = IntroduceVariableSelectionResolution.Failure(
        code,
        message,
    )
}
