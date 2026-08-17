package com.example.airefactoring.refactoring.safedelete

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.mcp.McpRefactoringResult
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import java.util.concurrent.CancellationException

/**
 * Owns current-PSI resolution, native execution, and JSON mapping for one Safe Delete request.
 * The resolver runs under a smart read; the executor is the only mutator. This delegate never
 * edits text or patches source.
 */
class JavaSafeDeleteOperation(
    private val resolver: JavaSafeDeleteTargetResolver = JavaSafeDeleteTargetResolver(),
    private val executor: SafeDeleteExecutor = IntellijSafeDeleteExecutor(),
) {

    suspend fun execute(
        project: Project,
        pathInProject: String,
        range: SourceRange,
    ): String {
        return try {
            when (
                val resolution = smartReadAction(project) {
                    resolver.resolve(project, pathInProject, range)
                }
            ) {
                is SafeDeleteTargetResolution.Failure -> McpRefactoringResult.failure(
                    resolution.code,
                    resolution.message,
                ).toJson()
                is SafeDeleteTargetResolution.Success -> {
                    val preparation = resolution.preparation
                    val result = executor.delete(project, preparation)
                    McpRefactoringResult.safeDeleteSuccess(
                        projectBasePath = project.basePath ?: "",
                        filePath = preparation.sourceDocumentPath,
                        targetDescription = result.targetDescription,
                        nativeUsageCount = result.nativeUsageCount,
                        summary = result.summary,
                    ).toJson()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: SafeDeleteConflictException) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.REFACTORING_CONFLICT,
                e.message ?: "Native Safe Delete reported a conflict.",
            ).toJson()
        } catch (e: SafeDeletePreparationException) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.PREPARE_FAILED,
                e.message ?: "Native Safe Delete refused the request.",
            ).toJson()
        } catch (e: Exception) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.REFACTORING_FAILED,
                e.message ?: e.javaClass.simpleName,
            ).toJson()
        }
    }

}
