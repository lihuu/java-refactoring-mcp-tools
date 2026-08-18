package com.example.airefactoring.refactoring.makestatic

import com.example.airefactoring.refactoring.SourceRange
import kotlinx.serialization.Serializable

/**
 * One explicitly selected instance field to pass as a parameter when making a member static. The
 * range exactly selects the field declaration name in the same file as the target member, and
 * [parameterName] is the AI-selected Java identifier for the resulting parameter. The plugin neither
 * discovers fields nor chooses parameter names.
 */
@Serializable
data class JavaMakeStaticFieldParameter(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val parameterName: String,
) {
    fun range() = SourceRange(startLine, startColumn, endLine, endColumn)
}
