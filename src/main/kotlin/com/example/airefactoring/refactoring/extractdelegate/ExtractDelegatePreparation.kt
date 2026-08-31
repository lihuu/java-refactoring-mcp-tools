package com.example.airefactoring.refactoring.extractdelegate

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.SmartPsiElementPointer

data class ExtractDelegatePreparation(
    val classPointer: SmartPsiElementPointer<PsiClass>,
    val classTextSnapshot: String,
    val sourceClassFqn: String,
    val extractedFields: List<String>,
    val extractedMethods: List<String>,
    val newClassName: String,
    val extractInnerClass: Boolean,
    val affectedVirtualFiles: Set<VirtualFile>,
)

data class ExtractDelegateExecutionResult(
    val sourceClass: String,
    val createdClass: String,
    val affectedFiles: List<String>,
    val summary: String,
)

class ExtractDelegatePreparationException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class ExtractDelegateConflictException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)