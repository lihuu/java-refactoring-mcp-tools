package com.example.airefactoring.refactoring.extractdelegate

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.mcp.McpRefactoringResult
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException

class ExtractDelegateOperation(
    private val resolver: ExtractDelegateSelectionResolver = ExtractDelegateSelectionResolver(),
    private val executor: ExtractDelegateExecutor = IntellijExtractDelegateExecutor(),
) {
    suspend fun execute(
        project: Project,
        pathInProject: String,
        classRange: SourceRange,
        extractedFields: List<String>,
        extractedMethods: List<String>,
        newClassName: String,
        extractInnerClass: Boolean,
    ): String {
        val preparation = try {
            val resolution = smartReadAction(project) {
                resolver.resolve(
                    project = project,
                    pathInProject = pathInProject,
                    classRange = classRange,
                    extractedFields = extractedFields,
                    extractedMethods = extractedMethods,
                    newClassName = newClassName,
                    extractInnerClass = extractInnerClass,
                )
            }
            when (resolution) {
                is ExtractDelegateSelectionResolution.Success -> resolution.preparation
                is ExtractDelegateSelectionResolution.Failure -> return McpRefactoringResult.failure(resolution.code, resolution.message).toJson()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return McpRefactoringResult.failure(McpRefactoringErrorCode.PREPARE_FAILED, e.message ?: "Prepare failed.").toJson()
        }

        return try {
            val result = executor.extract(project, preparation)
            McpRefactoringResult.extractDelegateSuccess(
                projectBasePath = project.basePath ?: "",
                filePath = pathInProject,
                sourceClass = result.sourceClass,
                createdClass = result.createdClass,
                affectedFiles = result.affectedFiles,
                summary = result.summary,
            ).toJson()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExtractDelegateConflictException) {
            McpRefactoringResult.failure(McpRefactoringErrorCode.REFACTORING_CONFLICT, e.message ?: "Refactoring conflict.").toJson()
        } catch (e: ExtractDelegatePreparationException) {
            McpRefactoringResult.failure(McpRefactoringErrorCode.PREPARE_FAILED, e.message ?: "Prepare failed.").toJson()
        } catch (e: Exception) {
            val msg = e.message ?: "Refactoring failed."
            if (msg.contains("conflict", ignoreCase = true)) {
                McpRefactoringResult.failure(McpRefactoringErrorCode.REFACTORING_CONFLICT, msg).toJson()
            } else {
                McpRefactoringResult.failure(McpRefactoringErrorCode.REFACTORING_FAILED, msg).toJson()
            }
        }
    }
}