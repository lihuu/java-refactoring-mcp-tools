package com.example.airefactoring.refactoring.useinterface

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.JavaSourceTarget
import com.example.airefactoring.refactoring.JavaSourceTargetResolution
import com.example.airefactoring.refactoring.JavaSourceTargetResolver
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameHelper
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Path

class UseInterfaceWherePossibleSelectionResolver(
    private val targetResolver: JavaSourceTargetResolver = JavaSourceTargetResolver(),
) {
    fun resolve(
        project: Project,
        pathInProject: String,
        sourceClassStartLine: Int,
        sourceClassStartColumn: Int,
        sourceClassEndLine: Int,
        sourceClassEndColumn: Int,
        targetInterfaceFqn: String,
    ): UseInterfaceWherePossibleSelectionResolution {
        val trimmedFqn = targetInterfaceFqn.trim()
        if (trimmedFqn.isEmpty()) {
            return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Target interface FQN must not be empty.")
        }
        if (!PsiNameHelper.getInstance(project).isQualifiedName(trimmedFqn)) {
            return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Target interface FQN '$trimmedFqn' is not a valid qualified name.")
        }
        val targetInterface = JavaPsiFacade.getInstance(project).findClass(trimmedFqn, GlobalSearchScope.allScope(project))
            ?: return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Target interface '$trimmedFqn' not found.")
        if (!targetInterface.isInterface) {
            return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Target '$trimmedFqn' is not an interface; V1 only widens to interfaces.")
        }

        val classRange = SourceRange(sourceClassStartLine, sourceClassStartColumn, sourceClassEndLine, sourceClassEndColumn)
        val classTarget = when (val r = targetResolver.resolve(project, pathInProject, classRange)) {
            is JavaSourceTargetResolution.Failure -> return failure(r.code, r.message)
            is JavaSourceTargetResolution.Success -> r.target
        }
        val sourceClass = exactDeclaration(classTarget, PsiClass::class.java)
            ?: return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Source class range must exactly match a class declaration name.")
        if (sourceClass.isInterface || sourceClass.isEnum || sourceClass.isAnnotationType) {
            return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Source must be a concrete class, not an interface/enum/annotation.")
        }
        if (sourceClass is PsiAnonymousClass) {
            return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Anonymous classes cannot be a Use Interface source.")
        }
        val sourceFqn = sourceClass.qualifiedName
            ?: return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Source class must have a qualified name.")
        if (PsiManager.getInstance(project).areElementsEquivalent(sourceClass, targetInterface)) {
            return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Target interface must differ from the source class.")
        }
        if (!sourceClass.isInheritor(targetInterface, true)) {
            return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Target '$trimmedFqn' is not an interface supertype of '$sourceFqn'.")
        }

        val pointerManager = SmartPointerManager.getInstance(project)
        val sourcePointer = pointerManager.createSmartPsiElementPointer(sourceClass)
        val targetPointer = pointerManager.createSmartPsiElementPointer(targetInterface)
        val sourceVf = sourceClass.containingFile?.virtualFile
            ?: return failure(McpRefactoringErrorCode.FILE_NOT_FOUND, "Unable to resolve containing file.")
        val relativePath = projectRelativePath(project, sourceVf.path)

        return UseInterfaceWherePossibleSelectionResolution.Success(
            UseInterfaceWherePossiblePreparation(
                sourceClassPointer = sourcePointer,
                targetInterfacePointer = targetPointer,
                sourceQualifiedNameSnapshot = sourceFqn,
                targetInterfaceFqnSnapshot = trimmedFqn,
                pathInProject = relativePath,
                targetInterfaceFqn = trimmedFqn,
            ),
        )
    }

    private fun <T : PsiNameIdentifierOwner> exactDeclaration(
        target: JavaSourceTarget,
        type: Class<T>,
    ): T? {
        val leaf = target.file.findElementAt(target.startOffset) ?: return null
        val declaration = PsiTreeUtil.getParentOfType(leaf, type, false) ?: return null
        val nameRange = declaration.nameIdentifier?.textRange ?: return null
        if (nameRange.startOffset != target.startOffset || nameRange.endOffset != target.endOffset) return null
        return declaration
    }

    private fun projectRelativePath(project: Project, absolutePath: String): String {
        val base = project.basePath ?: return absolutePath
        return Path.of(base).toAbsolutePath().normalize()
            .relativize(Path.of(absolutePath).toAbsolutePath().normalize())
            .toString()
    }

    private fun failure(code: McpRefactoringErrorCode, message: String): UseInterfaceWherePossibleSelectionResolution.Failure =
        UseInterfaceWherePossibleSelectionResolution.Failure(code, message)
}
