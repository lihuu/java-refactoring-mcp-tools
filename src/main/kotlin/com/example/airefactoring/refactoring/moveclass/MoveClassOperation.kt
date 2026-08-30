package com.example.airefactoring.refactoring.moveclass

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.mcp.McpRefactoringResult
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException

class MoveClassOperation(
    private val resolver: MoveClassSelectionResolver = MoveClassSelectionResolver(),
    private val executor: MoveClassExecutor = IntellijMoveClassExecutor(),
) {
    suspend fun execute(
        project: Project,
        pathInProject: String,
        classRange: SourceRange,
        targetPackage: String,
    ): String {
        val preparation = try {
            val resolution = smartReadAction(project) {
                resolver.resolve(
                    project = project,
                    pathInProject = pathInProject,
                    classRange = classRange,
                    targetPackage = targetPackage,
                )
            }
            when (resolution) {
                is MoveClassSelectionResolution.Success -> resolution.preparation
                is MoveClassSelectionResolution.Failure -> return McpRefactoringResult.failure(resolution.code, resolution.message).toJson()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return McpRefactoringResult.failure(McpRefactoringErrorCode.PREPARE_FAILED, e.message ?: "Prepare failed.").toJson()
        }

        return try {
            val result = executor.move(project, preparation)
            McpRefactoringResult.moveClassSuccess(
                projectBasePath = project.basePath ?: "",
                filePath = pathInProject,
                sourceClass = result.sourceClass,
                targetPackage = result.targetPackage,
                affectedFiles = result.affectedFiles,
                summary = result.summary,
            ).toJson()
        } catch (e: CancellationException) {
            throw e
        } catch (e: MoveClassConflictException) {
            McpRefactoringResult.failure(McpRefactoringErrorCode.REFACTORING_CONFLICT, e.message ?: "Refactoring conflict.").toJson()
        } catch (e: MoveClassPreparationException) {
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
