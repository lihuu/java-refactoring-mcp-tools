package com.example.airefactoring.refactoring.makestatic

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.JavaSourceTarget
import com.example.airefactoring.refactoring.JavaSourceTargetResolution
import com.example.airefactoring.refactoring.JavaSourceTargetResolver
import com.example.airefactoring.refactoring.SourceRange
import com.example.airefactoring.validator.NameValidator
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiTypeParameterListOwner
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.makeStatic.MakeStaticHandler
import java.nio.file.Path

/**
 * Resolves exact Java member and explicit-field selections for one Java Make Static request under a
 * smart read. The target range must select exactly the declaration name of an instance method or a
 * non-static inner class; every other PSI element is rejected. Selected fields must be non-static
 * instance fields of exactly the target member's containing class. The resolver never discovers
 * fields, chooses parameter names, or reorders input. All non-local native eligibility is deferred
 * to [MakeStaticHandler.validateTarget].
 */
class JavaMakeStaticSelectionResolver(
    private val targetResolver: JavaSourceTargetResolver = JavaSourceTargetResolver(),
    private val nameValidator: NameValidator = NameValidator(),
) {

    fun resolve(
        project: Project,
        pathInProject: String,
        memberRange: SourceRange,
        replaceUsages: Boolean,
        classParameterName: String?,
        fieldParameters: List<JavaMakeStaticFieldParameter>,
        generateDelegate: Boolean,
    ): JavaMakeStaticSelectionResolution {
        if (classParameterName != null && !isValidParameterName(classParameterName, project)) {
            return failure(
                McpRefactoringErrorCode.INVALID_PARAMETER_NAME,
                "classParameterName must be a valid Java identifier.",
            )
        }
        val memberTarget = when (val resolution = targetResolver.resolve(project, pathInProject, memberRange)) {
            is JavaSourceTargetResolution.Failure -> return failure(resolution.code, resolution.message)
            is JavaSourceTargetResolution.Success -> resolution.target
        }

        val method = exactDeclaration(memberTarget, PsiMethod::class.java)
        val clazz = exactDeclaration(memberTarget, PsiClass::class.java)
        val memberKind: JavaMakeStaticMemberKind
        val member: PsiTypeParameterListOwner
        val memberName: String
        when {
            method != null -> {
                if (method.isConstructor) {
                    return failure(
                        McpRefactoringErrorCode.UNSUPPORTED_METHOD,
                        "Make Static requires an ordinary instance method, not a constructor.",
                    )
                }
                if (method.hasModifierProperty(PsiModifier.STATIC)) {
                    return failure(
                        McpRefactoringErrorCode.UNSUPPORTED_METHOD,
                        "Make Static requires an instance method, not a static method.",
                    )
                }
                memberKind = JavaMakeStaticMemberKind.METHOD
                member = method
                memberName = method.name
            }
            clazz != null -> {
                if (clazz.containingClass == null) {
                    return failure(
                        McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                        "Make Static requires a non-static inner class, not a top-level class.",
                    )
                }
                if (clazz.hasModifierProperty(PsiModifier.STATIC)) {
                    return failure(
                        McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                        "Make Static requires a non-static inner class, not a static one.",
                    )
                }
                memberKind = JavaMakeStaticMemberKind.CLASS
                member = clazz
                memberName = clazz.name!!
            }
            else -> return failure(
                McpRefactoringErrorCode.INVALID_RANGE,
                "The member range must exactly select a method or class declaration name.",
            )
        }

        // MakeStaticHandler.validateTarget is the single authority for all remaining native
        // eligibility (e.g. abstract methods, members whose containing class cannot hold statics).
        val validationMessage = MakeStaticHandler.validateTarget(member)
        if (validationMessage != null) {
            return failure(McpRefactoringErrorCode.PREPARE_FAILED, validationMessage)
        }

        val memberOwner = member.containingClass ?: return failure(
            McpRefactoringErrorCode.PREPARE_FAILED,
            "The Java Make Static target no longer has a containing class.",
        )
        val pointerManager = SmartPointerManager.getInstance(project)
        val fieldPointers = mutableListOf<SmartPsiElementPointer<PsiField>>()
        val fieldTextSnapshots = mutableListOf<String>()
        val fieldTypeSnapshots = mutableListOf<String>()
        val fieldParameterNames = mutableListOf<String>()
        val seenFields = mutableSetOf<PsiField>()
        val seenNames = mutableSetOf<String>()

        for (fieldParameter in fieldParameters) {
            if (!isValidParameterName(fieldParameter.parameterName, project)) {
                return failure(
                    McpRefactoringErrorCode.INVALID_PARAMETER_NAME,
                    "Field parameter name '${fieldParameter.parameterName}' is not a valid Java identifier.",
                )
            }
            if (!seenNames.add(fieldParameter.parameterName)) {
                return failure(
                    McpRefactoringErrorCode.INVALID_PARAMETER_NAME,
                    "Field parameter name '${fieldParameter.parameterName}' is used more than once.",
                )
            }
            val fieldTarget = when (
                val resolution = targetResolver.resolve(project, pathInProject, fieldParameter.range())
            ) {
                is JavaSourceTargetResolution.Failure -> return failure(resolution.code, resolution.message)
                is JavaSourceTargetResolution.Success -> resolution.target
            }
            val field = exactDeclaration(fieldTarget, PsiField::class.java)
                ?: return failure(
                    McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                    "Each selected field must exactly match a field declaration name.",
                )
            if (field.hasModifierProperty(PsiModifier.STATIC)) {
                return failure(
                    McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                    "A selected field must be an instance field, not a static field.",
                )
            }
            if (field.containingClass !== memberOwner) {
                return failure(
                    McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                    "A selected field must belong to the target member's containing class.",
                )
            }
            if (!seenFields.add(field)) {
                return failure(
                    McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                    "A field cannot be selected more than once.",
                )
            }
            fieldPointers.add(pointerManager.createSmartPsiElementPointer(field))
            fieldTextSnapshots.add(field.text)
            fieldTypeSnapshots.add(field.type.canonicalText)
            fieldParameterNames.add(fieldParameter.parameterName)
        }

        if (classParameterName != null && classParameterName in fieldParameterNames) {
            return failure(
                McpRefactoringErrorCode.INVALID_PARAMETER_NAME,
                "classParameterName duplicates a field parameter name.",
            )
        }

        return JavaMakeStaticSelectionResolution.Success(
            JavaMakeStaticPreparation(
                memberPointer = pointerManager.createSmartPsiElementPointer(member),
                memberOwnerPointer = pointerManager.createSmartPsiElementPointer(memberOwner),
                fieldPointers = fieldPointers,
                memberTextSnapshot = member.text,
                fieldTextSnapshots = fieldTextSnapshots,
                fieldTypeSnapshots = fieldTypeSnapshots,
                pathInProject = projectRelativePath(project, memberTarget.file.virtualFile.path),
                memberKind = memberKind,
                memberName = memberName,
                replaceUsages = replaceUsages,
                classParameterName = classParameterName,
                fieldParameterNames = fieldParameterNames,
                generateDelegate = generateDelegate,
            ),
        )
    }

    private fun isValidParameterName(name: String, project: Project): Boolean =
        nameValidator.validateVariableName(name, project) is com.example.airefactoring.validator.ValidationResult.Ok

    private fun <T : PsiNameIdentifierOwner> exactDeclaration(
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

    private fun projectRelativePath(project: Project, absolutePath: String): String {
        val base = project.basePath ?: return absolutePath
        return Path.of(base).toAbsolutePath().normalize()
            .relativize(Path.of(absolutePath).toAbsolutePath().normalize())
            .toString()
    }

    private fun failure(
        code: McpRefactoringErrorCode,
        message: String,
    ): JavaMakeStaticSelectionResolution = JavaMakeStaticSelectionResolution.Failure(code, message)
}
