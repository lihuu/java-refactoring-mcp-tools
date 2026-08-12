package com.example.airefactoring.refactoring

/**
 * A 1-based source range supplied by an MCP client. The start is inclusive and the end is
 * exclusive, so [endColumn] identifies the column immediately after the selected text.
 */
data class SourceRange(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
)
