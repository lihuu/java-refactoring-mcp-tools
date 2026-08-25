package com.example.airefactoring.refactoring.inlinemethod

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.mcp.McpRefactoringResult
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import java.util.concurrent.CancellationException

/** Owns resolution, native execution, and stable MCP result mapping for Inline Method. */
class InlineMethodOperation(
    private val resolver: InlineMethodSelectionResolver = InlineMethodSelectionResolver(),
    private val executor: InlineMethodExecutor = IntellijInlineMethodExecutor(),
) {
    suspend fun execute(project: Project, pathInProject: String, methodRange: SourceRange): String = try {
        when (val resolution = smartReadAction(project) { resolver.resolve(project, pathInProject, methodRange) }) {
            is InlineMethodSelectionResolution.Failure -> McpRefactoringResult.failure(
                resolution.code, resolution.message,
            ).toJson()
            is InlineMethodSelectionResolution.Success -> {
                val result = executor.inline(project, resolution.preparation)
                McpRefactoringResult.inlineMethodSuccess(
                    projectBasePath = project.basePath ?: "",
                    filePath = pathInProject,
                    methodName = result.methodName,
                    inlinedOccurrenceCount = result.inlinedOccurrenceCount,
                    affectedFiles = result.affectedFiles,
                    summary = result.summary,
                ).toJson()
            }
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: ProcessCanceledException) {
        throw exception
    } catch (exception: InlineMethodConflictException) {
        McpRefactoringResult.failure(McpRefactoringErrorCode.REFACTORING_CONFLICT, exception.message ?: "Native Inline Method reported a conflict.").toJson()
    } catch (exception: InlineMethodPreparationException) {
        McpRefactoringResult.failure(McpRefactoringErrorCode.PREPARE_FAILED, exception.message ?: "Native Inline Method preparation failed.").toJson()
    } catch (exception: Exception) {
        McpRefactoringResult.failure(McpRefactoringErrorCode.REFACTORING_FAILED, exception.message ?: exception.javaClass.simpleName).toJson()
    }
}
