package com.example.airefactoring.refactoring.extractmethod

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.intellij.codeInsight.CodeInsightFrontbackUtil
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiWhiteSpace
import java.nio.file.Path

/**
 * A 1-based source range as supplied by an MCP client. Lines are 1-based across the whole
 * document; columns are 1-based within the line. The end position is exclusive (the column after
 * the last character of the selection on [endLine]).
 */
data class SourceRange(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
)

/** The resolved selection: current [PsiElement]s plus the [Document] they were resolved against. */
class ExtractMethodSelection(
    val file: PsiJavaFile,
    val document: Document,
    val elements: Array<PsiElement>,
)

sealed class SelectionResolution {
    data class Success(val selection: ExtractMethodSelection) : SelectionResolution()

    data class Failure(
        val code: McpRefactoringErrorCode,
        val message: String,
    ) : SelectionResolution()
}

/**
 * Resolves an MCP-supplied 1-based [SourceRange] to current PSI elements without reading any
 * editor state. The caller owns the routed [Project]; this resolver only validates containment,
 * maps the range to document offsets, and finds the exact expression or statement block.
 */
class ExtractMethodSelectionResolver {

    fun resolve(project: Project, pathInProject: String, range: SourceRange): SelectionResolution {
        // 1. Path containment: reject absolute paths and normalize `..` traversal.
        val base = project.basePath?.let(Path::of)?.toAbsolutePath()?.normalize()
            ?: return failure(McpRefactoringErrorCode.FILE_NOT_FOUND, "The project has no base path.")
        val relative = Path.of(pathInProject)
        if (relative.isAbsolute) {
            return failure(McpRefactoringErrorCode.OUTSIDE_PROJECT, "The path must be project-relative.")
        }
        val candidate = base.resolve(relative).normalize()
        if (!candidate.startsWith(base)) {
            return failure(McpRefactoringErrorCode.OUTSIDE_PROJECT, "The path resolves outside the project.")
        }

        // 2. Resolve the virtual file and refuse read-only targets.
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(candidate.toString())
            ?: return failure(McpRefactoringErrorCode.FILE_NOT_FOUND, "The file does not exist: $pathInProject")
        if (!virtualFile.isWritable) {
            return failure(McpRefactoringErrorCode.READ_ONLY, "The file is read-only: $pathInProject")
        }

        // 3. Obtain and commit the document so PSI reflects the latest text.
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
            ?: return failure(McpRefactoringErrorCode.FILE_NOT_FOUND, "Unable to obtain a document for: $pathInProject")
        PsiDocumentManager.getInstance(project).commitDocument(document)

        // 4. The target must be a Java file.
        val file = PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile
            ?: return failure(McpRefactoringErrorCode.NOT_JAVA_FILE, "The file is not a Java file: $pathInProject")

        // 5. Convert the 1-based range to an ordered, non-empty offset range.
        val startOffset = offset(document, range.startLine, range.startColumn)
            ?: return failure(
                McpRefactoringErrorCode.INVALID_RANGE,
                "Invalid start position (${range.startLine}, ${range.startColumn}).",
            )
        val endOffset = offset(document, range.endLine, range.endColumn)
            ?: return failure(
                McpRefactoringErrorCode.INVALID_RANGE,
                "Invalid end position (${range.endLine}, ${range.endColumn}).",
            )
        if (startOffset >= endOffset) {
            return failure(McpRefactoringErrorCode.INVALID_RANGE, "The selection must be non-empty and ordered.")
        }

        // 6. Exact PSI resolution: an expression if the range matches exactly, else a statement block.
        // `findStatementsInRange` can return a lone comment/whitespace element in some platform
        // versions; those are not extractable statements, so they are filtered out rather than
        // broadening the selection.
        val expression = CodeInsightFrontbackUtil.findExpressionInRange(file, startOffset, endOffset)
        val elements: Array<PsiElement> = if (expression != null) {
            arrayOf(expression)
        } else {
            CodeInsightFrontbackUtil.findStatementsInRange(file, startOffset, endOffset)
                .filterNot { it is PsiComment || it is PsiWhiteSpace }
                .toTypedArray()
        }
        if (elements.isEmpty()) {
            return SelectionResolution.Failure(
                McpRefactoringErrorCode.NO_EXTRACTABLE_ELEMENTS,
                "The selected range does not resolve to a Java expression or statement block.",
            )
        }
        return SelectionResolution.Success(ExtractMethodSelection(file, document, elements))
    }

    /** Converts a 1-based [line]/[column] position to a document offset, or null when out of bounds. */
    private fun offset(document: Document, line: Int, column: Int): Int? {
        if (line !in 1..document.lineCount || column < 1) return null
        val lineIndex = line - 1
        val lineStart = document.getLineStartOffset(lineIndex)
        val lineEnd = document.getLineEndOffset(lineIndex)
        val value = lineStart + column - 1
        return value.takeIf { it <= lineEnd }
    }

    private fun failure(code: McpRefactoringErrorCode, message: String): SelectionResolution =
        SelectionResolution.Failure(code, message)
}
