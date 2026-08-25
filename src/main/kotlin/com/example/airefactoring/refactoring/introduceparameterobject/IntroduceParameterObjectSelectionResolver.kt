package com.example.airefactoring.refactoring.introduceparameterobject

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.JavaSourceTarget
import com.example.airefactoring.refactoring.JavaSourceTargetResolution
import com.example.airefactoring.refactoring.JavaSourceTargetResolver
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameHelper
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Path

sealed interface IntroduceParameterObjectSelectionResolution {
    data class Success(val preparation: IntroduceParameterObjectPreparation) : IntroduceParameterObjectSelectionResolution
    data class Failure(val code: McpRefactoringErrorCode, val message: String) : IntroduceParameterObjectSelectionResolution
}

class IntroduceParameterObjectSelectionResolver(
    private val targetResolver: JavaSourceTargetResolver = JavaSourceTargetResolver(),
) {
    fun resolve(
        project: Project,
        pathInProject: String,
        methodRange: SourceRange,
        parameterNames: List<String>,
        placement: String,
        className: String?,
        targetPackage: String?,
        existingClassFqn: String?,
        generateAccessors: Boolean,
        escalateVisibility: Boolean,
    ): IntroduceParameterObjectSelectionResolution {
        // Placement validation
        val placementEnum = when (placement) {
            "new_top_level" -> JavaParameterObjectPlacement.NEW_TOP_LEVEL
            "new_inner_class" -> JavaParameterObjectPlacement.NEW_INNER_CLASS
            "existing_class" -> JavaParameterObjectPlacement.EXISTING_CLASS
            else -> return failure(
                McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                "Placement must be one of new_top_level, new_inner_class, existing_class.",
            )
        }

        // Exclusive params validation
        when (placementEnum) {
            JavaParameterObjectPlacement.NEW_TOP_LEVEL -> {
                if (className.isNullOrBlank()) return failure(McpRefactoringErrorCode.INVALID_FIELD_NAME, "Top-level placement requires className.")
                if (targetPackage.isNullOrBlank()) return failure(McpRefactoringErrorCode.INVALID_FIELD_NAME, "Top-level placement requires targetPackage.")
                if (!existingClassFqn.isNullOrBlank()) return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Top-level placement must not have existingClassFqn.")
                if (!PsiNameHelper.getInstance(project).isIdentifier(className.trim())) return failure(McpRefactoringErrorCode.INVALID_FIELD_NAME, "className '$className' is not a valid identifier.")
                if (!PsiNameHelper.getInstance(project).isQualifiedName(targetPackage.trim())) return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "targetPackage '$targetPackage' is not a valid qualified name.")
            }
            JavaParameterObjectPlacement.NEW_INNER_CLASS -> {
                if (className.isNullOrBlank()) return failure(McpRefactoringErrorCode.INVALID_FIELD_NAME, "Inner-class placement requires className.")
                if (!targetPackage.isNullOrBlank()) return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Inner-class placement must not have targetPackage.")
                if (!existingClassFqn.isNullOrBlank()) return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Inner-class placement must not have existingClassFqn.")
                if (!PsiNameHelper.getInstance(project).isIdentifier(className.trim())) return failure(McpRefactoringErrorCode.INVALID_FIELD_NAME, "className '$className' is not a valid identifier.")
            }
            JavaParameterObjectPlacement.EXISTING_CLASS -> {
                if (existingClassFqn.isNullOrBlank()) return failure(McpRefactoringErrorCode.INVALID_FIELD_NAME, "Existing-class placement requires existingClassFqn.")
                if (!className.isNullOrBlank()) return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Existing-class placement must not have className.")
                if (!targetPackage.isNullOrBlank()) return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Existing-class placement must not have targetPackage.")
                if (!PsiNameHelper.getInstance(project).isQualifiedName(existingClassFqn.trim())) return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "existingClassFqn '$existingClassFqn' is not a valid qualified name.")
            }
        }

        if (parameterNames.isEmpty()) return failure(McpRefactoringErrorCode.INVALID_RANGE, "At least one parameter must be selected.")
        if (parameterNames.size != parameterNames.distinct().size) return failure(McpRefactoringErrorCode.INVALID_RANGE, "Parameter names must be unique.")
        if (parameterNames.any { it.isBlank() }) return failure(McpRefactoringErrorCode.INVALID_RANGE, "Parameter names must not be blank.")

        // Resolve method
        val methodTarget = when (val r = targetResolver.resolve(project, pathInProject, methodRange)) {
            is JavaSourceTargetResolution.Failure -> return failure(r.code, r.message)
            is JavaSourceTargetResolution.Success -> r.target
        }
        val method = exactMethod(methodTarget) ?: return failure(McpRefactoringErrorCode.INVALID_RANGE, "Method range must exactly match a method declaration name.")
        if (method.isConstructor) return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Constructors are not supported.")
        val methodFile = method.containingFile as? PsiJavaFile ?: return failure(McpRefactoringErrorCode.NOT_JAVA_FILE, "Method file is not Java.")
        val methodVf = methodFile.virtualFile ?: return failure(McpRefactoringErrorCode.FILE_NOT_FOUND, "Unable to resolve method file.")
        if (!methodVf.isWritable) return failure(McpRefactoringErrorCode.READ_ONLY, "Method file is read-only: $pathInProject")
        if (!ProjectFileIndex.getInstance(project).isInContent(methodVf)) return failure(McpRefactoringErrorCode.OUTSIDE_PROJECT, "Method file outside project content.")

        // Validate parameters exist in method, reorder to declaration order
        val methodParamsByName = method.parameterList.parameters.associateBy { it.name }
        val selectedParamsOrdered = mutableListOf<com.intellij.psi.PsiParameter>()
        for (param in method.parameterList.parameters) {
            if (param.name in parameterNames) {
                if (param.name !in selectedParamsOrdered.map { it.name }) {
                    selectedParamsOrdered.add(param)
                }
            }
        }
        if (selectedParamsOrdered.size != parameterNames.size) {
            val unknown = parameterNames.filter { it !in methodParamsByName }
            if (unknown.isNotEmpty()) return failure(McpRefactoringErrorCode.INVALID_RANGE, "Unknown parameter(s): ${unknown.joinToString()}.")
            // Should not happen, but handle
            return failure(McpRefactoringErrorCode.INVALID_RANGE, "Parameter selection mismatch.")
        }
        // Ensure order is declaration order, but also verify input order doesn't matter - we preserve declaration order
        // (spec says list is selection, not reorder request)

        // Validate not all parameters? Actually selecting all is allowed? But if all, merged will replace all - allowed.
        // No extra validation for that.

        // Resolve existing class if needed
        var existingClass: PsiClass? = null
        if (placementEnum == JavaParameterObjectPlacement.EXISTING_CLASS) {
            val fqn = existingClassFqn!!.trim()
            existingClass = JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))
                ?: return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Existing class '$fqn' not found.")
            if (existingClass.isInterface || existingClass.isEnum || existingClass.isAnnotationType) {
                return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Existing class must be a concrete class.")
            }
            val vf = existingClass.containingFile?.virtualFile
                ?: return failure(McpRefactoringErrorCode.FILE_NOT_FOUND, "Existing class file not found.")
            if (!vf.isWritable) return failure(McpRefactoringErrorCode.READ_ONLY, "Existing class file is read-only.")
            if (!ProjectFileIndex.getInstance(project).isInContent(vf)) return failure(McpRefactoringErrorCode.OUTSIDE_PROJECT, "Existing class outside project content.")
            if (ProjectFileIndex.getInstance(project).isInLibrarySource(vf) || ProjectFileIndex.getInstance(project).isInLibraryClasses(vf)) {
                return failure(McpRefactoringErrorCode.OUTSIDE_PROJECT, "Existing class is outside project sources.")
            }
        }

        // Capture usages
        val affectedFiles = mutableSetOf<VirtualFile>()
        affectedFiles.add(methodVf)
        existingClass?.containingFile?.virtualFile?.let { affectedFiles.add(it) }

        var usageCount = 0
        val refs = ReferencesSearch.search(method, GlobalSearchScope.projectScope(project), false).findAll()
        for (ref in refs) {
            val element = ref.element
            val containingFile = element.containingFile as? PsiJavaFile ?: continue
            val vf = containingFile.virtualFile ?: continue
            if (!vf.isWritable) return failure(McpRefactoringErrorCode.READ_ONLY, "Caller file is read-only: ${vf.path}")
            if (!ProjectFileIndex.getInstance(project).isInContent(vf)) continue
            affectedFiles.add(vf)
            usageCount++
        }

        // For new_top_level, also include expected new file destination (but not yet exists) - not needed for affected set yet
        // We'll add it after creation via processor, but for now include method and callers only
        // For validation, we also need to check if targetPackage directory creation would succeed? Not needed now.

        val pointerManager = SmartPointerManager.getInstance(project)
        val methodPointer = pointerManager.createSmartPsiElementPointer(method)
        val paramPointers = selectedParamsOrdered.map { pointerManager.createSmartPsiElementPointer(it) }
        val existingPointer = existingClass?.let { pointerManager.createSmartPsiElementPointer(it) }

        // Snapshots
        val methodText = method.text
        val paramNamesSnapshot = selectedParamsOrdered.map { it.name ?: "" }

        return IntroduceParameterObjectSelectionResolution.Success(
            IntroduceParameterObjectPreparation(
                methodPointer = methodPointer,
                parameterPointers = paramPointers,
                existingClassPointer = existingPointer,
                methodTextSnapshot = methodText,
                parameterNamesSnapshot = paramNamesSnapshot,
                placement = placementEnum,
                className = className?.trim(),
                targetPackage = targetPackage?.trim(),
                existingClassFqn = existingClassFqn?.trim(),
                generateAccessors = generateAccessors,
                escalateVisibility = escalateVisibility,
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

    private fun failure(code: McpRefactoringErrorCode, message: String): IntroduceParameterObjectSelectionResolution.Failure =
        IntroduceParameterObjectSelectionResolution.Failure(code, message)
}
