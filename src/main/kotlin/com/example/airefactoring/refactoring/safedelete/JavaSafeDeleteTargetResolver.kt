package com.example.airefactoring.refactoring.safedelete

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.JavaSourceTargetResolution
import com.example.airefactoring.refactoring.JavaSourceTargetResolver
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.SmartPointerManager
import com.intellij.refactoring.safeDelete.SafeDeleteProcessor
import com.intellij.usageView.UsageViewUtil
import java.nio.file.Path

/**
 * Resolves a 1-based exact source range to one native safe-delete target. The range must equal
 * either a [PsiNameIdentifierOwner]'s name identifier or the complete range of a nameless
 * candidate; acceptance is delegated entirely to the native [SafeDeleteProcessor.validElement]
 * predicate, with no plugin-defined target-kind allowlist. It never mutates the source.
 */
class JavaSafeDeleteTargetResolver(
    private val targetResolver: JavaSourceTargetResolver = JavaSourceTargetResolver(),
) {

    fun resolve(
        project: Project,
        pathInProject: String,
        range: SourceRange,
    ): SafeDeleteTargetResolution {
        val target = when (val resolution = targetResolver.resolve(project, pathInProject, range)) {
            is JavaSourceTargetResolution.Failure -> return failure(
                resolution.code,
                resolution.message,
            )
            is JavaSourceTargetResolution.Success -> resolution.target
        }

        val file = target.file
        val exactRange = TextRange(target.startOffset, target.endOffset)

        val leaf = file.findElementAt(exactRange.startOffset)
            ?: return failure(
                McpRefactoringErrorCode.INVALID_RANGE,
                "The selected range does not start on a Java element.",
            )

        var matchedCandidate = false
        var element: PsiElement? = leaf
        while (element != null) {
            if (isCandidate(element, exactRange)) {
                matchedCandidate = true
                if (SafeDeleteProcessor.validElement(element)) {
                    return success(project, file, element)
                }
            }
            element = element.parent
        }

        return if (matchedCandidate) {
            failure(
                McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                "The selected element is not a supported safe-delete target.",
            )
        } else {
            failure(
                McpRefactoringErrorCode.INVALID_RANGE,
                "The selected range must exactly match a declaration name or a complete element.",
            )
        }
    }

    /**
     * A candidate is a [PsiNameIdentifierOwner] whose name identifier is selected exactly, or a
     * nameless element whose complete text range is selected exactly.
     */
    private fun isCandidate(element: PsiElement, exactRange: TextRange): Boolean {
        val nameIdentifier = (element as? PsiNameIdentifierOwner)?.nameIdentifier
        return if (nameIdentifier != null) {
            nameIdentifier.textRange == exactRange
        } else {
            element.textRange == exactRange
        }
    }

    private fun success(
        project: Project,
        file: PsiJavaFile,
        element: PsiElement,
    ): SafeDeleteTargetResolution = SafeDeleteTargetResolution.Success(
        SafeDeletePreparation(
            elementPointer = SmartPointerManager.getInstance(project)
                .createSmartPsiElementPointer(element),
            sourceDocumentPath = projectRelativePath(project, file.virtualFile.path),
            targetDescription = UsageViewUtil.getLongName(element),
        ),
    )

    private fun projectRelativePath(project: Project, absolutePath: String): String {
        val base = project.basePath ?: return absolutePath
        return Path.of(base).toAbsolutePath().normalize()
            .relativize(Path.of(absolutePath).toAbsolutePath().normalize())
            .toString()
    }

    private fun failure(
        code: McpRefactoringErrorCode,
        message: String,
    ): SafeDeleteTargetResolution = SafeDeleteTargetResolution.Failure(code, message)
}
