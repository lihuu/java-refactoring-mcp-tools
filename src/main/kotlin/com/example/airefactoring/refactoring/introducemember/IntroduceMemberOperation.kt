package com.example.airefactoring.refactoring.introducemember

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.mcp.McpRefactoringResult
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiNameHelper
import java.nio.file.Path
import java.util.concurrent.CancellationException

/**
 * Owns name validation, current-PSI resolution, native execution, and JSON mapping for one
 * Introduce Constant / Introduce Field request. The [profile] fixes every native UI decision;
 * this delegate only wires the fixed profile through to its matching result factory.
 */
internal class IntroduceMemberOperation(
    private val profile: IntroduceMemberProfile,
    private val resolver: IntroduceMemberSelectionResolver = IntroduceMemberSelectionResolver(),
    private val executor: IntroduceMemberExecutor = IntellijIntroduceMemberExecutor(),
) {

    suspend fun execute(
        project: Project,
        pathInProject: String,
        range: SourceRange,
        preferredName: String,
    ): String {
        return try {
            val nameIsValid = smartReadAction(project) {
                PsiNameHelper.getInstance(project).isIdentifier(preferredName)
            }
            if (!nameIsValid) {
                return McpRefactoringResult.failure(
                    McpRefactoringErrorCode.INVALID_FIELD_NAME,
                    "The member name is not a valid Java identifier: $preferredName",
                ).toJson()
            }

            when (
                val resolution = smartReadAction(project) {
                    resolver.resolve(project, pathInProject, range)
                }
            ) {
                is IntroduceMemberSelectionResolution.Failure -> McpRefactoringResult.failure(
                    resolution.code,
                    resolution.message,
                ).toJson()
                is IntroduceMemberSelectionResolution.Success -> {
                    val selection = resolution.selection
                    val absoluteFilePath = smartReadAction(project) {
                        selection.file.virtualFile.path
                    }
                    val result = executor.introduce(
                        project,
                        selection,
                        preferredName,
                        profile,
                    )
                    val basePath = project.basePath ?: ""
                    val filePath = if (basePath.isEmpty()) {
                        absoluteFilePath
                    } else {
                        Path.of(basePath)
                            .toAbsolutePath()
                            .normalize()
                            .relativize(
                                Path.of(absoluteFilePath)
                                    .toAbsolutePath()
                                    .normalize(),
                            )
                            .toString()
                    }
                    memberSuccessFactory(
                        projectBasePath = basePath,
                        filePath = filePath,
                        requestedFieldName = preferredName,
                        actualFieldName = result.actualFieldName,
                        fieldType = result.fieldType,
                        summary = result.summary,
                    ).toJson()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: IntroduceMemberConflictException) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.REFACTORING_CONFLICT,
                e.message ?: "Native Introduce Member declined the refactoring.",
            ).toJson()
        } catch (e: IntroduceMemberPreparationException) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.PREPARE_FAILED,
                e.message ?: "Native Introduce Member refused the expression.",
            ).toJson()
        } catch (e: Exception) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.REFACTORING_FAILED,
                e.message ?: e.javaClass.simpleName,
            ).toJson()
        }
    }

    private fun memberSuccessFactory(
        projectBasePath: String,
        filePath: String,
        requestedFieldName: String,
        actualFieldName: String,
        fieldType: String,
        summary: String,
    ): McpRefactoringResult = when (profile) {
        IntroduceMemberProfile.Constant -> McpRefactoringResult.introduceConstantSuccess(
            projectBasePath = projectBasePath,
            filePath = filePath,
            requestedFieldName = requestedFieldName,
            actualFieldName = actualFieldName,
            fieldType = fieldType,
            summary = summary,
        )
        IntroduceMemberProfile.InstanceFinalField -> McpRefactoringResult.introduceFieldSuccess(
            projectBasePath = projectBasePath,
            filePath = filePath,
            requestedFieldName = requestedFieldName,
            actualFieldName = actualFieldName,
            fieldType = fieldType,
            summary = summary,
        )
    }
}
