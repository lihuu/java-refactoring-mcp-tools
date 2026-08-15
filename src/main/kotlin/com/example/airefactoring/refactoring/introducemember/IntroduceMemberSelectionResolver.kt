package com.example.airefactoring.refactoring.introducemember

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.JavaSourceTargetResolution
import com.example.airefactoring.refactoring.JavaSourceTargetResolver
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.refactoring.IntroduceVariableUtil

data class IntroduceMemberSelection(
    val file: PsiJavaFile,
    val document: Document,
    val expression: PsiExpression,
    val memberType: PsiType,
    val containingClass: PsiClass,
    val targetClass: PsiClass,
)

sealed interface IntroduceMemberSelectionResolution {
    data class Success(val selection: IntroduceMemberSelection) : IntroduceMemberSelectionResolution
    data class Failure(
        val code: McpRefactoringErrorCode,
        val message: String,
    ) : IntroduceMemberSelectionResolution
}

/**
 * Resolves an MCP range to one exact readable, non-void Java expression plus the nearest class that
 * would own the introduced member.
 */
class IntroduceMemberSelectionResolver(
    private val targetResolver: JavaSourceTargetResolver = JavaSourceTargetResolver(),
) {

    fun resolve(
        project: Project,
        pathInProject: String,
        range: SourceRange,
        targetClassQualifiedName: String? = null,
    ): IntroduceMemberSelectionResolution {
        val target = when (val resolution = targetResolver.resolve(project, pathInProject, range)) {
            is JavaSourceTargetResolution.Failure -> return failure(
                resolution.code,
                resolution.message,
            )
            is JavaSourceTargetResolution.Success -> resolution.target
        }

        val expression = IntroduceVariableUtil.findExpressionInRange(
            project, target.file, target.startOffset, target.endOffset,
        ) ?: return failure(
            McpRefactoringErrorCode.NO_INTRODUCIBLE_EXPRESSION,
            "The selected range does not resolve to one complete Java expression.",
        )
        if (expression.textRange != TextRange(target.startOffset, target.endOffset)) {
            return failure(
                McpRefactoringErrorCode.NO_INTRODUCIBLE_EXPRESSION,
                "The selected range must exactly match one complete Java expression.",
            )
        }
        val type = expression.type ?: return unsupported(
            "The selected expression has no known Java type.",
        )
        if (type == PsiTypes.voidType()) {
            return unsupported("A void expression cannot initialize a member.")
        }
        if (PsiUtil.isAccessedForWriting(expression)) {
            return unsupported("An assignment target or other L-value is not supported.")
        }
        val containingClass = PsiTreeUtil.getParentOfType(expression, PsiClass::class.java, false)
            ?: return failure(
                McpRefactoringErrorCode.UNSUPPORTED_DESTINATION,
                "The selected expression is not inside a Java class.",
            )
        val targetClass = resolveTargetClass(containingClass, targetClassQualifiedName)
            ?: return failure(
                McpRefactoringErrorCode.UNSUPPORTED_DESTINATION,
                "Target class '$targetClassQualifiedName' is not the selected expression's " +
                    "containing class or one of its enclosing classes.",
            )
        return IntroduceMemberSelectionResolution.Success(
            IntroduceMemberSelection(
                target.file,
                target.document,
                expression,
                type,
                containingClass,
                targetClass,
            ),
        )
    }

    private fun resolveTargetClass(
        containingClass: PsiClass,
        targetClassQualifiedName: String?,
    ): PsiClass? {
        if (targetClassQualifiedName == null) return containingClass
        return generateSequence(containingClass) { it.containingClass }
            .firstOrNull { it.qualifiedName == targetClassQualifiedName }
    }

    private fun unsupported(message: String): IntroduceMemberSelectionResolution = failure(
        McpRefactoringErrorCode.UNSUPPORTED_EXPRESSION,
        message,
    )

    private fun failure(
        code: McpRefactoringErrorCode,
        message: String,
    ): IntroduceMemberSelectionResolution = IntroduceMemberSelectionResolution.Failure(
        code,
        message,
    )
}
