package com.example.airefactoring.refactoring.extractmethod

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.JavaSourceTargetResolution
import com.example.airefactoring.refactoring.JavaSourceTargetResolver
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.codeInsight.CodeInsightFrontbackUtil
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiWhiteSpace

/** The resolved selection: current [PsiElement]s plus the [Document] they were resolved against. */
class ExtractMethodSelection(
    val file: PsiJavaFile,
    val document: Document,
    val elements: Array<PsiElement>,
)

sealed class SelectionResolution {
    data class Success(val selection: ExtractMethodSelection) : SelectionResolution()

    data class Failure(
        val code: McpRefactoringErrorCode,
        val message: String,
    ) : SelectionResolution()
}

/**
 * Resolves a current Java source target to one exact expression or a complete statement block.
 * Project containment, file checks, document commit, and coordinate validation are delegated to
 * [JavaSourceTargetResolver].
 */
class ExtractMethodSelectionResolver(
    private val targetResolver: JavaSourceTargetResolver = JavaSourceTargetResolver(),
) {

    fun resolve(project: Project, pathInProject: String, range: SourceRange): SelectionResolution {
        val target = when (val resolution = targetResolver.resolve(project, pathInProject, range)) {
            is JavaSourceTargetResolution.Failure -> return SelectionResolution.Failure(
                resolution.code,
                resolution.message,
            )
            is JavaSourceTargetResolution.Success -> resolution.target
        }

        val expression = CodeInsightFrontbackUtil.findExpressionInRange(
            target.file,
            target.startOffset,
            target.endOffset,
        )
        val elements: Array<PsiElement> = if (expression != null) {
            arrayOf(expression)
        } else {
            CodeInsightFrontbackUtil.findStatementsInRange(
                target.file,
                target.startOffset,
                target.endOffset,
            )
                .filterNot { it is PsiComment || it is PsiWhiteSpace }
                .toTypedArray()
        }
        if (elements.isEmpty()) {
            return SelectionResolution.Failure(
                McpRefactoringErrorCode.NO_EXTRACTABLE_ELEMENTS,
                "The selected range does not resolve to a Java expression or statement block.",
            )
        }
        return SelectionResolution.Success(
            ExtractMethodSelection(target.file, target.document, elements),
        )
    }
}
