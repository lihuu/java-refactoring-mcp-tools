package com.example.airefactoring.refactoring.extractmethodobject

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.SmartPsiElementPointer

data class ExtractMethodObjectPreparation(
    val methodPointer: SmartPsiElementPointer<PsiMethod>,
    val methodTextSnapshot: String,
    val methodObjectClassName: String,
    val methodObjectMethodName: String,
    val affectedVirtualFiles: Set<VirtualFile>,
)

data class ExtractMethodObjectExecutionResult(
    val methodName: String,
    val methodObjectClass: String,
    val methodObjectMethodName: String,
    val migratedFieldCount: Int,
    val affectedFiles: List<String>,
    val summary: String,
)

class ExtractMethodObjectPreparationException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class ExtractMethodObjectConflictException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
