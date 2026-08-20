package com.example.airefactoring.refactoring.extractinterface

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.mcp.McpRefactoringResult
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import java.util.concurrent.CancellationException

class ExtractInterfaceOperation(
    private val resolver: ExtractInterfaceSelectionResolver = ExtractInterfaceSelectionResolver(),
    private val executor: ExtractInterfaceExecutor = IntellijExtractInterfaceExecutor(),
) {

    suspend fun execute(
        project: Project,
        pathInProject: String,
        sourceClassStartLine: Int,
        sourceClassStartColumn: Int,
        sourceClassEndLine: Int,
        sourceClassEndColumn: Int,
        memberStartLines: List<Int>,
        memberStartColumns: List<Int>,
        memberEndLines: List<Int>,
        memberEndColumns: List<Int>,
        interfaceName: String,
        targetPackage: String?,
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
                        memberStartLines = memberStartLines,
                        memberStartColumns = memberStartColumns,
                        memberEndLines = memberEndLines,
                        memberEndColumns = memberEndColumns,
                        interfaceName = interfaceName,
                        targetPackage = targetPackage,
                    )
                }
            ) {
                is ExtractInterfaceSelectionResolution.Failure -> McpRefactoringResult.failure(
                    resolution.code,
                    resolution.message,
                ).toJson()
                is ExtractInterfaceSelectionResolution.Success -> {
                    val preparation = resolution.preparation
                    val result = executor.extract(project, preparation)
                    McpRefactoringResult.extractInterfaceSuccess(
                        projectBasePath = project.basePath ?: "",
                        filePath = preparation.pathInProject,
                        sourceClass = result.sourceClassQualifiedName,
                        interfaceName = result.interfaceName,
                        qualifiedInterfaceName = result.qualifiedInterfaceName,
                        memberNames = result.memberNames,
                        targetPackage = result.targetPackage,
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
        } catch (e: ExtractInterfaceConflictException) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.REFACTORING_CONFLICT,
                e.message ?: "Native Extract Interface reported a conflict.",
            ).toJson()
        } catch (e: ExtractInterfacePreparationException) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.PREPARE_FAILED,
                e.message ?: "Native Extract Interface preparation failed.",
            ).toJson()
        } catch (e: Exception) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.REFACTORING_FAILED,
                e.message ?: e.javaClass.simpleName,
            ).toJson()
        }
    }
}
