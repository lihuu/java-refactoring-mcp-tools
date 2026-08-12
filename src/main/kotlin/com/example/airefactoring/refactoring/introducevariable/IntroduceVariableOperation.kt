package com.example.airefactoring.refactoring.introducevariable

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.mcp.McpRefactoringResult
import com.example.airefactoring.refactoring.SourceRange
import com.example.airefactoring.validator.NameValidator
import com.example.airefactoring.validator.ValidationResult
import com.intellij.openapi.application.EDT
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** Owns validation, current-PSI resolution, native execution, and JSON mapping for one request. */
class IntroduceVariableOperation(
    private val resolver: IntroduceVariableSelectionResolver = IntroduceVariableSelectionResolver(),
    private val nameValidator: NameValidator = NameValidator(),
    private val executor: IntroduceVariableExecutor = IntellijIntroduceVariableExecutor(),
) {

    suspend fun execute(
        project: Project,
        pathInProject: String,
        range: SourceRange,
        preferredVariableName: String,
    ): String = withContext(Dispatchers.EDT) {
        try {
            when (
                val validation = nameValidator.validateVariableName(
                    preferredVariableName,
                    project,
                )
            ) {
                is ValidationResult.Invalid -> return@withContext McpRefactoringResult.failure(
                    McpRefactoringErrorCode.INVALID_VARIABLE_NAME,
                    validation.message,
                ).toJson()
                is ValidationResult.Ok -> Unit
            }

            when (val resolution = resolver.resolve(project, pathInProject, range)) {
                is IntroduceVariableSelectionResolution.Failure -> McpRefactoringResult.failure(
                    resolution.code,
                    resolution.message,
                ).toJson()
                is IntroduceVariableSelectionResolution.Success -> {
                    val selection = resolution.selection
                    val result = executor.introduce(
                        project,
                        selection,
                        preferredVariableName,
                    )
                    val basePath = project.basePath ?: ""
                    val filePath = if (basePath.isEmpty()) {
                        selection.file.virtualFile.path
                    } else {
                        Path.of(basePath)
                            .toAbsolutePath()
                            .normalize()
                            .relativize(
                                Path.of(selection.file.virtualFile.path)
                                    .toAbsolutePath()
                                    .normalize(),
                            )
                            .toString()
                    }
                    McpRefactoringResult.introduceVariableSuccess(
                        projectBasePath = basePath,
                        filePath = filePath,
                        requestedVariableName = preferredVariableName,
                        actualVariableName = result.actualVariableName,
                        variableType = result.variableType,
                        summary = result.summary,
                    ).toJson()
                }
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: IntroduceVariablePreparationException) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.PREPARE_FAILED,
                e.message ?: "Native Introduce Variable refused the expression.",
            ).toJson()
        } catch (e: Exception) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.REFACTORING_FAILED,
                e.message ?: e.javaClass.simpleName,
            ).toJson()
        }
    }
}
