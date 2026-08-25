package com.example.airefactoring.refactoring.introduceparameterobject

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.mcp.McpRefactoringResult
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException

class IntroduceParameterObjectOperation(
    private val resolver: IntroduceParameterObjectSelectionResolver = IntroduceParameterObjectSelectionResolver(),
    private val executor: IntroduceParameterObjectExecutor = IntellijIntroduceParameterObjectExecutor(),
) {
    suspend fun execute(
        project: Project,
        pathInProject: String,
        methodRange: SourceRange,
        parameterNames: List<String>,
        placement: String,
        className: String?,
        targetPackage: String?,
        existingClassFqn: String?,
        generateAccessors: Boolean,
        escalateVisibility: Boolean,
    ): String {
        val preparation = try {
            val resolution = smartReadAction(project) {
                resolver.resolve(
                    project = project,
                    pathInProject = pathInProject,
                    methodRange = methodRange,
                    parameterNames = parameterNames,
                    placement = placement,
                    className = className,
                    targetPackage = targetPackage,
                    existingClassFqn = existingClassFqn,
                    generateAccessors = generateAccessors,
                    escalateVisibility = escalateVisibility,
                )
            }
            when (resolution) {
                is IntroduceParameterObjectSelectionResolution.Success -> resolution.preparation
                is IntroduceParameterObjectSelectionResolution.Failure -> return McpRefactoringResult.failure(resolution.code, resolution.message).toJson()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return McpRefactoringResult.failure(McpRefactoringErrorCode.PREPARE_FAILED, e.message ?: "Prepare failed.").toJson()
        }

        return try {
            val result = executor.introduce(project, preparation)
            McpRefactoringResult.introduceParameterObjectSuccess(
                projectBasePath = project.basePath ?: "",
                filePath = pathInProject,
                methodName = result.methodName,
                parameterObjectClass = result.parameterObjectClass,
                placement = result.placement,
                mergedParameterCount = result.mergedParameterCount,
                nativeUsageCount = result.nativeUsageCount,
                affectedFiles = result.affectedFiles,
                summary = result.summary,
            ).toJson()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IntroduceParameterObjectConflictException) {
            McpRefactoringResult.failure(McpRefactoringErrorCode.REFACTORING_CONFLICT, e.message ?: "Refactoring conflict.").toJson()
        } catch (e: IntroduceParameterObjectPreparationException) {
            McpRefactoringResult.failure(McpRefactoringErrorCode.PREPARE_FAILED, e.message ?: "Prepare failed.").toJson()
        } catch (e: Exception) {
            val msg = e.message ?: "Refactoring failed."
            if (msg.contains("conflict", ignoreCase = true)) {
                McpRefactoringResult.failure(McpRefactoringErrorCode.REFACTORING_CONFLICT, msg).toJson()
            } else {
                McpRefactoringResult.failure(McpRefactoringErrorCode.REFACTORING_FAILED, msg).toJson()
            }
        }
    }
}
