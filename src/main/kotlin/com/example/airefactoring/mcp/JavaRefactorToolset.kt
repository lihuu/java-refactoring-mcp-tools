package com.example.airefactoring.mcp

import com.example.airefactoring.refactoring.SourceRange
import com.example.airefactoring.refactoring.extractmethod.ExtractMethodOperation
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import kotlinx.coroutines.currentCoroutineContext

/** MCP adapter for this plugin's native Java refactoring operations. */
class JavaRefactorToolset(
    private val extractMethodOperation: ExtractMethodOperation = ExtractMethodOperation(),
) : McpToolset {

    @McpTool
    @McpDescription(EXTRACT_METHOD_DESCRIPTION)
    suspend fun java_extract_method(
        @McpDescription("Java file path relative to the project root") pathInProject: String,
        @McpDescription("1-based inclusive start line") startLine: Int,
        @McpDescription("1-based inclusive start column") startColumn: Int,
        @McpDescription("1-based line containing the exclusive end position") endLine: Int,
        @McpDescription("1-based exclusive end column") endColumn: Int,
        @McpDescription("New lower-camel-case Java method name") methodName: String,
    ): String = extractMethodOperation.execute(
        currentCoroutineContext().project,
        pathInProject,
        SourceRange(startLine, startColumn, endLine, endColumn),
        methodName,
    )

    private companion object {
        const val EXTRACT_METHOD_DESCRIPTION =
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
