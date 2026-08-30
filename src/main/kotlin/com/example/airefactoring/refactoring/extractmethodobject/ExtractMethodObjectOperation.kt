package com.example.airefactoring.refactoring.extractmethodobject

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.mcp.McpRefactoringResult
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException

class ExtractMethodObjectOperation(
    private val resolver: ExtractMethodObjectSelectionResolver = ExtractMethodObjectSelectionResolver(),
    private val executor: ExtractMethodObjectExecutor = IntellijExtractMethodObjectExecutor(),
) {
    suspend fun execute(
        project: Project,
        pathInProject: String,
        methodRange: SourceRange,
        methodObjectClassName: String,
        methodObjectMethodName: String,
    ): String {
        val preparation = try {
            val resolution = smartReadAction(project) {
                resolver.resolve(
                    project = project,
                    pathInProject = pathInProject,
                    methodRange = methodRange,
                    methodObjectClassName = methodObjectClassName,
                    methodObjectMethodName = methodObjectMethodName,
                )
            }
            when (resolution) {
                is ExtractMethodObjectSelectionResolution.Success -> resolution.preparation
                is ExtractMethodObjectSelectionResolution.Failure -> return McpRefactoringResult.failure(resolution.code, resolution.message).toJson()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return McpRefactoringResult.failure(McpRefactoringErrorCode.PREPARE_FAILED, e.message ?: "Prepare failed.").toJson()
        }

        return try {
            val result = executor.replace(project, preparation)
            McpRefactoringResult.replaceMethodWithMethodObjectSuccess(
                projectBasePath = project.basePath ?: "",
                filePath = pathInProject,
                methodName = result.methodName,
                methodObjectClass = result.methodObjectClass,
                methodObjectMethodName = result.methodObjectMethodName,
                migratedFieldCount = result.migratedFieldCount,
                affectedFiles = result.affectedFiles,
                summary = result.summary,
            ).toJson()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExtractMethodObjectConflictException) {
            McpRefactoringResult.failure(McpRefactoringErrorCode.REFACTORING_CONFLICT, e.message ?: "Refactoring conflict.").toJson()
        } catch (e: ExtractMethodObjectPreparationException) {
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
