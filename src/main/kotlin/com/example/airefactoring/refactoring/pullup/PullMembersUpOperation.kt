package com.example.airefactoring.refactoring.pullup

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.mcp.McpRefactoringResult
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import java.util.concurrent.CancellationException

class PullMembersUpOperation(
    private val resolver: PullMembersUpSelectionResolver = PullMembersUpSelectionResolver(),
    private val executor: PullMembersUpExecutor = IntellijPullMembersUpExecutor(),
) {
    suspend fun execute(
        project: Project,
        pathInProject: String,
        sourceSubclassStartLine: Int,
        sourceSubclassStartColumn: Int,
        sourceSubclassEndLine: Int,
        sourceSubclassEndColumn: Int,
        memberStartLines: List<Int>,
        memberStartColumns: List<Int>,
        memberEndLines: List<Int>,
        memberEndColumns: List<Int>,
        targetSuperclassFqn: String,
    ): String {
        return try {
            when (
                val resolution = smartReadAction(project) {
                    resolver.resolve(
                        project = project,
                        pathInProject = pathInProject,
                        sourceSubclassStartLine = sourceSubclassStartLine,
                        sourceSubclassStartColumn = sourceSubclassStartColumn,
                        sourceSubclassEndLine = sourceSubclassEndLine,
                        sourceSubclassEndColumn = sourceSubclassEndColumn,
                        memberStartLines = memberStartLines,
                        memberStartColumns = memberStartColumns,
                        memberEndLines = memberEndLines,
                        memberEndColumns = memberEndColumns,
                        targetSuperclassFqn = targetSuperclassFqn,
                    )
                }
            ) {
                is PullMembersUpSelectionResolution.Failure -> McpRefactoringResult.failure(
                    resolution.code,
                    resolution.message,
                ).toJson()
                is PullMembersUpSelectionResolution.Success -> {
                    val preparation = resolution.preparation
                    val result = executor.pull(project, preparation)
                    McpRefactoringResult.pullMembersUpSuccess(
                        projectBasePath = project.basePath ?: "",
                        filePath = preparation.pathInProject,
                        sourceClass = result.sourceClassQualifiedName,
                        targetSuperclass = result.targetSuperclassFqn,
                        memberNames = result.memberNames,
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
        } catch (e: PullMembersUpConflictException) {
            McpRefactoringResult.failure(McpRefactoringErrorCode.REFACTORING_CONFLICT, e.message ?: "Native Pull Up reported a conflict.").toJson()
        } catch (e: PullMembersUpPreparationException) {
            McpRefactoringResult.failure(McpRefactoringErrorCode.PREPARE_FAILED, e.message ?: "Native Pull Up preparation failed.").toJson()
        } catch (e: Exception) {
            McpRefactoringResult.failure(McpRefactoringErrorCode.REFACTORING_FAILED, e.message ?: e.javaClass.simpleName).toJson()
        }
    }
}
