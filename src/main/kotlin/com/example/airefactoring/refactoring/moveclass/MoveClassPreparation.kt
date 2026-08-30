package com.example.airefactoring.refactoring.moveclass

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.SmartPsiElementPointer

data class MoveClassPreparation(
    val classPointer: SmartPsiElementPointer<PsiClass>,
    val classTextSnapshot: String,
    val sourceClassFqn: String,
    val targetPackage: String,
    val affectedVirtualFiles: Set<VirtualFile>,
)

data class MoveClassExecutionResult(
    val sourceClass: String,
    val targetPackage: String,
    val affectedFiles: List<String>,
    val summary: String,
)

class MoveClassPreparationException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class MoveClassConflictException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
