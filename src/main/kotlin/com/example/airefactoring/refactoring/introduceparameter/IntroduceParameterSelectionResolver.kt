package com.example.airefactoring.refactoring.introduceparameter

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.JavaSourceTargetResolution
import com.example.airefactoring.refactoring.JavaSourceTargetResolver
import com.example.airefactoring.refactoring.SourceRange
import com.example.airefactoring.validator.NameValidator
import com.example.airefactoring.validator.ValidationResult
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiResourceVariable
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.PsiVariable
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.refactoring.IntroduceVariableUtil
import java.nio.file.Path

/**
 * Resolves a 1-based exact source range to one introducible parameter source: either a readable,
 * non-void expression or a readable local variable with an initializer, each inside one writable
 * ordinary method. It also computes the sorted unique project-relative paths of the files that
 * contain direct Java callers of that method. It never mutates the source.
 */
class IntroduceParameterSelectionResolver(
    private val targetResolver: JavaSourceTargetResolver = JavaSourceTargetResolver(),
    private val nameValidator: NameValidator = NameValidator(),
) {

    fun resolve(
        project: Project,
        pathInProject: String,
        range: SourceRange,
        parameterName: String,
    ): IntroduceParameterSelectionResolution {
        val target = when (val resolution = targetResolver.resolve(project, pathInProject, range)) {
            is JavaSourceTargetResolution.Failure -> return failure(
                resolution.code,
                resolution.message,
            )
            is JavaSourceTargetResolution.Success -> resolution.target
        }

        when (nameValidator.validateVariableName(parameterName, project)) {
            is ValidationResult.Invalid -> return failure(
                McpRefactoringErrorCode.INVALID_PARAMETER_NAME,
                "The parameter name '$parameterName' is not a valid Java identifier.",
            )
            is ValidationResult.Ok -> Unit
        }

        val file = target.file
        val exactRange = TextRange(target.startOffset, target.endOffset)

        // Preferred: one exact expression. A bare reference that resolves to a local variable is
        // classified as a LOCAL_VARIABLE source; a bare field/parameter reference is unsupported.
        val expression = IntroduceVariableUtil.findExpressionInRange(
            project, file, target.startOffset, target.endOffset,
        )
        if (expression != null && expression.textRange == exactRange) {
            val resolved = (expression as? PsiReferenceExpression)?.resolve()
            when (resolved) {
                is PsiLocalVariable -> return buildLocalVariableSelection(
                    project,
                    target.file,
                    target.document,
                    resolved,
                    parameterName,
                )
                is PsiField -> return unsupportedVariable(
                    "The selected field '$resolved.name' cannot back an introduced parameter.",
                )
                is PsiParameter -> return unsupportedVariable(
                    "The selected parameter '$resolved.name' cannot back an introduced parameter.",
                )
                else -> return buildExpressionSelection(
                    project,
                    target.file,
                    target.document,
                    expression,
                    exactRange,
                    parameterName,
                )
            }
        }

        // Otherwise: a local variable declaration name selected exactly.
        val variable = findVariableDeclarationAtRange(file, exactRange)
        if (variable is PsiLocalVariable) {
            return buildLocalVariableSelection(
                project,
                target.file,
                target.document,
                variable,
                parameterName,
            )
        }
        when (variable) {
            is PsiField -> return unsupportedVariable(
                "A field cannot back an introduced parameter.",
            )
            is PsiParameter -> return unsupportedVariable(
                "A parameter cannot back an introduced parameter.",
            )
        }

        return failure(
            McpRefactoringErrorCode.NO_INTRODUCIBLE_EXPRESSION,
            "The selected range does not resolve to one complete expression or local variable.",
        )
    }

    private fun buildExpressionSelection(
        project: Project,
        file: PsiJavaFile,
        document: Document,
        expression: PsiExpression,
        exactRange: TextRange,
        parameterName: String,
    ): IntroduceParameterSelectionResolution {
        if (expression.textRange != exactRange) {
            return failure(
                McpRefactoringErrorCode.NO_INTRODUCIBLE_EXPRESSION,
                "The selected range must exactly match one complete Java expression.",
            )
        }
        val type = expression.type ?: return unsupported(
            "The selected expression has no known Java type.",
        )
        if (type == PsiTypes.voidType()) {
            return unsupported("A void expression cannot back a parameter.")
        }
        if (PsiUtil.isAccessedForWriting(expression)) {
            return unsupported("An assignment target or other L-value is not supported.")
        }
        val method = enclosingMethod(expression)
            ?: return failure(
                McpRefactoringErrorCode.NO_TARGET_METHOD,
                "The selected expression is not inside a Java method.",
            )
        return finish(project, file, document, method, type, expression, null, parameterName)
    }

    private fun buildLocalVariableSelection(
        project: Project,
        file: PsiJavaFile,
        document: Document,
        variable: PsiLocalVariable,
        parameterName: String,
    ): IntroduceParameterSelectionResolution {
        if (variable is PsiResourceVariable) {
            return unsupported("Resource variables are not supported.")
        }
        if (variable.initializer == null) {
            return unsupported("The local variable must have an initializer.")
        }
        if (
            ReferencesSearch.search(variable, variable.useScope).findAll().any { reference ->
                val referenceExpression = reference.element as? PsiReferenceExpression
                    ?: return@any false
                PsiUtil.isAccessedForWriting(referenceExpression)
            }
        ) {
            return unsupported(
                "A local variable that is written or reassigned is not supported.",
            )
        }
        val type = variable.type
        if (type == PsiTypes.voidType()) {
            return unsupported("A void-typed local variable cannot back a parameter.")
        }
        val method = enclosingMethod(variable)
            ?: return failure(
                McpRefactoringErrorCode.NO_TARGET_METHOD,
                "The local variable is not inside a Java method.",
            )
        return finish(project, file, document, method, type, null, variable, parameterName)
    }

    private fun finish(
        project: Project,
        file: PsiJavaFile,
        document: Document,
        method: PsiMethod,
        sourceType: PsiType,
        expression: PsiExpression?,
        localVariable: PsiLocalVariable?,
        parameterName: String,
    ): IntroduceParameterSelectionResolution {
        val methodRejection = validateMethod(project, method)
        if (methodRejection != null) return methodRejection

        // The MCP contract rejects a name that collides with an existing parameter or any local
        // that survives the refactoring: the name is agent-selected semantic intent, and an implicit
        // native rename would silently change it. The lifted source local is removed by the native
        // refactoring, so it alone may be lifted under its own name.
        val conflictingName = conflictingVariableName(method, localVariable, parameterName)
        if (conflictingName != null) {
            return failure(
                McpRefactoringErrorCode.INVALID_PARAMETER_NAME,
                "The parameter name '$parameterName' conflicts with the existing " +
                    "'$conflictingName' in '${method.name}'.",
            )
        }

        val affected = AffectedFiles()
        affected.paths += projectRelativePath(project, file.virtualFile.path)
        for (reference in ReferencesSearch.search(
            method,
            GlobalSearchScope.projectScope(project),
        ).findAll()) {
            val rejection = validateCallReference(project, reference.element, method, affected)
            if (rejection != null) return rejection
        }

        val affectedFiles = affected.paths.distinct().sorted()
        val snapshots = snapshotAffectedDocuments(project, affectedFiles)
            ?: return failure(
                McpRefactoringErrorCode.UNSUPPORTED_USAGE,
                "Every affected file must have a readable document before refactoring.",
            )
        val pointerManager = SmartPointerManager.getInstance(project)
        return IntroduceParameterSelectionResolution.Success(
            IntroduceParameterSelection(
                sourceKind = if (localVariable != null) {
                    IntroduceParameterSourceKind.LOCAL_VARIABLE
                } else {
                    IntroduceParameterSourceKind.EXPRESSION
                },
                methodPointer = pointerManager.createSmartPsiElementPointer(method),
                expressionPointer = expression?.let(pointerManager::createSmartPsiElementPointer),
                localVariablePointer = localVariable?.let(pointerManager::createSmartPsiElementPointer),
                sourceTypeCanonicalText = sourceType.canonicalText,
                sourceDocumentPath = projectRelativePath(project, file.virtualFile.path),
                methodSignature = methodSignature(method),
                sourceText = expression?.text ?: localVariable!!.text,
                updatedCallSiteCount = affected.callSiteCount,
                affectedFiles = affectedFiles,
                documentSnapshots = snapshots,
            ),
        )
    }

    /**
     * Returns the name of the first parameter or surviving local variable in [method] whose name
     * collides with the new [parameterName], or null when the name is free. [liftedLocal] is the
     * source local being removed by the refactoring (or null for an expression source); every other
     * variable in the method survives and would be shadowed by (or shadow) the new parameter. This
     * mirrors the native processor's same-name-variable conflict detection so the resolver rejects
     * before any mutation rather than letting the native processor rename or prompt.
     */
    private fun conflictingVariableName(
        method: PsiMethod,
        liftedLocal: PsiLocalVariable?,
        parameterName: String,
    ): String? {
        for (variable in PsiTreeUtil.collectElementsOfType(method, PsiVariable::class.java)) {
            if (variable === liftedLocal) continue
            if (variable.name == parameterName) return variable.name
        }
        return null
    }

    private class AffectedFiles {
        val paths = mutableListOf<String>()
        var callSiteCount: Int = 0
    }

    /**
     * Validates that [referenceElement] is a direct Java call to [method] in writable project
     * content, recording its containing file's relative path on success, or returning a [Failure]
     * otherwise.
     */
    private fun validateCallReference(
        project: Project,
        referenceElement: com.intellij.psi.PsiElement,
        method: PsiMethod,
        affected: AffectedFiles,
    ): IntroduceParameterSelectionResolution? {
        val referenceExpression = referenceElement as? PsiReferenceExpression
            ?: return unsupportedUsage(
                "The method has a usage that is not a direct Java method call.",
            )
        val call = referenceExpression.parent as? PsiMethodCallExpression
            ?: return unsupportedUsage(
                "The method has a usage that is not a direct Java method call.",
            )
        if (
            call.methodExpression !== referenceExpression ||
            !method.manager.areElementsEquivalent(call.resolveMethod(), method)
        ) {
            return unsupportedUsage("The method has an ambiguous or unsupported call usage.")
        }
        val callFile = call.containingFile as? PsiJavaFile
            ?: return unsupportedUsage("Every call site must be in a Java source file.")
        if (
            !callFile.virtualFile.isWritable ||
            !ProjectFileIndex.getInstance(project).isInContent(callFile.virtualFile)
        ) {
            return unsupportedUsage("Every call site must be writable project content.")
        }
        affected.paths += projectRelativePath(project, callFile.virtualFile.path)
        affected.callSiteCount++
        return null
    }

    private fun validateMethod(project: Project, method: PsiMethod): IntroduceParameterSelectionResolution? {
        if (method.isConstructor) {
            return failure(
                McpRefactoringErrorCode.UNSUPPORTED_METHOD,
                "Constructors are not supported by this tool.",
            )
        }
        if (method.body == null) {
            return failure(
                McpRefactoringErrorCode.UNSUPPORTED_METHOD,
                "A method without a writable body is not supported.",
            )
        }
        if (method.isVarArgs) {
            return failure(
                McpRefactoringErrorCode.UNSUPPORTED_METHOD,
                "Varargs methods are not supported because V1 always appends the new parameter.",
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
                "Methods in an override or implementation hierarchy are not supported.",
            )
        }
        return null
    }

    /**
     * Returns the variable (local, field, or parameter) whose declaration name is selected exactly
     * by [exactRange], or null when no declaration name covers it.
     */
    private fun findVariableDeclarationAtRange(
        file: PsiJavaFile,
        exactRange: TextRange,
    ): PsiVariable? {
        val start = exactRange.startOffset
        val leaf = file.findElementAt(start) ?: return null
        val variable = PsiTreeUtil.getParentOfType(leaf, PsiVariable::class.java, false)
            ?: return null
        val nameIdentifier = variable.nameIdentifier ?: return null
        return variable.takeIf { nameIdentifier.textRange == exactRange }
    }

    /** Finds the nearest enclosing method of the element, constructor or otherwise. */
    private fun enclosingMethod(element: com.intellij.psi.PsiElement): PsiMethod? =
        PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)

    private fun unsupported(message: String): IntroduceParameterSelectionResolution = failure(
        McpRefactoringErrorCode.UNSUPPORTED_EXPRESSION,
        message,
    )

    private fun unsupportedVariable(message: String): IntroduceParameterSelectionResolution =
        failure(McpRefactoringErrorCode.UNSUPPORTED_VARIABLE, message)

    private fun unsupportedUsage(message: String): IntroduceParameterSelectionResolution =
        failure(McpRefactoringErrorCode.UNSUPPORTED_USAGE, message)

    private fun failure(
        code: McpRefactoringErrorCode,
        message: String,
    ): IntroduceParameterSelectionResolution = IntroduceParameterSelectionResolution.Failure(
        code,
        message,
    )

    private fun snapshotAffectedDocuments(
        project: Project,
        affectedFiles: List<String>,
    ): List<IntroduceParameterDocumentSnapshot>? {
        val basePath = project.basePath ?: return null
        val fileDocumentManager = FileDocumentManager.getInstance()
        val fileSystem = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
        return affectedFiles.map { path ->
            val virtualFile = fileSystem.findFileByPath(
                Path.of(basePath).resolve(path).normalize().toString(),
            ) ?: return null
            val affectedDocument = fileDocumentManager.getDocument(virtualFile) ?: return null
            IntroduceParameterDocumentSnapshot(
                path = path,
                text = affectedDocument.text,
                wasUnsaved = fileDocumentManager.isDocumentUnsaved(affectedDocument),
            )
        }
    }

    private fun methodSignature(method: PsiMethod): String = buildString {
        append(method.name)
        append('(')
        append(method.parameterList.parameters.joinToString(",") { it.type.canonicalText })
        append("):")
        append(method.returnType?.canonicalText.orEmpty())
    }

    private fun projectRelativePath(project: Project, absolutePath: String): String {
        val base = project.basePath ?: return absolutePath
        return Path.of(base).toAbsolutePath().normalize()
            .relativize(Path.of(absolutePath).toAbsolutePath().normalize())
            .toString()
    }
}
