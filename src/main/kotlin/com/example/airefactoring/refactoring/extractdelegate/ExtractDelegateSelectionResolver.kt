package com.example.airefactoring.refactoring.extractdelegate

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.JavaSourceTarget
import com.example.airefactoring.refactoring.JavaSourceTargetResolution
import com.example.airefactoring.refactoring.JavaSourceTargetResolver
import com.example.airefactoring.refactoring.SourceRange
import com.example.airefactoring.validator.NameValidator
import com.example.airefactoring.validator.ValidationResult
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

sealed interface ExtractDelegateSelectionResolution {
    data class Success(val preparation: ExtractDelegatePreparation) : ExtractDelegateSelectionResolution
    data class Failure(val code: McpRefactoringErrorCode, val message: String) : ExtractDelegateSelectionResolution
}

class ExtractDelegateSelectionResolver(
    private val targetResolver: JavaSourceTargetResolver = JavaSourceTargetResolver(),
    private val nameValidator: NameValidator = NameValidator(),
) {
    fun resolve(
        project: Project,
        pathInProject: String,
        classRange: SourceRange,
        extractedFields: List<String>,
        extractedMethods: List<String>,
        newClassName: String,
        extractInnerClass: Boolean,
    ): ExtractDelegateSelectionResolution {
        if (extractedFields.isEmpty() && extractedMethods.isEmpty()) {
            return failure(
                McpRefactoringErrorCode.INVALID_RANGE,
                "At least one field or method must be extracted.",
            )
        }

        when (val nameCheck = nameValidator.validateVariableName(newClassName.trim(), project)) {
            is ValidationResult.Invalid -> return failure(
                McpRefactoringErrorCode.INVALID_RANGE,
                "newClassName '${newClassName.trim()}' is not a valid class name: ${nameCheck.message}",
            )
            ValidationResult.Ok -> {}
        }

        val classTarget = when (val r = targetResolver.resolve(project, pathInProject, classRange)) {
            is JavaSourceTargetResolution.Failure -> return failure(r.code, r.message)
            is JavaSourceTargetResolution.Success -> r.target
        }
        val cls = exactClass(classTarget) ?: return failure(
            McpRefactoringErrorCode.INVALID_RANGE,
            "Class range must exactly match a class declaration name.",
        )
        if (cls.isInterface || cls.isEnum || cls.isAnnotationType) {
            return failure(
                McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                "Only a concrete top-level class supports Extract Delegate.",
            )
        }
        if (cls.containingClass != null) {
            return failure(
                McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                "Inner classes cannot be used as an Extract Delegate source.",
            )
        }
        val classFile = cls.containingFile as? PsiJavaFile ?: return failure(
            McpRefactoringErrorCode.NOT_JAVA_FILE,
            "Class file is not Java.",
        )
        val classVf = classFile.virtualFile ?: return failure(
            McpRefactoringErrorCode.FILE_NOT_FOUND,
            "Unable to resolve class file.",
        )
        if (!classVf.isWritable) return failure(McpRefactoringErrorCode.READ_ONLY, "Class file is read-only: $pathInProject")
        if (!ProjectFileIndex.getInstance(project).isInContent(classVf)) {
            return failure(McpRefactoringErrorCode.OUTSIDE_PROJECT, "Class file outside project content.")
        }

        for (name in (extractedFields + extractedMethods).distinct()) {
            if (extractedFields.count { it == name } > 1) {
                return failure(McpRefactoringErrorCode.INVALID_RANGE, "Field '$name' is requested more than once.")
            }
            if (extractedMethods.count { it == name } > 1) {
                return failure(McpRefactoringErrorCode.INVALID_RANGE, "Method '$name' is requested more than once.")
            }
            if (name.isBlank()) {
                return failure(McpRefactoringErrorCode.INVALID_RANGE, "Extracted member names must not be blank.")
            }
        }

        val matchedFields = resolveFields(cls, extractedFields) ?: return failure(
            McpRefactoringErrorCode.INVALID_RANGE,
            "Extracted field names must each match exactly one field of '${cls.name}'.",
        )
        val matchedMethods = resolveMethods(cls, extractedMethods) ?: return failure(
            McpRefactoringErrorCode.INVALID_RANGE,
            "Extracted method names must each match exactly one non-constructor, non-abstract method of '${cls.name}'.",
        )
        check(!matchedFields.isEmpty() || !matchedMethods.isEmpty()) {
            "At least one member must match."
        }

        // A package-level class with the same name (or the source class itself) would make the
        // native processor fail or conflict; reject here before constructing the processor.
        val currentPackage = classFile.packageName ?: ""
        val targetFqn = (if (currentPackage.isEmpty()) "" else "$currentPackage.") + newClassName.trim()
        if (targetFqn == (cls.qualifiedName ?: cls.name)) {
            return failure(
                McpRefactoringErrorCode.REFACTORING_CONFLICT,
                "newClassName must differ from the source class '$targetFqn'.",
            )
        }
        val existing = JavaPsiFacade.getInstance(project).findClass(targetFqn, GlobalSearchScope.allScope(project))
        if (existing != null) {
            return failure(
                McpRefactoringErrorCode.REFACTORING_CONFLICT,
                "A class '$targetFqn' already exists.",
            )
        }

        // Affected files: source file + referenced files.
        val affectedFiles = mutableSetOf<VirtualFile>()
        affectedFiles.add(classVf)
        val searched = buildList {
            add(cls)
            addAll(matchedFields)
            addAll(matchedMethods)
        }
        for (element in searched) {
            val refs = ReferencesSearch.search(element, GlobalSearchScope.projectScope(project), false).findAll()
            for (ref in refs) {
                val containingFile = ref.element.containingFile as? PsiJavaFile ?: continue
                val vf = containingFile.virtualFile ?: continue
                if (!vf.isWritable) return failure(McpRefactoringErrorCode.READ_ONLY, "Referencing file is read-only: ${vf.path}")
                if (!ProjectFileIndex.getInstance(project).isInContent(vf)) continue
                affectedFiles.add(vf)
            }
        }

        val classPointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(cls)

        return ExtractDelegateSelectionResolution.Success(
            ExtractDelegatePreparation(
                classPointer = classPointer,
                classTextSnapshot = cls.text,
                sourceClassFqn = cls.qualifiedName ?: cls.name ?: "",
                extractedFields = extractedFields.map { it.trim() },
                extractedMethods = extractedMethods.map { it.trim() },
                newClassName = newClassName.trim(),
                extractInnerClass = extractInnerClass,
                affectedVirtualFiles = affectedFiles,
            )
        )
    }

    private fun resolveFields(cls: PsiClass, names: List<String>): List<PsiField>? {
        val matched = mutableListOf<PsiField>()
        for (name in names) {
            val field = cls.findFieldByName(name.trim(), false) ?: return null
            matched.add(field)
        }
        return matched
    }

    private fun resolveMethods(cls: PsiClass, names: List<String>): List<PsiMethod>? {
        val matched = mutableListOf<PsiMethod>()
        for (name in names) {
            val candidates = cls.findMethodsByName(name.trim(), false)
                .filter { !it.isConstructor && !it.hasModifierProperty(PsiModifier.ABSTRACT) }
            if (candidates.size != 1) return null
            matched.add(candidates.single())
        }
        return matched
    }

    private fun exactClass(target: JavaSourceTarget): PsiClass? {
        val leaf = target.file.findElementAt(target.startOffset) ?: return null
        val cls = PsiTreeUtil.getParentOfType(leaf, PsiClass::class.java, false) ?: return null
        val nameRange = cls.nameIdentifier?.textRange ?: return null
        if (nameRange.startOffset != target.startOffset || nameRange.endOffset != target.endOffset) return null
        return cls
    }

    private fun failure(code: McpRefactoringErrorCode, message: String): ExtractDelegateSelectionResolution.Failure =
        ExtractDelegateSelectionResolution.Failure(code, message)
}