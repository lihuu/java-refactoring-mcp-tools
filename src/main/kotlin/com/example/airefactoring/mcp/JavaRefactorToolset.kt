package com.example.airefactoring.mcp

import com.example.airefactoring.refactoring.SourceRange
import com.example.airefactoring.refactoring.changesignature.ChangeSignatureOperation
import com.example.airefactoring.refactoring.extractmethod.ExtractMethodOperation
import com.example.airefactoring.refactoring.introducevariable.IntroduceVariableOperation
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import kotlinx.coroutines.currentCoroutineContext

/** MCP adapter for this plugin's native Java refactoring operations. */
class JavaRefactorToolset(
    private val extractMethodOperation: ExtractMethodOperation = ExtractMethodOperation(),
    private val introduceVariableOperation: IntroduceVariableOperation = IntroduceVariableOperation(),
    private val changeSignatureOperation: ChangeSignatureOperation = ChangeSignatureOperation(),
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

    @McpTool
    @McpDescription(INTRODUCE_VARIABLE_DESCRIPTION)
    suspend fun java_introduce_variable(
        @McpDescription("Java file path relative to the project root") pathInProject: String,
        @McpDescription("1-based inclusive start line") startLine: Int,
        @McpDescription("1-based inclusive start column") startColumn: Int,
        @McpDescription("1-based line containing the exclusive end position") endLine: Int,
        @McpDescription("1-based exclusive end column") endColumn: Int,
        @McpDescription("Agent-selected preferred Java local-variable name") preferredVariableName: String,
    ): String = introduceVariableOperation.execute(
        currentCoroutineContext().project,
        pathInProject,
        SourceRange(startLine, startColumn, endLine, endColumn),
        preferredVariableName,
    )

    @McpTool
    @McpDescription(CHANGE_SIGNATURE_ADD_PARAMETER_DESCRIPTION)
    suspend fun java_change_signature_add_parameter(
        @McpDescription("Java file path relative to the project root") pathInProject: String,
        @McpDescription("1-based line inside the target method declaration or body") line: Int,
        @McpDescription("1-based column inside the target method declaration or body") column: Int,
        @McpDescription("Name of the one new Java parameter") parameterName: String,
        @McpDescription("Resolvable Java source type for the one new parameter") parameterType: String,
        @McpDescription("1-based position in the resulting parameter list") parameterPosition: Int,
        @McpDescription("Java expression inserted uniformly into all existing call sites")
        defaultCallSiteExpression: String,
    ): String = changeSignatureOperation.execute(
        currentCoroutineContext().project,
        pathInProject,
        line,
        column,
        parameterName,
        parameterType,
        parameterPosition,
        defaultCallSiteExpression,
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

        const val INTRODUCE_VARIABLE_DESCRIPTION =
            "Introduces one exact Java expression as one local variable using IntelliJ's native " +
                "Introduce Variable refactoring. Use it after reading the containing method, " +
                "choosing a semantic preferred variable name from the expression and context, " +
                "presenting the change, and waiting for user approval. Only the selected occurrence " +
                "is replaced; IntelliJ writes an explicit non-final type and may make a conflicting " +
                "valid name unique. Re-read the file after success and use the returned " +
                "actualVariableName for later work. Never use direct text edits, patches, or PSI " +
                "mutation as a fallback when this native refactoring rejects the expression. The " +
                "target uses a project-relative Java path and 1-based source range with an inclusive " +
                "start and exclusive end. Returns JSON with ok=true on success or a stable error " +
                "code on failure."

        const val CHANGE_SIGNATURE_ADD_PARAMETER_DESCRIPTION =
            "Adds exactly one parameter to one ordinary Java method using IntelliJ's native Change " +
                "Signature refactoring. Use search_symbol to obtain the exact declaration path and " +
                "1-based line/column, then read the method and callers, present the proposed signature " +
                "and uniform argument strategy, and call only after waiting for user approval. The " +
                "supplied expression is inserted into all existing call sites; it is a structural " +
                "default and may require separately reasoned caller-specific edits. Re-read every " +
                "returned affectedFiles entry and run diagnostics, build, and tests after success. " +
                "This tool rejects constructors, overloads, override hierarchies, method references, " +
                "unsupported usages, and conflicts; it does not support general Change Signature. " +
                "Never use direct text edits, patches, whole-file rewrites, or direct PSI mutation " +
                "as a fallback. Returns JSON with ok=true on success or ok=false with a stable error " +
                "code on failure."
    }
}
