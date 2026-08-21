package com.example.airefactoring.refactoring.useinterface

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.mcp.McpRefactoringResult
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import java.util.concurrent.CancellationException

class UseInterfaceWherePossibleOperation(
    private val resolver: UseInterfaceWherePossibleSelectionResolver = UseInterfaceWherePossibleSelectionResolver(),
    private val executor: UseInterfaceWherePossibleExecutor = IntellijUseInterfaceWherePossibleExecutor(),
) {
    suspend fun execute(
        project: Project,
        pathInProject: String,
        sourceClassStartLine: Int,
        sourceClassStartColumn: Int,
        sourceClassEndLine: Int,
        sourceClassEndColumn: Int,
        targetInterfaceFqn: String,
    ): String {
        return try {
            when (
                val resolution = smartReadAction(project) {
                    resolver.resolve(
                        project = project,
                        pathInProject = pathInProject,
                        sourceClassStartLine = sourceClassStartLine,
                        sourceClassStartColumn = sourceClassStartColumn,
                        sourceClassEndLine = sourceClassEndLine,
                        sourceClassEndColumn = sourceClassEndColumn,
                        targetInterfaceFqn = targetInterfaceFqn,
                    )
                }
            ) {
                is UseInterfaceWherePossibleSelectionResolution.Failure -> McpRefactoringResult.failure(
                    resolution.code,
                    resolution.message,
                ).toJson()
                is UseInterfaceWherePossibleSelectionResolution.Success -> {
                    val preparation = resolution.preparation
                    val result = executor.useInterface(project, preparation)
                    McpRefactoringResult.useInterfaceWherePossibleSuccess(
                        projectBasePath = project.basePath ?: "",
                        filePath = preparation.pathInProject,
                        sourceClass = result.sourceClassQualifiedName,
                        targetInterface = result.targetInterfaceFqn,
                        nativeUsageCount = result.nativeUsageCount,
                        affectedFiles = result.affectedFiles,
                        renamedVariables = result.renamedVariables,
                        summary = result.summary,
                    ).toJson()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: UseInterfaceConflictException) {
            McpRefactoringResult.failure(McpRefactoringErrorCode.REFACTORING_CONFLICT, e.message ?: "Native Use Interface reported a conflict.").toJson()
        } catch (e: UseInterfaceWherePossiblePreparationException) {
            McpRefactoringResult.failure(McpRefactoringErrorCode.PREPARE_FAILED, e.message ?: "Use Interface preparation failed.").toJson()
        } catch (e: Exception) {
            McpRefactoringResult.failure(McpRefactoringErrorCode.REFACTORING_FAILED, e.message ?: e.javaClass.simpleName).toJson()
        }
    }
}
