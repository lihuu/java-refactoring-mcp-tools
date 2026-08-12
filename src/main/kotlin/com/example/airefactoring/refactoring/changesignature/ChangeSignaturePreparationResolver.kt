package com.example.airefactoring.refactoring.changesignature

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.JavaSourcePointTargetResolution
import com.example.airefactoring.refactoring.JavaSourceTargetResolver
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiEllipsisType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.TypeConversionUtil
import com.intellij.util.IncorrectOperationException
import java.nio.file.Path

class ChangeSignaturePreparationResolver(
    private val targetResolver: JavaSourceTargetResolver = JavaSourceTargetResolver(),
) {
    fun resolve(
        project: Project,
        pathInProject: String,
        line: Int,
        column: Int,
        parameterName: String,
        parameterTypeText: String,
        parameterPosition: Int,
        defaultCallSiteExpression: String,
    ): ChangeSignaturePreparationResolution {
        val target = when (
            val resolution = targetResolver.resolvePoint(project, pathInProject, line, column)
        ) {
            is JavaSourcePointTargetResolution.Failure -> return failure(
                resolution.code,
                resolution.message,
            )
            is JavaSourcePointTargetResolution.Success -> resolution.target
        }
        val leaf = target.file.findElementAt(target.offset)
        val method = PsiTreeUtil.getParentOfType(leaf, PsiMethod::class.java, false)
            ?: return failure(
                McpRefactoringErrorCode.NO_TARGET_METHOD,
                "The target position is not inside a Java method.",
            )
        if (method.isConstructor) {
            return failure(
                McpRefactoringErrorCode.UNSUPPORTED_METHOD,
                "Constructors are not supported by this tool.",
            )
        }
        val containingClass = method.containingClass
            ?: return failure(
                McpRefactoringErrorCode.UNSUPPORTED_METHOD,
                "The target method has no containing Java class.",
            )
        if (containingClass.findMethodsByName(method.name, false).size != 1) {
            return failure(
                McpRefactoringErrorCode.UNSUPPORTED_METHOD,
                "Methods in a same-name overload set are not supported.",
            )
        }
        if (
            method.findSuperMethods().isNotEmpty() ||
            OverridingMethodsSearch.search(
                method,
                GlobalSearchScope.projectScope(project),
                true,
            ).findAll().isNotEmpty()
        ) {
            return failure(
                McpRefactoringErrorCode.UNSUPPORTED_METHOD,
                "Methods in an override hierarchy are not supported.",
            )
        }
        if (method.parameterList.parameters.any { it.name == parameterName }) {
            return failure(
                McpRefactoringErrorCode.INVALID_PARAMETER_NAME,
                "The method already has a parameter named '$parameterName'.",
            )
        }
        val parameterType = parseParameterType(project, method, parameterTypeText)
            ?: return failure(
                McpRefactoringErrorCode.INVALID_PARAMETER_TYPE,
                "The parameter type is malformed, unresolved, void, or varargs.",
            )
        val maximumPosition = method.parameterList.parametersCount + 1
        if (parameterPosition !in 1..maximumPosition) {
            return failure(
                McpRefactoringErrorCode.INVALID_PARAMETER_POSITION,
                "Parameter position must be between 1 and $maximumPosition.",
            )
        }

        val calls = mutableListOf<PsiMethodCallExpression>()
        val references = ReferencesSearch.search(
            method,
            GlobalSearchScope.projectScope(project),
        ).findAll()
        for (reference in references) {
            val expression = reference.element as? PsiReferenceExpression
                ?: return failure(
                    McpRefactoringErrorCode.UNSUPPORTED_USAGE,
                    "The method has an unsupported usage.",
                )
            val call = expression.parent as? PsiMethodCallExpression
                ?: return failure(
                    McpRefactoringErrorCode.UNSUPPORTED_USAGE,
                    "The method has a usage that is not a direct Java method call.",
                )
            if (
                call.methodExpression !== expression ||
                !method.manager.areElementsEquivalent(call.resolveMethod(), method)
            ) {
                return failure(
                    McpRefactoringErrorCode.UNSUPPORTED_USAGE,
                    "The method has an ambiguous or unsupported call usage.",
                )
            }
            val callFile = call.containingFile as? PsiJavaFile
                ?: return failure(
                    McpRefactoringErrorCode.UNSUPPORTED_USAGE,
                    "Every call site must be in a Java source file.",
                )
            if (
                !callFile.virtualFile.isWritable ||
                !ProjectFileIndex.getInstance(project).isInContent(callFile.virtualFile)
            ) {
                return failure(
                    McpRefactoringErrorCode.UNSUPPORTED_USAGE,
                    "Every call site must be writable project content.",
                )
            }
            calls += call
        }
        if (
            !validateDefaultExpression(
                project,
                method,
                calls,
                parameterType,
                defaultCallSiteExpression,
            )
        ) {
            return failure(
                McpRefactoringErrorCode.INVALID_DEFAULT_VALUE,
                "The default call-site expression is invalid or incompatible with the parameter type.",
            )
        }

        val declarationPath = projectRelativePath(project, target.file.virtualFile.path)
        val affectedFiles = (calls.map { projectRelativePath(project, it.containingFile.virtualFile.path) } +
            declarationPath).distinct().sorted()
        return ChangeSignaturePreparationResolution.Success(
            ChangeSignaturePreparation(
                methodPointer = SmartPointerManager.getInstance(project)
                    .createSmartPsiElementPointer(method),
                declarationFilePath = declarationPath,
                methodName = method.name,
                originalParameterListText = method.parameterList.text,
                parameterName = parameterName,
                parameterTypeText = parameterTypeText,
                canonicalParameterType = parameterType.canonicalText,
                parameterPosition = parameterPosition,
                defaultCallSiteExpression = defaultCallSiteExpression,
                updatedCallSiteCount = calls.size,
                affectedFiles = affectedFiles,
            ),
        )
    }

    private fun parseParameterType(
        project: Project,
        method: PsiMethod,
        text: String,
    ): PsiType? {
        if (text.isBlank() || text != text.trim()) return null
        val type = try {
            JavaPsiFacade.getElementFactory(project).createTypeFromText(text, method)
        } catch (_: IncorrectOperationException) {
            return null
        }
        return type.takeIf(::isResolvedOrdinaryParameterType)
    }

    private fun isResolvedOrdinaryParameterType(type: PsiType): Boolean = when (type) {
        is PsiEllipsisType -> false
        is PsiPrimitiveType -> type != PsiTypes.voidType()
        is PsiArrayType -> isResolvedOrdinaryParameterType(type.componentType)
        is PsiClassType -> type.resolve() != null &&
            type.parameters.all(::isResolvedOrdinaryParameterType)
        is PsiWildcardType -> type.bound?.let(::isResolvedOrdinaryParameterType) ?: true
        else -> false
    }

    private fun validateDefaultExpression(
        project: Project,
        method: PsiMethod,
        calls: List<PsiMethodCallExpression>,
        parameterType: PsiType,
        expressionText: String,
    ): Boolean {
        if (expressionText.isBlank()) return false
        val contexts = calls.ifEmpty { listOf(method) }
        return contexts.all { context ->
            val expression = try {
                JavaPsiFacade.getElementFactory(project)
                    .createExpressionFromText(expressionText, context)
            } catch (_: IncorrectOperationException) {
                return@all false
            }
            if (PsiTreeUtil.hasErrorElements(expression)) return@all false
            if (context !is PsiMethodCallExpression) return@all true
            val expressionType = expression.type ?: return@all false
            val targetType = context.resolveMethodGenerics().substitutor
                .substitute(parameterType) ?: parameterType
            TypeConversionUtil.isAssignable(targetType, expressionType)
        }
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
    ): ChangeSignaturePreparationResolution = ChangeSignaturePreparationResolution.Failure(
        code,
        message,
    )
}
