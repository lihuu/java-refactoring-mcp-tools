package com.example.airefactoring.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class McpRefactoringErrorCode {
    FILE_NOT_FOUND,
    OUTSIDE_PROJECT,
    NOT_JAVA_FILE,
    READ_ONLY,
    INVALID_RANGE,
    INVALID_METHOD_NAME,
    INVALID_VARIABLE_NAME,
    NO_TARGET_METHOD,
    UNSUPPORTED_METHOD,
    INVALID_PARAMETER_NAME,
    INVALID_PARAMETER_TYPE,
    INVALID_PARAMETER_POSITION,
    INVALID_DEFAULT_VALUE,
    UNSUPPORTED_USAGE,
    REFACTORING_CONFLICT,
    NO_EXTRACTABLE_ELEMENTS,
    NO_INTRODUCIBLE_EXPRESSION,
    UNSUPPORTED_EXPRESSION,
    PREPARE_FAILED,
    REFACTORING_FAILED,
}

@Serializable
@ConsistentCopyVisibility
data class McpRefactoringResult private constructor(
    val ok: Boolean,
    val operation: String? = null,
    val filePath: String? = null,
    val projectBasePath: String? = null,
    val methodName: String? = null,
    val requestedVariableName: String? = null,
    val actualVariableName: String? = null,
    val variableType: String? = null,
    val summary: String? = null,
    val code: McpRefactoringErrorCode? = null,
    val message: String? = null,
) {
    fun toJson(): String = JSON.encodeToString(this)

    companion object {
        private val JSON = Json { encodeDefaults = false }

        fun extractMethodSuccess(
            projectBasePath: String,
            filePath: String,
            methodName: String,
            summary: String,
        ) =
            McpRefactoringResult(
                ok = true,
                operation = "java_extract_method",
                filePath = filePath,
                projectBasePath = projectBasePath,
                methodName = methodName,
                summary = summary,
            )

        fun introduceVariableSuccess(
            projectBasePath: String,
            filePath: String,
            requestedVariableName: String,
            actualVariableName: String,
            variableType: String,
            summary: String,
        ) = McpRefactoringResult(
            ok = true,
            operation = "java_introduce_variable",
            filePath = filePath,
            projectBasePath = projectBasePath,
            requestedVariableName = requestedVariableName,
            actualVariableName = actualVariableName,
            variableType = variableType,
            summary = summary,
        )

        fun failure(code: McpRefactoringErrorCode, message: String) =
            McpRefactoringResult(ok = false, code = code, message = message)
    }
}
