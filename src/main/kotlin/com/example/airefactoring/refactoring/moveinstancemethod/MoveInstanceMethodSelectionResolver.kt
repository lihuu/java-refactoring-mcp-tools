package com.example.airefactoring.refactoring.moveinstancemethod

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.JavaSourceTarget
import com.example.airefactoring.refactoring.JavaSourceTargetResolution
import com.example.airefactoring.refactoring.JavaSourceTargetResolver
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiVariable
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Path

class MoveInstanceMethodSelectionResolver(
    private val targetResolver: JavaSourceTargetResolver = JavaSourceTargetResolver(),
) {
    fun resolve(
        project: Project,
        pathInProject: String,
        methodRange: SourceRange,
        targetRange: SourceRange,
        newVisibility: String,
    ): MoveInstanceMethodSelectionResolution {
        if (!isSupportedVisibility(newVisibility)) {
            return failure(
                McpRefactoringErrorCode.INVALID_VISIBILITY,
                "newVisibility must be 'public', 'protected', 'private', or a package-local (empty) value.",
            )
        }
        val methodTarget = when (
            val resolution = targetResolver.resolve(project, pathInProject, methodRange)
        ) {
            is JavaSourceTargetResolution.Failure -> return failure(
                resolution.code,
                resolution.message,
            )
            is JavaSourceTargetResolution.Success -> resolution.target
        }
        val targetTarget = when (
            val resolution = targetResolver.resolve(project, pathInProject, targetRange)
        ) {
            is JavaSourceTargetResolution.Failure -> return failure(
                resolution.code,
                resolution.message,
            )
            is JavaSourceTargetResolution.Success -> resolution.target
        }

        val method = findExactDeclaration(methodTarget, PsiMethod::class.java)
            ?: return failure(
                McpRefactoringErrorCode.INVALID_RANGE,
                "The method range must exactly select a method declaration name.",
            )
        val variable = findExactDeclaration(targetTarget, PsiVariable::class.java)
            ?: return failure(
                McpRefactoringErrorCode.INVALID_RANGE,
                "The target range must exactly select a variable declaration name.",
            )
        if (variable !is PsiParameter || method.parameterList.parameters.none { it === variable }) {
            return failure(
                McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                "The target must be a parameter of the selected method.",
            )
        }
        val targetType = variable.type as? PsiClassType
            ?: return failure(
                McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                "The target parameter type must resolve to a class.",
            )
        val targetClass = targetType.resolve()
            ?: return failure(
                McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                "The target parameter type must resolve to a class.",
            )
        val targetClassQualifiedName = targetClass.qualifiedName
            ?: return failure(
                McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                "The target parameter class must have a qualified name.",
            )
        val typeSnapshot = targetType.canonicalText

        return MoveInstanceMethodSelectionResolution.Success(
            MoveInstanceMethodPreparation(
                methodPointer = SmartPointerManager.getInstance(project)
                    .createSmartPsiElementPointer(method),
                targetPointer = SmartPointerManager.getInstance(project)
                    .createSmartPsiElementPointer(variable),
                pathInProject = projectRelativePath(project, methodTarget.file.virtualFile.path),
                methodName = method.name,
                targetDescription = "parameter ${variable.name} of type $typeSnapshot",
                targetClassQualifiedName = targetClassQualifiedName,
                newVisibility = newVisibility,
                methodTextSnapshot = method.text,
                targetTypeSnapshot = typeSnapshot,
            ),
        )
    }

    private fun <T : PsiNameIdentifierOwner> findExactDeclaration(
        target: JavaSourceTarget,
        type: Class<T>,
    ): T? {
        val leaf = target.file.findElementAt(target.startOffset) ?: return null
        val declaration = PsiTreeUtil.getParentOfType(leaf, type, false) ?: return null
        val nameRange = declaration.nameIdentifier?.textRange ?: return null
        if (nameRange.startOffset != target.startOffset || nameRange.endOffset != target.endOffset) {
            return null
        }
        return declaration
    }

    private fun isSupportedVisibility(value: String): Boolean =
        value == "public" || value == "protected" || value == "private" || value.isEmpty()

    private fun projectRelativePath(project: Project, absolutePath: String): String {
        val base = project.basePath ?: return absolutePath
        return Path.of(base).toAbsolutePath().normalize()
            .relativize(Path.of(absolutePath).toAbsolutePath().normalize())
            .toString()
    }

    private fun failure(
        code: McpRefactoringErrorCode,
        message: String,
    ): MoveInstanceMethodSelectionResolution =
        MoveInstanceMethodSelectionResolution.Failure(code, message)
}
