package com.example.airefactoring.refactoring.replaceinheritance

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.mcp.McpRefactoringResult
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException

class ReplaceInheritanceWithDelegationOperation(
    private val resolverFactory: (Project) -> ReplaceInheritanceWithDelegationSelectionResolver = ::ReplaceInheritanceWithDelegationSelectionResolver,
    private val executor: ReplaceInheritanceWithDelegationExecutor = IntellijReplaceInheritanceExecutor(),
) {
    suspend fun execute(
        project: Project,
        pathInProject: String,
        classRange: SourceRange,
        targetBaseClassFqn: String,
        fieldName: String,
        delegateOtherMembers: Boolean,
        generateGetter: Boolean,
    ): String {
        val preparation = try {
            smartReadAction(project) {
                resolverFactory(project).resolve(
                    pathInProject = pathInProject,
                    startLine = classRange.startLine,
                    startColumn = classRange.startColumn,
                    endLine = classRange.endLine,
                    endColumn = classRange.endColumn,
                    targetBaseClassFqn = targetBaseClassFqn,
                    fieldName = fieldName,
                    delegateOtherMembers = delegateOtherMembers,
                    generateGetter = generateGetter,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return McpRefactoringResult.failure(McpRefactoringErrorCode.PREPARE_FAILED, e.message ?: "Prepare failed.").toJson()
        }

        return try {
            val result = executor.execute(project, preparation)
            McpRefactoringResult.replaceInheritanceWithDelegationSuccess(
                projectBasePath = project.basePath ?: "",
                filePath = pathInProject,
                sourceClass = result.sourceClass,
                targetSuperclass = preparation.targetBaseClassFqn,
                delegateFieldName = preparation.fieldName,
                affectedFiles = result.affectedFiles,
                summary = result.summary,
            ).toJson()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ReplaceInheritanceConflictException) {
            McpRefactoringResult.failure(McpRefactoringErrorCode.REFACTORING_CONFLICT, e.message ?: "Refactoring conflict.").toJson()
        } catch (e: ReplaceInheritancePreparationException) {
            McpRefactoringResult.failure(McpRefactoringErrorCode.PREPARE_FAILED, e.message ?: "Preparation failed.").toJson()
        } catch (e: Exception) {
            McpRefactoringResult.failure(McpRefactoringErrorCode.REFACTORING_FAILED, e.message ?: "Refactoring failed.").toJson()
        }
    }
}
