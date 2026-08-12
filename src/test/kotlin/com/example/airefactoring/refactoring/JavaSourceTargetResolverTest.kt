package com.example.airefactoring.refactoring

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path

class JavaSourceTargetResolverTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = JavaSourceTargetResolver()

    fun testResolvesCurrentJavaDocumentAndExclusiveOffsets() {
        val file = mirrorRealFile("RangeOk.java", "class RangeOk { int n = 10 + 20; }")
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        val start = document.text.indexOf("10 + 20")

        val result = resolver.resolve(
            project,
            "RangeOk.java",
            range(document, start, start + "10 + 20".length),
        )

        assertTrue(result is JavaSourceTargetResolution.Success)
        val target = (result as JavaSourceTargetResolution.Success).target
        assertEquals(
            "10 + 20",
            target.document.getText(TextRange(target.startOffset, target.endOffset)),
        )
        assertEquals("RangeOk.java", target.file.name)
    }

    fun testRejectsAbsoluteAndParentTraversalPaths() {
        requireFailure(
            resolver.resolve(project, "/tmp/Outside.java", SourceRange(1, 1, 1, 2)),
            McpRefactoringErrorCode.OUTSIDE_PROJECT,
        )
        requireFailure(
            resolver.resolve(project, "../Outside.java", SourceRange(1, 1, 1, 2)),
            McpRefactoringErrorCode.OUTSIDE_PROJECT,
        )
    }

    fun testRejectsMissingNonJavaAndReadOnlyFiles() {
        requireFailure(
            resolver.resolve(project, "Missing.java", SourceRange(1, 1, 1, 2)),
            McpRefactoringErrorCode.FILE_NOT_FOUND,
        )

        mirrorRealFile("range-notes.txt", "text")
        requireFailure(
            resolver.resolve(project, "range-notes.txt", SourceRange(1, 1, 1, 2)),
            McpRefactoringErrorCode.NOT_JAVA_FILE,
        )

        val readOnly = mirrorRealFile("RangeReadOnly.java", "class RangeReadOnly {}")
        WriteCommandAction.runWriteCommandAction(project) { readOnly.isWritable = false }
        try {
            requireFailure(
                resolver.resolve(project, "RangeReadOnly.java", SourceRange(1, 1, 1, 2)),
                McpRefactoringErrorCode.READ_ONLY,
            )
        } finally {
            WriteCommandAction.runWriteCommandAction(project) { readOnly.isWritable = true }
        }
    }

    fun testRejectsInvalidCoordinates() {
        mirrorRealFile("BadRange.java", "class BadRange {}")

        listOf(
            SourceRange(0, 1, 1, 2),
            SourceRange(1, 0, 1, 2),
            SourceRange(1, 2, 1, 1),
            SourceRange(1, 1, 1, 1),
            SourceRange(99, 1, 99, 2),
            SourceRange(1, 999, 1, 1000),
        ).forEach { range ->
            requireFailure(
                resolver.resolve(project, "BadRange.java", range),
                McpRefactoringErrorCode.INVALID_RANGE,
            )
        }
    }

    private fun mirrorRealFile(fileName: String, text: String): VirtualFile {
        val target = Path.of(project.basePath!!, fileName)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(target.toString())!!
    }

    private fun range(document: Document, startOffset: Int, endOffset: Int): SourceRange {
        fun position(offset: Int): Pair<Int, Int> {
            val line = document.getLineNumber(offset)
            return (line + 1) to (offset - document.getLineStartOffset(line) + 1)
        }

        val (startLine, startColumn) = position(startOffset)
        val (endLine, endColumn) = position(endOffset)
        return SourceRange(startLine, startColumn, endLine, endColumn)
    }

    private fun requireFailure(
        result: JavaSourceTargetResolution,
        expected: McpRefactoringErrorCode,
    ) {
        assertTrue(
            "expected Failure($expected) but was $result",
            result is JavaSourceTargetResolution.Failure,
        )
        val failure = result as JavaSourceTargetResolution.Failure
        assertEquals(expected, failure.code)
        assertTrue(failure.message.isNotBlank())
    }
}
