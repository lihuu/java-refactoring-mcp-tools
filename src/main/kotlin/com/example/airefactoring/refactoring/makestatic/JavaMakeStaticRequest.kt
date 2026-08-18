package com.example.airefactoring.refactoring.makestatic

import com.example.airefactoring.refactoring.SourceRange

/**
 * One explicitly selected instance field to pass as a parameter when making a member static. The
 * range exactly selects the field declaration name in the same file as the target member, and
 * [parameterName] is the AI-selected Java identifier for the resulting parameter. The plugin neither
 * discovers fields nor chooses parameter names.
 */
data class JavaMakeStaticFieldParameter(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val parameterName: String,
) {
    fun range() = SourceRange(startLine, startColumn, endLine, endColumn)

    companion object {
        fun fromParallelLists(
            startLines: List<Int>,
            startColumns: List<Int>,
            endLines: List<Int>,
            endColumns: List<Int>,
            parameterNames: List<String>,
        ): List<JavaMakeStaticFieldParameter> {
            val sizes = listOf(
                startLines.size,
                startColumns.size,
                endLines.size,
                endColumns.size,
                parameterNames.size,
            )
            if (sizes.distinct().size != 1) {
                throw JavaMakeStaticFieldParameterEncodingException(
                    "fieldStartLines, fieldStartColumns, fieldEndLines, fieldEndColumns, and " +
                        "fieldParameterNames must have the same number of entries.",
                )
            }
            return startLines.indices.map { index ->
                JavaMakeStaticFieldParameter(
                    startLine = startLines[index],
                    startColumn = startColumns[index],
                    endLine = endLines[index],
                    endColumn = endColumns[index],
                    parameterName = parameterNames[index],
                )
            }
        }
    }
}

class JavaMakeStaticFieldParameterEncodingException(message: String) : RuntimeException(message)
