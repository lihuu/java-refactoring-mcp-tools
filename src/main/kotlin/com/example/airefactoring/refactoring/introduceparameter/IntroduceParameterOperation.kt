package com.example.airefactoring.refactoring.introduceparameter

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.mcp.McpRefactoringResult
import com.example.airefactoring.refactoring.SourceRange
import com.example.airefactoring.validator.NameValidator
import com.example.airefactoring.validator.ValidationResult
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import java.util.concurrent.CancellationException

/**
 * Owns name validation, current-PSI resolution, native execution, and JSON mapping for one
 * Introduce Parameter request. The resolver runs under a smart read; the executor is the only
 * mutator. This delegate never edits text or patches source.
 */
class IntroduceParameterOperation(
    private val resolver: IntroduceParameterSelectionResolver = IntroduceParameterSelectionResolver(),
    private val nameValidator: NameValidator = NameValidator(),
    private val executor: IntroduceParameterExecutor = IntellijIntroduceParameterExecutor(),
) {

    suspend fun execute(
        project: Project,
        pathInProject: String,
        range: SourceRange,
        parameterName: String,
    ): String {
        return try {
            when (
                val validation = smartReadAction(project) {
                    nameValidator.validateVariableName(parameterName, project)
                }
            ) {
                is ValidationResult.Invalid -> return McpRefactoringResult.failure(
                    McpRefactoringErrorCode.INVALID_PARAMETER_NAME,
                    validation.message,
                ).toJson()
                is ValidationResult.Ok -> Unit
            }

            when (
                val resolution = smartReadAction(project) {
                    resolver.resolve(project, pathInProject, range, parameterName)
                }
            ) {
                is IntroduceParameterSelectionResolution.Failure -> McpRefactoringResult.failure(
                    resolution.code,
                    resolution.message,
                ).toJson()
                is IntroduceParameterSelectionResolution.Success -> {
                    val selection = resolution.selection
                    val filePath = selection.sourceDocumentPath
                    val result = executor.introduceParameter(project, selection, parameterName)
                    McpRefactoringResult.introduceParameterSuccess(
                        projectBasePath = project.basePath ?: "",
                        filePath = filePath,
                        methodName = result.methodName,
                        parameterName = result.parameterName,
                        parameterType = result.parameterType,
                        parameterPosition = result.parameterPosition,
                        sourceKind = result.sourceKind.name,
                        updatedCallSiteCount = result.updatedCallSiteCount,
                        affectedFiles = result.affectedFiles,
                        summary = result.summary,
                    ).toJson()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: IntroduceParameterConflictException) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.REFACTORING_CONFLICT,
                e.message ?: "Native Introduce Parameter reported a conflict.",
            ).toJson()
        } catch (e: IntroduceParameterPreparationException) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.PREPARE_FAILED,
                e.message ?: "Native Introduce Parameter refused the request.",
            ).toJson()
        } catch (e: Exception) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.REFACTORING_FAILED,
                e.message ?: e.javaClass.simpleName,
            ).toJson()
        }
    }

}
