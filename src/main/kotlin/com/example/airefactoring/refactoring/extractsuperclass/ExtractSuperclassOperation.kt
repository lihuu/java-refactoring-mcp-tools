package com.example.airefactoring.refactoring.extractsuperclass

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.mcp.McpRefactoringResult
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import java.util.concurrent.CancellationException

class ExtractSuperclassOperation(
    private val resolver: ExtractSuperclassSelectionResolver = ExtractSuperclassSelectionResolver(),
    private val executor: ExtractSuperclassExecutor = IntellijExtractSuperclassExecutor(),
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
        superclassName: String,
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
                        superclassName = superclassName,
                        targetPackage = targetPackage,
                    )
                }
            ) {
                is ExtractSuperclassSelectionResolution.Failure -> McpRefactoringResult.failure(
                    resolution.code,
                    resolution.message,
                ).toJson()
                is ExtractSuperclassSelectionResolution.Success -> {
                    val preparation = resolution.preparation
                    val result = executor.extract(project, preparation)
                    McpRefactoringResult.extractSuperclassSuccess(
                        projectBasePath = project.basePath ?: "",
                        filePath = preparation.pathInProject,
                        sourceClass = result.sourceClassQualifiedName,
                        superclassName = result.superclassName,
                        qualifiedSuperclassName = result.qualifiedSuperclassName,
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
        } catch (e: ExtractSuperclassConflictException) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.REFACTORING_CONFLICT,
                e.message ?: "Native Extract Superclass reported a conflict.",
            ).toJson()
        } catch (e: ExtractSuperclassPreparationException) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.PREPARE_FAILED,
                e.message ?: "Native Extract Superclass preparation failed.",
            ).toJson()
        } catch (e: Exception) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.REFACTORING_FAILED,
                e.message ?: e.javaClass.simpleName,
            ).toJson()
        }
    }
}
