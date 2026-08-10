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
    NO_EXTRACTABLE_ELEMENTS,
    PREPARE_FAILED,
    REFACTORING_FAILED,
}

@Serializable
data class McpRefactoringResult private constructor(
    val ok: Boolean,
    val operation: String? = null,
    val filePath: String? = null,
    val methodName: String? = null,
    val summary: String? = null,
    val code: McpRefactoringErrorCode? = null,
    val message: String? = null,
) {
    fun toJson(): String = JSON.encodeToString(this)

    companion object {
        private val JSON = Json { encodeDefaults = false }

        fun success(filePath: String, methodName: String, summary: String) =
            McpRefactoringResult(
                ok = true,
                operation = "java_extract_method",
                filePath = filePath,
                methodName = methodName,
                summary = summary,
            )

        fun failure(code: McpRefactoringErrorCode, message: String) =
            McpRefactoringResult(ok = false, code = code, message = message)
    }
}
