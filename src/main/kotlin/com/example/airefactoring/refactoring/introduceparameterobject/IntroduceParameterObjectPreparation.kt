package com.example.airefactoring.refactoring.introduceparameterobject

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.SmartPsiElementPointer

enum class JavaParameterObjectPlacement {
    NEW_TOP_LEVEL,
    NEW_INNER_CLASS,
    EXISTING_CLASS,
}

data class IntroduceParameterObjectPreparation(
    val methodPointer: SmartPsiElementPointer<PsiMethod>,
    val parameterPointers: List<SmartPsiElementPointer<PsiParameter>>,
    val existingClassPointer: SmartPsiElementPointer<PsiClass>?,
    val methodTextSnapshot: String,
    val parameterNamesSnapshot: List<String>,
    val placement: JavaParameterObjectPlacement,
    val className: String?,
    val targetPackage: String?,
    val existingClassFqn: String?,
    val generateAccessors: Boolean,
    val escalateVisibility: Boolean,
    val affectedVirtualFiles: Set<VirtualFile>,
)

data class IntroduceParameterObjectExecutionResult(
    val methodName: String,
    val parameterObjectClass: String,
    val placement: String,
    val mergedParameterCount: Int,
    val nativeUsageCount: Int,
    val affectedFiles: List<String>,
    val summary: String,
)

class IntroduceParameterObjectPreparationException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class IntroduceParameterObjectConflictException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
