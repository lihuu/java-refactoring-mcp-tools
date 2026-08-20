package com.example.airefactoring.refactoring.extractsuperclass

import com.intellij.openapi.project.Project

interface ExtractSuperclassExecutor {
    suspend fun extract(
        project: Project,
        preparation: ExtractSuperclassPreparation,
    ): ExtractSuperclassExecutionResult
}

data class ExtractSuperclassExecutionResult(
    val sourceClassQualifiedName: String,
    val superclassName: String,
    val qualifiedSuperclassName: String,
    val memberNames: List<String>,
    val targetPackage: String?,
    val nativeUsageCount: Int,
    val affectedFiles: List<String>?,
    val summary: String,
)
