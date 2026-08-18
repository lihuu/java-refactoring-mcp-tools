package com.example.airefactoring.refactoring.makestatic

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.mcp.McpRefactoringResult
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import java.util.concurrent.CancellationException

/**
 * Owns current-PSI resolution, native execution, and JSON mapping for one Java Make Static request.
 * The resolver runs under a smart read; the executor is the only mutator. This delegate never edits
 * text or patches source.
 */
class JavaMakeStaticOperation(
    private val resolver: JavaMakeStaticSelectionResolver = JavaMakeStaticSelectionResolver(),
    private val executor: JavaMakeStaticExecutor = IntellijJavaMakeStaticExecutor(),
) {

    suspend fun execute(
        project: Project,
        pathInProject: String,
        memberRange: SourceRange,
        replaceUsages: Boolean,
        classParameterName: String?,
        fieldStartLines: List<Int>,
        fieldStartColumns: List<Int>,
        fieldEndLines: List<Int>,
        fieldEndColumns: List<Int>,
        fieldParameterNames: List<String>,
        generateDelegate: Boolean,
    ): String = try {
        execute(
            project = project,
            pathInProject = pathInProject,
            memberRange = memberRange,
            replaceUsages = replaceUsages,
            classParameterName = classParameterName,
            fieldParameters = JavaMakeStaticFieldParameter.fromParallelLists(
                fieldStartLines,
                fieldStartColumns,
                fieldEndLines,
                fieldEndColumns,
                fieldParameterNames,
            ),
            generateDelegate = generateDelegate,
        )
    } catch (e: JavaMakeStaticFieldParameterEncodingException) {
        McpRefactoringResult.failure(
            McpRefactoringErrorCode.INVALID_RANGE,
            e.message ?: "Malformed Java Make Static field parameter lists.",
        ).toJson()
    }

    suspend fun execute(
        project: Project,
        pathInProject: String,
        memberRange: SourceRange,
        replaceUsages: Boolean,
        classParameterName: String?,
        fieldParameters: List<JavaMakeStaticFieldParameter>,
        generateDelegate: Boolean,
    ): String {
        return try {
            when (
                val resolution = smartReadAction(project) {
                    resolver.resolve(
                        project,
                        pathInProject,
                        memberRange,
                        replaceUsages,
                        classParameterName,
                        fieldParameters,
                        generateDelegate,
                    )
                }
            ) {
                is JavaMakeStaticSelectionResolution.Failure -> McpRefactoringResult.failure(
                    resolution.code,
                    resolution.message,
                ).toJson()
                is JavaMakeStaticSelectionResolution.Success -> {
                    val preparation = resolution.preparation
                    val result = executor.makeStatic(project, preparation)
                    McpRefactoringResult.makeStaticSuccess(
                        projectBasePath = project.basePath ?: "",
                        filePath = preparation.pathInProject,
                        memberName = result.memberName,
                        memberKind = result.memberKind,
                        replaceUsages = result.replaceUsages,
                        classParameterName = result.classParameterName,
                        fieldParameterNames = result.fieldParameterNames,
                        generateDelegate = result.generateDelegate,
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
        } catch (e: JavaMakeStaticConflictException) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.REFACTORING_CONFLICT,
                e.message ?: "Native Java Make Static reported a conflict.",
            ).toJson()
        } catch (e: JavaMakeStaticPreparationException) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.PREPARE_FAILED,
                e.message ?: "Native Java Make Static refused the request.",
            ).toJson()
        } catch (e: Exception) {
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.REFACTORING_FAILED,
                e.message ?: e.javaClass.simpleName,
            ).toJson()
        }
    }
}
