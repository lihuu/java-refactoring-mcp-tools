package com.example.airefactoring.refactoring.converttoinstancemethod

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.JavaSourceTargetResolver
import com.example.airefactoring.refactoring.JavaSourceTargetResolution
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.project.Project
import com.intellij.pom.java.JavaFeature
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import java.nio.file.Path

class ConvertToInstanceMethodSelectionResolver(
    private val targetResolver: JavaSourceTargetResolver = JavaSourceTargetResolver(),
) {
    fun resolve(
        project: Project,
        pathInProject: String,
        methodRange: SourceRange,
        targetKind: String,
        targetRange: SourceRange?,
        newVisibility: String?,
        confirmInterfaceImplementations: Boolean,
    ): ConvertToInstanceMethodSelectionResolution {
        val kind = when (targetKind) {
            "parameter" -> ConvertToInstanceMethodTargetKind.PARAMETER
            "containing_class" -> ConvertToInstanceMethodTargetKind.CONTAINING_CLASS
            else -> return failure(
                McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                "targetKind must be 'parameter' or 'containing_class'.",
            )
        }

        if (kind == ConvertToInstanceMethodTargetKind.PARAMETER && targetRange == null) {
            return failure(
                McpRefactoringErrorCode.INVALID_RANGE,
                "targetKind 'parameter' requires all four target coordinates.",
            )
        }
        if (kind == ConvertToInstanceMethodTargetKind.CONTAINING_CLASS && targetRange != null) {
            return failure(
                McpRefactoringErrorCode.INVALID_RANGE,
                "targetKind 'containing_class' must not have target coordinates.",
            )
        }

        if (!isSupportedVisibility(newVisibility)) {
            return failure(
                McpRefactoringErrorCode.INVALID_VISIBILITY,
                "newVisibility must be null, 'public', 'protected', 'private', or 'packageLocal'.",
            )
        }

        val methodTarget = when (
            val resolution = targetResolver.resolve(project, pathInProject, methodRange)
        ) {
            is JavaSourceTargetResolution.Failure -> return failure(resolution.code, resolution.message)
            is JavaSourceTargetResolution.Success -> resolution.target
        }

        val method = findExactDeclaration(methodTarget, PsiMethod::class.java)
            ?: return failure(
                McpRefactoringErrorCode.INVALID_RANGE,
                "The method range must exactly select a method declaration name.",
            )

        if (method.isConstructor) {
            return failure(
                McpRefactoringErrorCode.UNSUPPORTED_METHOD,
                "Convert to Instance Method requires an ordinary static method, not a constructor.",
            )
        }
        if (!method.hasModifierProperty(PsiModifier.STATIC)) {
            return failure(
                McpRefactoringErrorCode.UNSUPPORTED_METHOD,
                "Convert to Instance Method requires a static method, not an instance method.",
            )
        }

        val targetParameter: PsiParameter?
        val targetClass: PsiClass

        when (kind) {
            ConvertToInstanceMethodTargetKind.PARAMETER -> {
                val targetTarget = when (
                    val resolution = targetResolver.resolve(project, pathInProject, targetRange!!)
                ) {
                    is JavaSourceTargetResolution.Failure -> return failure(resolution.code, resolution.message)
                    is JavaSourceTargetResolution.Success -> resolution.target
                }
                val parameter = findExactDeclaration(targetTarget, PsiParameter::class.java)
                    ?: return failure(
                        McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                        "The target must be a parameter of the selected method.",
                    )
                if (parameter.declarationScope !== method) {
                    return failure(
                        McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                        "The target must be a parameter of the selected method.",
                    )
                }
                val type = parameter.type
                if (type !is PsiClassType) {
                    return failure(
                        McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                        "The target parameter type must resolve to a class.",
                    )
                }
                val clazz = type.resolve()
                    ?: return failure(
                        McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                        "The target parameter type must resolve to a class.",
                    )
                if (clazz is PsiTypeParameter) {
                    return failure(
                        McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                        "The target parameter type must be a concrete class, not a type parameter.",
                    )
                }
                if (clazz.qualifiedName == null) {
                    return failure(
                        McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                        "The target parameter class must have a qualified name.",
                    )
                }
                if (!project.isInProject(clazz)) {
                    return failure(
                        McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                        "The target parameter class must be a project class.",
                    )
                }
                targetParameter = parameter
                targetClass = clazz
            }
            ConvertToInstanceMethodTargetKind.CONTAINING_CLASS -> {
                val containing = method.containingClass
                    ?: return failure(
                        McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                        "The containing-class target must be a named, non-enum, non-inner class with a no-argument constructor.",
                    )
                // Handler conditions: named, non-enum, non-inner, not implicit, no-arg ctor
                if (containing.qualifiedName == null) {
                    return failure(
                        McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                        "The containing class must have a qualified name.",
                    )
                }
                if (containing.isEnum) {
                    return failure(
                        McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                        "The containing class must not be an enum.",
                    )
                }
                if (PsiUtil.isInnerClass(containing)) {
                    return failure(
                        McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                        "The containing class must not be an inner class.",
                    )
                }
                // PsiImplicitClass is synthetic; check class name via instanceof by string?
                if (containing.javaClass.simpleName == "PsiImplicitClass" || containing.name == null) {
                    return failure(
                        McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                        "The containing class must not be an implicit class.",
                    )
                }
                val constructors = containing.constructors
                val hasNoArg = if (constructors.isEmpty()) {
                    true
                } else {
                    constructors.any { it.parameterList.parametersCount == 0 }
                }
                if (!hasNoArg) {
                    return failure(
                        McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                        "The containing class must have a no-argument constructor (implicit or explicit).",
                    )
                }
                targetParameter = null
                targetClass = containing
            }
        }

        val targetClassQualifiedName = targetClass.qualifiedName
            ?: return failure(
                McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                "The target class must have a qualified name.",
            )

        // Interface confirmation
        if (targetClass.isInterface && !PsiUtil.isAvailable(JavaFeature.EXTENSION_METHODS, targetClass)) {
            val hasImplementor = ClassInheritorsSearch.search(targetClass, false).findFirst() != null
            if (!hasImplementor) {
                return failure(
                    McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                    "The target interface '${targetClass.qualifiedName}' has no implementing classes.",
                )
            }
            if (!confirmInterfaceImplementations) {
                return failure(
                    McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                    "The target interface '${targetClass.qualifiedName}' requires explicit " +
                        "confirmInterfaceImplementations=true to create implementations.",
                )
            }
        }

        val targetDescription = when (kind) {
            ConvertToInstanceMethodTargetKind.PARAMETER -> {
                val typeText = (targetParameter!!.type as PsiClassType).canonicalText
                "parameter ${targetParameter.name} of type $typeText"
            }
            ConvertToInstanceMethodTargetKind.CONTAINING_CLASS -> {
                "containing class ${targetClass.name} (${targetClassQualifiedName})"
            }
        }

        val pointerManager = SmartPointerManager.getInstance(project)
        val methodOwnerQualifiedNameSnapshot = method.containingClass?.qualifiedName
        val targetParameterTextSnapshot = targetParameter?.text
        val targetParameterTypeSnapshot = (targetParameter?.type as? PsiClassType)?.canonicalText

        return ConvertToInstanceMethodSelectionResolution.Success(
            ConvertToInstanceMethodPreparation(
                methodPointer = pointerManager.createSmartPsiElementPointer(method),
                targetParameterPointer = targetParameter?.let { pointerManager.createSmartPsiElementPointer(it) },
                targetClassPointer = pointerManager.createSmartPsiElementPointer(targetClass),
                targetKind = kind,
                pathInProject = projectRelativePath(project, methodTarget.file.virtualFile.path),
                methodName = method.name,
                targetDescription = targetDescription,
                targetClassQualifiedName = targetClassQualifiedName,
                newVisibility = newVisibility,
                confirmInterfaceImplementations = confirmInterfaceImplementations,
                methodTextSnapshot = method.text,
                methodOwnerQualifiedNameSnapshot = methodOwnerQualifiedNameSnapshot,
                targetParameterTextSnapshot = targetParameterTextSnapshot,
                targetParameterTypeSnapshot = targetParameterTypeSnapshot,
                targetClassQualifiedNameSnapshot = targetClassQualifiedName,
            ),
        )
    }

    private fun isSupportedVisibility(value: String?): Boolean =
        value == null || value == "public" || value == "protected" || value == "private" || value == "packageLocal"

    private fun Project.isInProject(clazz: PsiClass): Boolean {
        val manager = com.intellij.psi.PsiManager.getInstance(this)
        return manager.isInProject(clazz)
    }

    private fun <T : PsiNameIdentifierOwner> findExactDeclaration(
        target: com.example.airefactoring.refactoring.JavaSourceTarget,
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

    private fun projectRelativePath(project: Project, absolutePath: String): String {
        val base = project.basePath ?: return absolutePath
        return Path.of(base).toAbsolutePath().normalize()
            .relativize(Path.of(absolutePath).toAbsolutePath().normalize())
            .toString()
    }

    private fun failure(
        code: McpRefactoringErrorCode,
        message: String,
    ): ConvertToInstanceMethodSelectionResolution =
        ConvertToInstanceMethodSelectionResolution.Failure(code, message)
}
