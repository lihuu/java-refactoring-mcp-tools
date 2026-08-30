package com.example.airefactoring.refactoring.moveclass

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.JavaSourceTarget
import com.example.airefactoring.refactoring.JavaSourceTargetResolution
import com.example.airefactoring.refactoring.JavaSourceTargetResolver
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiNameHelper
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

sealed interface MoveClassSelectionResolution {
    data class Success(val preparation: MoveClassPreparation) : MoveClassSelectionResolution
    data class Failure(val code: McpRefactoringErrorCode, val message: String) : MoveClassSelectionResolution
}

class MoveClassSelectionResolver(
    private val targetResolver: JavaSourceTargetResolver = JavaSourceTargetResolver(),
) {
    fun resolve(
        project: Project,
        pathInProject: String,
        classRange: SourceRange,
        targetPackage: String,
    ): MoveClassSelectionResolution {
        // Target package validation
        if (targetPackage.isBlank()) {
            return failure(McpRefactoringErrorCode.INVALID_RANGE, "targetPackage must not be blank.")
        }
        if (!PsiNameHelper.getInstance(project).isQualifiedName(targetPackage.trim())) {
            return failure(McpRefactoringErrorCode.INVALID_RANGE, "targetPackage '$targetPackage' is not a valid qualified name.")
        }

        // Resolve class
        val classTarget = when (val r = targetResolver.resolve(project, pathInProject, classRange)) {
            is JavaSourceTargetResolution.Failure -> return failure(r.code, r.message)
            is JavaSourceTargetResolution.Success -> r.target
        }
        val cls = exactClass(classTarget) ?: return failure(
            McpRefactoringErrorCode.INVALID_RANGE,
            "Class range must exactly match a class declaration name.",
        )
        if (cls.isInterface || cls.isEnum || cls.isAnnotationType) {
            return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Only a concrete top-level class can be moved.")
        }
        if (cls.containingClass != null) {
            return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Inner classes cannot be moved by this tool.")
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

        // Same-package move is a no-op / native rejection; reject here.
        val currentPackage = classFile.packageName ?: ""
        if (currentPackage == targetPackage.trim()) {
            return failure(
                McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                "Class is already in package '$targetPackage'.",
            )
        }

        // Affected files: class file + all referencing files.
        val affectedFiles = mutableSetOf<VirtualFile>()
        affectedFiles.add(classVf)
        val refs = ReferencesSearch.search(cls, GlobalSearchScope.projectScope(project), false).findAll()
        for (ref in refs) {
            val element = ref.element
            val containingFile = element.containingFile as? PsiJavaFile ?: continue
            val vf = containingFile.virtualFile ?: continue
            if (!vf.isWritable) return failure(McpRefactoringErrorCode.READ_ONLY, "Referencing file is read-only: ${vf.path}")
            if (!ProjectFileIndex.getInstance(project).isInContent(vf)) continue
            affectedFiles.add(vf)
        }

        val classPointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(cls)

        return MoveClassSelectionResolution.Success(
            MoveClassPreparation(
                classPointer = classPointer,
                classTextSnapshot = cls.text,
                sourceClassFqn = cls.qualifiedName ?: cls.name ?: "",
                targetPackage = targetPackage.trim(),
                affectedVirtualFiles = affectedFiles,
            )
        )
    }

    private fun exactClass(target: JavaSourceTarget): PsiClass? {
        val leaf = target.file.findElementAt(target.startOffset) ?: return null
        val cls = PsiTreeUtil.getParentOfType(leaf, PsiClass::class.java, false) ?: return null
        val nameRange = cls.nameIdentifier?.textRange ?: return null
        if (nameRange.startOffset != target.startOffset || nameRange.endOffset != target.endOffset) return null
        return cls
    }

    private fun failure(code: McpRefactoringErrorCode, message: String): MoveClassSelectionResolution.Failure =
        MoveClassSelectionResolution.Failure(code, message)
}
