package com.example.airefactoring.refactoring.inlinemethod

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.JavaSourceTarget
import com.example.airefactoring.refactoring.JavaSourceTargetResolution
import com.example.airefactoring.refactoring.JavaSourceTargetResolver
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.util.TextRange

/** Resolves an exact Java method name and proves every reference is a direct code call. */
class InlineMethodSelectionResolver(
    private val targetResolver: JavaSourceTargetResolver = JavaSourceTargetResolver(),
) {
    fun resolve(
        project: Project,
        pathInProject: String,
        methodRange: SourceRange,
    ): InlineMethodSelectionResolution {
        val target = when (val resolution = targetResolver.resolve(project, pathInProject, methodRange)) {
            is JavaSourceTargetResolution.Failure -> return failure(resolution.code, resolution.message)
            is JavaSourceTargetResolution.Success -> resolution.target
        }
        val method = findExactDeclaration(target, PsiMethod::class.java)
            ?: return failure(
                McpRefactoringErrorCode.INVALID_RANGE,
                "The method range must exactly select a method declaration name.",
            )
        val sourceVirtualFile = method.containingFile?.virtualFile
            ?: return failure(McpRefactoringErrorCode.FILE_NOT_FOUND, "Unable to resolve the method source file.")
        if (!sourceVirtualFile.isValid || !sourceVirtualFile.isWritable) {
            return failure(McpRefactoringErrorCode.READ_ONLY, "The method source file is read-only.")
        }
        unsupportedMethod(method)?.let { return failure(McpRefactoringErrorCode.UNSUPPORTED_METHOD, it) }

        val references = ReferencesSearch.search(method, GlobalSearchScope.projectScope(project)).findAll()
        if (references.isEmpty()) {
            return failure(McpRefactoringErrorCode.UNSUPPORTED_METHOD, "The method has no Java code usages to inline.")
        }

        val usages = mutableListOf<Pair<PsiReferenceExpression, VirtualFile>>()
        for (reference in references) {
            val expression = reference.element as? PsiReferenceExpression
                ?: return failure(McpRefactoringErrorCode.UNSUPPORTED_USAGE, "Every usage must be a direct Java method call.")
            if (expression is PsiMethodReferenceExpression) {
                return failure(McpRefactoringErrorCode.UNSUPPORTED_USAGE, "Method references cannot be inlined by this tool.")
            }
            if (expression.resolve() !== method) {
                return failure(McpRefactoringErrorCode.UNSUPPORTED_USAGE, "Every usage must resolve to the selected method.")
            }
            val call = expression.parent as? PsiMethodCallExpression
            if (call?.methodExpression !== expression) {
                return failure(McpRefactoringErrorCode.UNSUPPORTED_USAGE, "Every usage must be a direct Java method call.")
            }
            if (PsiTreeUtil.getParentOfType(expression, PsiMethod::class.java, false) === method) {
                return failure(McpRefactoringErrorCode.UNSUPPORTED_METHOD, "Recursive methods cannot be inlined.")
            }
            val usageFile = expression.containingFile?.virtualFile
                ?: return failure(McpRefactoringErrorCode.UNSUPPORTED_USAGE, "A usage has no writable Java source file.")
            if (!usageFile.isValid || !usageFile.isWritable) {
                return failure(McpRefactoringErrorCode.READ_ONLY, "A Java usage file is read-only: ${usageFile.path}")
            }
            usages += expression to usageFile
        }

        val orderedUsages = usages.sortedWith(compareBy({ it.second.path }, { it.first.textRange.startOffset }))
        val pointerManager = SmartPointerManager.getInstance(project)
        return InlineMethodSelectionResolution.Success(
            InlineMethodPreparation(
                methodPointer = pointerManager.createSmartPsiElementPointer(method),
                usagePointers = orderedUsages.map { pointerManager.createSmartPsiElementPointer(it.first) },
                sourceVirtualFile = sourceVirtualFile,
                affectedVirtualFiles = (orderedUsages.map { it.second } + sourceVirtualFile).toSet(),
                pathInProject = pathInProject,
                methodName = method.name,
                methodTextSnapshot = method.text,
                ownerQualifiedNameSnapshot = method.containingClass?.qualifiedName,
                usageOffsetsSnapshot = orderedUsages.map { it.first.textRange.startOffset },
                usageSnapshots = orderedUsages.map {
                    InlineMethodUsageSnapshot(it.second.path, it.first.textRange.startOffset)
                },
            ),
        )
    }

    private fun unsupportedMethod(method: PsiMethod): String? = when {
        method.isConstructor -> "Constructors cannot be inlined."
        method.body == null -> "Methods without a body cannot be inlined."
        method.hasModifierProperty(PsiModifier.NATIVE) -> "Native methods cannot be inlined."
        method.findSuperMethods().isNotEmpty() -> "Methods that override or implement another method cannot be inlined."
        OverridingMethodsSearch.search(method, GlobalSearchScope.projectScope(method.project), true).findFirst() != null ->
            "Methods with overriding implementations cannot be inlined."
        else -> null
    }

    private fun <T : PsiNameIdentifierOwner> findExactDeclaration(
        target: JavaSourceTarget,
        type: Class<T>,
    ): T? {
        val leaf = target.file.findElementAt(target.startOffset) ?: return null
        val declaration = PsiTreeUtil.getParentOfType(leaf, type, false) ?: return null
        return declaration.takeIf {
            it.nameIdentifier?.textRange == TextRange(target.startOffset, target.endOffset)
        }
    }

    private fun failure(
        code: McpRefactoringErrorCode,
        message: String,
    ): InlineMethodSelectionResolution = InlineMethodSelectionResolution.Failure(code, message)
}
