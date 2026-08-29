package com.example.airefactoring.refactoring.extractmethodobject

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.JavaSourceTarget
import com.example.airefactoring.refactoring.JavaSourceTargetResolution
import com.example.airefactoring.refactoring.JavaSourceTargetResolver
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameHelper
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil

sealed interface ExtractMethodObjectSelectionResolution {
    data class Success(val preparation: ExtractMethodObjectPreparation) : ExtractMethodObjectSelectionResolution
    data class Failure(val code: McpRefactoringErrorCode, val message: String) : ExtractMethodObjectSelectionResolution
}

class ExtractMethodObjectSelectionResolver(
    private val targetResolver: JavaSourceTargetResolver = JavaSourceTargetResolver(),
) {
    fun resolve(
        project: Project,
        pathInProject: String,
        methodRange: SourceRange,
        methodObjectClassName: String,
        methodObjectMethodName: String,
    ): ExtractMethodObjectSelectionResolution {
        // Identifier validation
        if (methodObjectClassName.isBlank()) {
            return failure(McpRefactoringErrorCode.INVALID_METHOD_NAME, "methodObjectClassName must not be blank.")
        }
        if (!PsiNameHelper.getInstance(project).isIdentifier(methodObjectClassName.trim())) {
            return failure(McpRefactoringErrorCode.INVALID_METHOD_NAME, "methodObjectClassName '$methodObjectClassName' is not a valid Java identifier.")
        }
        if (methodObjectMethodName.isBlank()) {
            return failure(McpRefactoringErrorCode.INVALID_METHOD_NAME, "methodObjectMethodName must not be blank.")
        }
        if (!PsiNameHelper.getInstance(project).isIdentifier(methodObjectMethodName.trim())) {
            return failure(McpRefactoringErrorCode.INVALID_METHOD_NAME, "methodObjectMethodName '$methodObjectMethodName' is not a valid Java identifier.")
        }

        // Resolve method
        val methodTarget = when (val r = targetResolver.resolve(project, pathInProject, methodRange)) {
            is JavaSourceTargetResolution.Failure -> return failure(r.code, r.message)
            is JavaSourceTargetResolution.Success -> r.target
        }
        val method = exactMethod(methodTarget) ?: return failure(
            McpRefactoringErrorCode.INVALID_RANGE,
            "Method range must exactly match a method declaration name.",
        )
        if (method.isConstructor) {
            return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Constructors are not supported.")
        }
        val methodFile = method.containingFile as? PsiJavaFile ?: return failure(
            McpRefactoringErrorCode.NOT_JAVA_FILE,
            "Method file is not Java.",
        )
        val methodVf = methodFile.virtualFile ?: return failure(
            McpRefactoringErrorCode.FILE_NOT_FOUND,
            "Unable to resolve method file.",
        )
        if (!methodVf.isWritable) return failure(McpRefactoringErrorCode.READ_ONLY, "Method file is read-only: $pathInProject")
        if (!ProjectFileIndex.getInstance(project).isInContent(methodVf)) {
            return failure(McpRefactoringErrorCode.OUTSIDE_PROJECT, "Method file outside project content.")
        }

        // Empty / non-selectable body guard: the native processor constructor requires at least
        // one target element (it indexes elements[0] with no editor), so reject here.
        val body = method.body
        if (body == null || body.statements.isEmpty()) {
            return failure(
                McpRefactoringErrorCode.NO_EXTRACTABLE_ELEMENTS,
                "Method '${method.name}' has no extractable body statements.",
            )
        }

        // The Method Object is an inner class of the same file and call sites are preserved by
        // delegation, so the only affected file is the method's own file.
        val affectedFiles: Set<VirtualFile> = setOf(methodVf)

        val methodPointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(method)

        return ExtractMethodObjectSelectionResolution.Success(
            ExtractMethodObjectPreparation(
                methodPointer = methodPointer,
                methodTextSnapshot = method.text,
                methodObjectClassName = methodObjectClassName.trim(),
                methodObjectMethodName = methodObjectMethodName.trim(),
                affectedVirtualFiles = affectedFiles,
            )
        )
    }

    private fun exactMethod(target: JavaSourceTarget): PsiMethod? {
        val leaf = target.file.findElementAt(target.startOffset) ?: return null
        val method = PsiTreeUtil.getParentOfType(leaf, PsiMethod::class.java, false) ?: return null
        val nameRange = method.nameIdentifier?.textRange ?: return null
        if (nameRange.startOffset != target.startOffset || nameRange.endOffset != target.endOffset) return null
        return method
    }

    private fun failure(code: McpRefactoringErrorCode, message: String): ExtractMethodObjectSelectionResolution.Failure =
        ExtractMethodObjectSelectionResolution.Failure(code, message)
}
