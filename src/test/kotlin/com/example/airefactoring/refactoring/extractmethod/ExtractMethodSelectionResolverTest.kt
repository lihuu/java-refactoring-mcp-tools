package com.example.airefactoring.refactoring.extractmethod

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * The light fixture keeps its editor file in an in-memory `temp://` file system and reuses the
 * project base path across test methods, so each test mirrors its content into a real file under
 * [com.intellij.openapi.project.Project.getBasePath] with a unique file name for the resolver's
 * real local-file lookup.
 */
class ExtractMethodSelectionResolverTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = ExtractMethodSelectionResolver()

    // --- helpers ---

    /**
     * Derives the 1-based [SourceRange] from the fixture editor's `<selection>` markers.
     */
    private fun sourceRangeFromEditor(): SourceRange {
        val document = myFixture.editor.document
        fun position(offset: Int): Pair<Int, Int> {
            val line = document.getLineNumber(offset)
            return (line + 1) to (offset - document.getLineStartOffset(line) + 1)
        }
        val (startLine, startColumn) = position(myFixture.editor.selectionModel.selectionStart)
        val (endLine, endColumn) = position(myFixture.editor.selectionModel.selectionEnd)
        return SourceRange(startLine, startColumn, endLine, endColumn)
    }

    /** Writes [text] to a real `{project.basePath}/{fileName}` file and registers it in the VFS. */
    private fun mirrorRealFile(fileName: String, text: String): VirtualFile {
        val target = Path.of(project.basePath!!, fileName)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(target.toString())!!
    }

    /** Configures a `<selection>`-marked fixture and mirrors the plain content to a real file. */
    private fun configureMarkedFile(fileName: String, markedText: String) {
        myFixture.configureByText(fileName, markedText)
        val plain = markedText.replace("<selection>", "").replace("</selection>", "")
        mirrorRealFile(fileName, plain)
    }

    private fun requireSuccess(result: SelectionResolution): ExtractMethodSelection {
        assertTrue("expected Success but was $result", result is SelectionResolution.Success)
        return (result as SelectionResolution.Success).selection
    }

    private fun requireFailure(result: SelectionResolution, expected: McpRefactoringErrorCode) {
        assertTrue("expected Failure($expected) but was $result", result is SelectionResolution.Failure)
        val failure = result as SelectionResolution.Failure
        assertEquals(expected, failure.code)
        assertTrue("failure message must not be blank", failure.message.isNotBlank())
    }

    // --- Step 1: successful resolution ---

    fun testResolvesSingleExpression() {
        configureMarkedFile(
            "CalcExpr.java",
            "public class Calc { int add(int x, int y) { return <selection>x + y</selection>; } }",
        )
        val selection = requireSuccess(resolver.resolve(project, "CalcExpr.java", sourceRangeFromEditor()))
        assertEquals("x + y", selection.elements.joinToString("") { it.text })
        assertEquals(1, selection.elements.size)
        assertNotNull(selection.document)
    }

    fun testResolvesTwoCompleteStatements() {
        configureMarkedFile(
            "CalcStmt.java",
            "public class Calc { int add(int x, int y) { <selection>int s = x + y; return s;</selection> } }",
        )
        val selection = requireSuccess(resolver.resolve(project, "CalcStmt.java", sourceRangeFromEditor()))
        assertEquals("int s = x + y;return s;", selection.elements.joinToString("") { it.text })
        assertEquals(2, selection.elements.size)
        assertNotNull(selection.document)
    }

    // --- Step 2: failure cases ---

    fun testCommentOnlyRangeReturnsNoExtractableElements() {
        configureMarkedFile(
            "CalcComment.java",
            "public class Calc {\n" +
                "    int add(int x, int y) {\n" +
                "        <selection>// a comment</selection>\n" +
                "        return x;\n" +
                "    }\n" +
                "}",
        )
        val result = resolver.resolve(project, "CalcComment.java", sourceRangeFromEditor())
        requireFailure(result, McpRefactoringErrorCode.NO_EXTRACTABLE_ELEMENTS)
    }
}
