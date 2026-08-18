package com.example.airefactoring.refactoring.moveinstancemethod

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.mcp.McpRefactoringResult
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import java.util.concurrent.CancellationException

/**
 * Owns current-PSI resolution, native execution, and JSON mapping for one Move Instance Method
 * request. The resolver runs under a smart read; the executor is the only mutator. This delegate
 * never edits text or patches source.
 */
class MoveInstanceMethodOperation(
    private val resolver: MoveInstanceMethodSelectionResolver = MoveInstanceMethodSelectionResolver(),
    private val executor: MoveInstanceMethodExecutor = IntellijMoveInstanceMethodExecutor(),
) {

    suspend fun execute(
        project: Project,
        pathInProject: String,
        methodRange: SourceRange,
        targetRange: SourceRange,
        newVisibility: String,
    ): String {
        return try {
            when (
                val resolution = smartReadAction(project) {
                    resolver.resolve(
                        project,
                        pathInProject,
                        methodRange,
                        targetRange,
                        newVisibility,
                    )
                }
            ) {
                is MoveInstanceMethodSelectionResolution.Failure -> McpRefactoringResult.failure(
                    resolution.code,
                    resolution.message,
                ).toJson()
                is MoveInstanceMethodSelectionResolution.Success -> {
                    val preparation = resolution.preparation
                    val result = executor.move(project, preparation)
                    McpRefactoringResult.moveInstanceMethodSuccess(
                        projectBasePath = project.basePath ?: "",
                        filePath = preparation.pathInProject,
                        methodName = result.methodName,
                        targetDescription = result.targetDescription,
                        targetClassQualifiedName = result.targetClassQualifiedName,
                        newVisibility = result.newVisibility,
                        updatedCallSiteCount = result.updatedCallSiteCount,
                        summary = result.summary,
                    ).toJson()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: MoveInstanceMethodConflictException) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.REFACTORING_CONFLICT,
                e.message ?: "Native Move Instance Method reported a conflict.",
            ).toJson()
        } catch (e: MoveInstanceMethodPreparationException) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.PREPARE_FAILED,
                e.message ?: "Native Move Instance Method refused the request.",
            ).toJson()
        } catch (e: Exception) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.REFACTORING_FAILED,
                e.message ?: e.javaClass.simpleName,
            ).toJson()
        }
    }
}
