package com.example.airefactoring.refactoring.inlinevariable

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.mcp.McpRefactoringResult
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** Owns current-PSI resolution, native execution, and JSON mapping for one request. */
class InlineVariableOperation(
    private val resolver: InlineVariableSelectionResolver = InlineVariableSelectionResolver(),
    private val executor: InlineVariableExecutor = IntellijInlineVariableExecutor(),
) {

    suspend fun execute(
        project: Project,
        pathInProject: String,
        line: Int,
        column: Int,
    ): String = try {
        when (
            val resolution = smartReadAction(project) {
                resolver.resolve(project, pathInProject, line, column)
            }
        ) {
            is InlineVariableSelectionResolution.Failure -> McpRefactoringResult.failure(
                resolution.code,
                resolution.message,
            ).toJson()
            is InlineVariableSelectionResolution.Success -> {
                val selection = resolution.selection
                val absoluteFilePath = smartReadAction(project) {
                    selection.file.virtualFile.path
                }
                val basePath = project.basePath ?: ""
                val filePath = if (basePath.isEmpty()) {
                    absoluteFilePath
                } else {
                    Path.of(basePath)
                        .toAbsolutePath()
                        .normalize()
                        .relativize(
                            Path.of(absoluteFilePath)
                                .toAbsolutePath()
                                .normalize(),
                        )
                        .toString()
                }
                val result = executor.inline(project, selection)
                McpRefactoringResult.inlineVariableSuccess(
                    projectBasePath = basePath,
                    filePath = filePath,
                    variableName = result.variableName,
                    inlinedOccurrenceCount = result.inlinedOccurrenceCount,
                    summary = result.summary,
                ).toJson()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: InlineVariableConflictException) {
        McpRefactoringResult.failure(
            McpRefactoringErrorCode.REFACTORING_CONFLICT,
            e.message ?: "Native Inline Variable reported a conflict.",
        ).toJson()
    } catch (e: InlineVariablePreparationException) {
        McpRefactoringResult.failure(
            McpRefactoringErrorCode.PREPARE_FAILED,
            e.message ?: "Native Inline Variable refused the request.",
        ).toJson()
    } catch (e: Exception) {
        McpRefactoringResult.failure(
            McpRefactoringErrorCode.REFACTORING_FAILED,
            e.message ?: e.javaClass.simpleName,
        ).toJson()
    }
}
