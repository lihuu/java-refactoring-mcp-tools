package com.example.airefactoring.refactoring.extractinterface

import com.intellij.openapi.project.Project

interface ExtractInterfaceExecutor {
    suspend fun extract(
        project: Project,
        preparation: ExtractInterfacePreparation,
    ): ExtractInterfaceExecutionResult
}

data class ExtractInterfaceExecutionResult(
    val sourceClassQualifiedName: String,
    val interfaceName: String,
    val qualifiedInterfaceName: String,
    val memberNames: List<String>,
    val targetPackage: String?,
    val nativeUsageCount: Int,
    val affectedFiles: List<String>?,
    val summary: String,
)
