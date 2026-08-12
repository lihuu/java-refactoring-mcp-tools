package com.example.airefactoring.refactoring.changesignature

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.mcp.McpRefactoringResult
import com.example.airefactoring.validator.NameValidator
import com.example.airefactoring.validator.ValidationResult
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import java.util.concurrent.CancellationException

/** Owns validation, current-PSI preparation, native execution, and JSON mapping for one request. */
class ChangeSignatureOperation(
    private val resolver: ChangeSignaturePreparationResolver = ChangeSignaturePreparationResolver(),
    private val nameValidator: NameValidator = NameValidator(),
    private val executor: ChangeSignatureExecutor = IntellijChangeSignatureExecutor(),
) {

    suspend fun execute(
        project: Project,
        pathInProject: String,
        line: Int,
        column: Int,
        parameterName: String,
        parameterType: String,
        parameterPosition: Int,
        defaultCallSiteExpression: String,
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
                    resolver.resolve(
                        project,
                        pathInProject,
                        line,
                        column,
                        parameterName,
                        parameterType,
                        parameterPosition,
                        defaultCallSiteExpression,
                    )
                }
            ) {
                is ChangeSignaturePreparationResolution.Failure -> McpRefactoringResult.failure(
                    resolution.code,
                    resolution.message,
                ).toJson()
                is ChangeSignaturePreparationResolution.Success -> {
                    val result = executor.addParameter(project, resolution.preparation)
                    McpRefactoringResult.changeSignatureAddParameterSuccess(
                        projectBasePath = project.basePath ?: "",
                        filePath = result.declarationFilePath,
                        methodName = result.methodName,
                        parameterName = result.parameterName,
                        parameterType = result.parameterType,
                        parameterPosition = result.parameterPosition,
                        defaultCallSiteExpression = result.defaultCallSiteExpression,
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
        } catch (e: ChangeSignatureConflictException) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.REFACTORING_CONFLICT,
                e.message ?: "Native Change Signature reported a conflict.",
            ).toJson()
        } catch (e: ChangeSignaturePreparationException) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.PREPARE_FAILED,
                e.message ?: "Native Change Signature refused the request.",
            ).toJson()
        } catch (e: Exception) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.REFACTORING_FAILED,
                e.message ?: e.javaClass.simpleName,
            ).toJson()
        }
    }
}
