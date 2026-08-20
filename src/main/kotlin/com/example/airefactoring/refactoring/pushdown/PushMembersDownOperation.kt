package com.example.airefactoring.refactoring.pushdown

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.mcp.McpRefactoringResult
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import java.util.concurrent.CancellationException

class PushMembersDownOperation(
    private val resolver: PushMembersDownSelectionResolver = PushMembersDownSelectionResolver(),
    private val executor: PushMembersDownExecutor = IntellijPushMembersDownExecutor(),
) {
    suspend fun execute(
        project: Project,
        pathInProject: String,
        sourceSuperclassStartLine: Int,
        sourceSuperclassStartColumn: Int,
        sourceSuperclassEndLine: Int,
        sourceSuperclassEndColumn: Int,
        memberStartLines: List<Int>,
        memberStartColumns: List<Int>,
        memberEndLines: List<Int>,
        memberEndColumns: List<Int>,
        targetSubclassFqns: List<String>,
    ): String {
        return try {
            when (
                val resolution = smartReadAction(project) {
                    resolver.resolve(
                        project = project,
                        pathInProject = pathInProject,
                        sourceSuperclassStartLine = sourceSuperclassStartLine,
                        sourceSuperclassStartColumn = sourceSuperclassStartColumn,
                        sourceSuperclassEndLine = sourceSuperclassEndLine,
                        sourceSuperclassEndColumn = sourceSuperclassEndColumn,
                        memberStartLines = memberStartLines,
                        memberStartColumns = memberStartColumns,
                        memberEndLines = memberEndLines,
                        memberEndColumns = memberEndColumns,
                        targetSubclassFqns = targetSubclassFqns,
                    )
                }
            ) {
                is PushMembersDownSelectionResolution.Failure -> McpRefactoringResult.failure(
                    resolution.code,
                    resolution.message,
                ).toJson()
                is PushMembersDownSelectionResolution.Success -> {
                    val preparation = resolution.preparation
                    val result = executor.push(project, preparation)
                    McpRefactoringResult.pushMembersDownSuccess(
                        projectBasePath = project.basePath ?: "",
                        filePath = preparation.pathInProject,
                        sourceClass = result.sourceClassQualifiedName,
                        targetSubclasses = result.targetSubclassFqns,
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
        } catch (e: PushMembersDownConflictException) {
            McpRefactoringResult.failure(McpRefactoringErrorCode.REFACTORING_CONFLICT, e.message ?: "Native Push Down reported a conflict.").toJson()
        } catch (e: PushMembersDownPreparationException) {
            McpRefactoringResult.failure(McpRefactoringErrorCode.PREPARE_FAILED, e.message ?: "Native Push Down preparation failed.").toJson()
        } catch (e: Exception) {
            McpRefactoringResult.failure(McpRefactoringErrorCode.REFACTORING_FAILED, e.message ?: e.javaClass.simpleName).toJson()
        }
    }
}
