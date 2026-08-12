package com.example.airefactoring.refactoring.extractmethod

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

/** Owns the complete request lifecycle for one native Java Extract Method operation. */
class ExtractMethodOperation(
    private val resolver: ExtractMethodSelectionResolver = ExtractMethodSelectionResolver(),
    private val nameValidator: NameValidator = NameValidator(),
    private val executor: ExtractMethodExecutor = IntellijExtractMethodExecutor(),
) {

    suspend fun execute(
        project: Project,
        pathInProject: String,
        range: SourceRange,
        methodName: String,
    ): String = withContext(Dispatchers.EDT) {
        try {
            when (val validation = nameValidator.validateMethodName(methodName, project)) {
                is ValidationResult.Invalid -> return@withContext McpRefactoringResult
                    .failure(McpRefactoringErrorCode.INVALID_METHOD_NAME, validation.message)
                    .toJson()
                is ValidationResult.Ok -> Unit
            }
            val trimmedName = methodName.trim()
            when (val resolution = resolver.resolve(project, pathInProject, range)) {
                is SelectionResolution.Failure -> McpRefactoringResult
                    .failure(resolution.code, resolution.message)
                    .toJson()
                is SelectionResolution.Success -> {
                    val selection = resolution.selection
                    val summary = executor.extract(
                        project,
                        selection.file,
                        selection.elements,
                        trimmedName,
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
                    McpRefactoringResult.extractMethodSuccess(
                        projectBasePath = basePath,
                        filePath = filePath,
                        methodName = trimmedName,
                        summary = summary,
                    ).toJson()
                }
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: ExtractMethodPreparationException) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.PREPARE_FAILED,
                e.message ?: "Native Extract Method refused the selection.",
            ).toJson()
        } catch (e: Exception) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.REFACTORING_FAILED,
                e.message ?: e.javaClass.simpleName,
            ).toJson()
        }
    }
}
