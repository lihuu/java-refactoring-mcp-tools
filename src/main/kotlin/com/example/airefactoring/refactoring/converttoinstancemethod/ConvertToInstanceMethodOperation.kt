package com.example.airefactoring.refactoring.converttoinstancemethod

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.mcp.McpRefactoringResult
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import java.util.concurrent.CancellationException

class ConvertToInstanceMethodOperation(
    private val resolver: ConvertToInstanceMethodSelectionResolver = ConvertToInstanceMethodSelectionResolver(),
    private val executor: ConvertToInstanceMethodExecutor = IntellijConvertToInstanceMethodExecutor(),
) {
    suspend fun execute(
        project: Project,
        pathInProject: String,
        methodRange: SourceRange,
        targetKind: String,
        targetRange: SourceRange?,
        newVisibility: String?,
        confirmInterfaceImplementations: Boolean,
    ): String {
        return try {
            when (
                val resolution = smartReadAction(project) {
                    resolver.resolve(
                        project,
                        pathInProject,
                        methodRange,
                        targetKind,
                        targetRange,
                        newVisibility,
                        confirmInterfaceImplementations,
                    )
                }
            ) {
                is ConvertToInstanceMethodSelectionResolution.Failure -> McpRefactoringResult.failure(
                    resolution.code,
                    resolution.message,
                ).toJson()
                is ConvertToInstanceMethodSelectionResolution.Success -> {
                    val preparation = resolution.preparation
                    val result = executor.convert(project, preparation)
                    McpRefactoringResult.convertToInstanceMethodSuccess(
                        projectBasePath = project.basePath ?: "",
                        filePath = preparation.pathInProject,
                        methodName = result.methodName,
                        targetKind = result.targetKind,
                        targetDescription = result.targetDescription,
                        targetClassQualifiedName = result.targetClassQualifiedName,
                        newVisibility = result.newVisibility,
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
        } catch (e: ConvertToInstanceMethodConflictException) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.REFACTORING_CONFLICT,
                e.message ?: "Native Convert to Instance Method reported a conflict.",
            ).toJson()
        } catch (e: ConvertToInstanceMethodPreparationException) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.PREPARE_FAILED,
                e.message ?: "Native Convert to Instance Method refused the request.",
            ).toJson()
        } catch (e: Exception) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.REFACTORING_FAILED,
                e.message ?: e.javaClass.simpleName,
            ).toJson()
        }
    }
}
