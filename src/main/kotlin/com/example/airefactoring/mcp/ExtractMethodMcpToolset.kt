package com.example.airefactoring.mcp

import com.example.airefactoring.refactoring.extractmethod.ExtractMethodExecutor
import com.example.airefactoring.refactoring.extractmethod.ExtractMethodPreparationException
import com.example.airefactoring.refactoring.extractmethod.ExtractMethodSelectionResolver
import com.example.airefactoring.refactoring.extractmethod.IntellijExtractMethodExecutor
import com.example.airefactoring.refactoring.extractmethod.SelectionResolution
import com.example.airefactoring.refactoring.extractmethod.SourceRange
import com.example.airefactoring.validator.NameValidator
import com.example.airefactoring.validator.ValidationResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Exposes the plugin's native Java Extract Method refactoring to the built-in IntelliJ MCP Server.
 *
 * The MCP host routes the resolved [Project] through the coroutine context ([currentCoroutineContext]
 * extension [com.intellij.mcpserver.project]); the tool method only declares the six client-facing
 * arguments. All document and refactoring interaction happens on the EDT inside [execute], which is
 * the internal seam the tests drive with a project fixture.
 */
class ExtractMethodMcpToolset(
    private val resolver: ExtractMethodSelectionResolver = ExtractMethodSelectionResolver(),
    private val nameValidator: NameValidator = NameValidator(),
    private val executor: ExtractMethodExecutor = IntellijExtractMethodExecutor(),
) : McpToolset {

    @McpTool
    @McpDescription(TOOL_DESCRIPTION)
    suspend fun java_extract_method(
        @McpDescription("Java file path relative to the project root") pathInProject: String,
        @McpDescription("1-based inclusive start line") startLine: Int,
        @McpDescription("1-based inclusive start column") startColumn: Int,
        @McpDescription("1-based line containing the exclusive end position") endLine: Int,
        @McpDescription("1-based exclusive end column") endColumn: Int,
        @McpDescription("New lower-camel-case Java method name") methodName: String,
    ): String {
        val project = currentCoroutineContext().project
        return execute(
            project,
            pathInProject,
            SourceRange(startLine, startColumn, endLine, endColumn),
            methodName,
        )
    }

    /**
     * The testable execution seam. Runs the full request lifecycle on the EDT: validate the method
     * name first, then resolve the current PSI, then execute the native refactoring. Expected
     * failures map to [McpRefactoringResult]; [ProcessCanceledException] always escapes.
     */
    internal suspend fun execute(
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
                is ValidationResult.Ok -> { /* continue */ }
            }
            val trimmedName = methodName.trim()
            when (val resolution = resolver.resolve(project, pathInProject, range)) {
                is SelectionResolution.Failure -> return@withContext McpRefactoringResult
                    .failure(resolution.code, resolution.message)
                    .toJson()
                is SelectionResolution.Success -> {
                    val selection = resolution.selection
                    val summary = executor.extract(project, selection.file, selection.elements, trimmedName)
                    val baseDir = project.baseDir
                    val filePath = baseDir
                        ?.let { VfsUtilCore.getRelativePath(selection.file.virtualFile, it) }
                        ?: selection.file.virtualFile.path
                    McpRefactoringResult
                        .success(
                            projectBasePath = baseDir?.path ?: "",
                            filePath = filePath,
                            methodName = trimmedName,
                            summary = summary,
                        )
                        .toJson()
                }
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: ExtractMethodPreparationException) {
            McpRefactoringResult
                .failure(McpRefactoringErrorCode.PREPARE_FAILED, e.message ?: "Native Extract Method refused the selection.")
                .toJson()
        } catch (e: Exception) {
            McpRefactoringResult
                .failure(McpRefactoringErrorCode.REFACTORING_FAILED, e.message ?: e.javaClass.simpleName)
                .toJson()
        }
    }

    private companion object {
        const val TOOL_DESCRIPTION =
            "Extracts a Java expression or statement block into a new method using IntelliJ's " +
                "native Extract Method refactoring. Use it to split a complex or long Java method " +
                "into smaller, well-named methods: present the proposed decomposition and wait for " +
                "user approval before calling, call once per extracted method, then re-read the " +
                "modified file before computing the next source range because line and column " +
                "positions change after every extraction. Never implement Extract Method through " +
                "direct text edits; if the native refactoring refuses the selection, report the " +
                "failure rather than editing source. The target is addressed by a project-relative " +
                "Java file path and a 1-based source range (start inclusive, end exclusive). The " +
                "method name must be a valid lower-camel-case Java identifier. Returns a JSON " +
                "object with ok=true on success, or ok=false with a stable error code on failure."
    }
}
