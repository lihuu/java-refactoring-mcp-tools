package com.example.airefactoring.refactoring.replaceinheritance

import com.example.airefactoring.refactoring.JavaSourceTargetResolver
import com.example.airefactoring.refactoring.SourceRange
import com.example.airefactoring.validator.NameValidator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil

class ReplaceInheritanceWithDelegationSelectionResolver(
    private val project: Project,
) {
    fun resolve(
        pathInProject: String,
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int,
        targetBaseClassFqn: String,
        fieldName: String,
        delegateOtherMembers: Boolean,
        generateGetter: Boolean,
    ): ReplaceInheritanceWithDelegationPreparation {
        val resolver = JavaSourceTargetResolver()
        val range = SourceRange(startLine, startColumn, endLine, endColumn)
        val resolution = resolver.resolve(project, pathInProject, range)

        if (resolution is com.example.airefactoring.refactoring.JavaSourceTargetResolution.Failure) {
            throw SelectionException(resolution.message)
        }

        val target = (resolution as com.example.airefactoring.refactoring.JavaSourceTargetResolution.Success).target
        val leaf = target.file.findElementAt(target.startOffset) ?: throw SelectionException("Could not find element at range.")
        val cls = PsiTreeUtil.getParentOfType(leaf, PsiClass::class.java, false) ?: throw SelectionException("Selected element is not part of a class.")

        // Exact name identifier match
        val nameRange = cls.nameIdentifier?.textRange ?: throw SelectionException("Could not resolve class name identifier.")
        if (nameRange.startOffset != target.startOffset || nameRange.endOffset != target.endOffset) {
            throw SelectionException("Class range must exactly match a class declaration name.")
        }

        // Top-level check
        if (cls.containingClass != null) {
            throw SelectionException("Only top-level classes are supported for replacing inheritance with delegation.")
        }

        // Verify direct-superclass inheritance
        if (!inheritsFrom(cls, targetBaseClassFqn)) {
            throw SelectionException(
                "The class ${cls.qualifiedName} does not directly extend $targetBaseClassFqn. " +
                    "Only the direct superclass can be replaced with delegation.",
            )
        }

        // Validate field name
        val nameVal = NameValidator().validateVariableName(fieldName, project)
        if (nameVal is com.example.airefactoring.validator.ValidationResult.Invalid) {
            throw SelectionException(nameVal.message)
        }

        val affectedFiles = mutableSetOf<VirtualFile>()
        val sourceFile = cls.containingFile?.virtualFile
        if (sourceFile != null) affectedFiles.add(sourceFile)

        return ReplaceInheritanceWithDelegationPreparation(
            classPointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(cls),
            classTextSnapshot = cls.text,
            sourceClassFqn = cls.qualifiedName ?: "",
            targetBaseClassFqn = targetBaseClassFqn,
            fieldName = fieldName,
            delegateOtherMembers = delegateOtherMembers,
            generateGetter = generateGetter,
            affectedVirtualFiles = affectedFiles,
        )
    }

    // V1 scope: the native processor rewrites the single `extends` clause, so only the direct
    // superclass can be replaced; interfaces and deeper ancestors are rejected.
    private fun inheritsFrom(cls: PsiClass, baseFqn: String): Boolean {
        val facade = JavaPsiFacade.getInstance(project)
        val baseClass = facade.findClass(baseFqn, com.intellij.psi.search.GlobalSearchScope.projectScope(project))
            ?: return false

        return cls.superClass == baseClass
    }

    class SelectionException(message: String) : Exception(message)
}
