package com.example.airefactoring.refactoring.encapsulatefields

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.mcp.McpRefactoringResult
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import java.util.concurrent.CancellationException

class EncapsulateFieldsOperation(
    private val resolver: EncapsulateFieldsSelectionResolver = EncapsulateFieldsSelectionResolver(),
    private val executor: EncapsulateFieldsExecutor = IntellijEncapsulateFieldsExecutor(),
) {

    suspend fun execute(
        project: Project,
        pathInProject: String,
        fieldStartLines: List<Int>,
        fieldStartColumns: List<Int>,
        fieldEndLines: List<Int>,
        fieldEndColumns: List<Int>,
        getterNames: List<String>,
        setterNames: List<String>,
        fieldsVisibility: String?,
        accessorsVisibility: String,
        encapsulateGet: Boolean,
        encapsulateSet: Boolean,
        useAccessorsWhenAccessible: Boolean,
    ): String {
        return try {
            when (
                val resolution = smartReadAction(project) {
                    resolver.resolve(
                        project = project,
                        pathInProject = pathInProject,
                        fieldStartLines = fieldStartLines,
                        fieldStartColumns = fieldStartColumns,
                        fieldEndLines = fieldEndLines,
                        fieldEndColumns = fieldEndColumns,
                        getterNames = getterNames,
                        setterNames = setterNames,
                        fieldsVisibility = fieldsVisibility,
                        accessorsVisibility = accessorsVisibility,
                        encapsulateGet = encapsulateGet,
                        encapsulateSet = encapsulateSet,
                        useAccessorsWhenAccessible = useAccessorsWhenAccessible,
                    )
                }
            ) {
                is EncapsulateFieldsSelectionResolution.Failure -> McpRefactoringResult.failure(
                    resolution.code,
                    resolution.message,
                ).toJson()
                is EncapsulateFieldsSelectionResolution.Success -> {
                    val preparation = resolution.preparation
                    val result = executor.encapsulate(project, preparation)
                    McpRefactoringResult.encapsulateFieldsSuccess(
                        projectBasePath = project.basePath ?: "",
                        filePath = preparation.pathInProject,
                        fieldNames = result.fieldNames,
                        getterNames = result.getterNames,
                        setterNames = result.setterNames,
                        fieldsVisibility = result.fieldsVisibility,
                        accessorsVisibility = result.accessorsVisibility,
                        encapsulateGet = result.encapsulateGet,
                        encapsulateSet = result.encapsulateSet,
                        useAccessorsWhenAccessible = result.useAccessorsWhenAccessible,
                        nativeUsageCount = result.nativeUsageCount,
                        affectedFiles = result.affectedFiles,
                        summary = result.summary,
                    ).toJson()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: EncapsulateFieldsConflictException) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.REFACTORING_CONFLICT,
                e.message ?: "Native Encapsulate Fields reported a conflict.",
            ).toJson()
        } catch (e: EncapsulateFieldsPreparationException) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.PREPARE_FAILED,
                e.message ?: "Native Encapsulate Fields preparation failed.",
            ).toJson()
        } catch (e: Exception) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.REFACTORING_FAILED,
                e.message ?: e.javaClass.simpleName,
            ).toJson()
        }
    }
}
