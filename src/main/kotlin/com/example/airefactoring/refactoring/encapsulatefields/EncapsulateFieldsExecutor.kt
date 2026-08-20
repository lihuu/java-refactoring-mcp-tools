package com.example.airefactoring.refactoring.encapsulatefields

import com.intellij.openapi.project.Project

interface EncapsulateFieldsExecutor {
    suspend fun encapsulate(
        project: Project,
        preparation: EncapsulateFieldsPreparation,
    ): EncapsulateFieldsExecutionResult
}

data class EncapsulateFieldsExecutionResult(
    val fieldNames: List<String>,
    val getterNames: List<String>,
    val setterNames: List<String>,
    val fieldsVisibility: String?,
    val accessorsVisibility: String,
    val encapsulateGet: Boolean,
    val encapsulateSet: Boolean,
    val useAccessorsWhenAccessible: Boolean,
    val nativeUsageCount: Int,
    val affectedFiles: List<String>?,
    val summary: String,
)
