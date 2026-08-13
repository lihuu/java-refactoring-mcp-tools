package com.example.airefactoring.refactoring.introducemember

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * The light fixture keeps its editor file in an in-memory file system and reuses the project base
 * path across test methods, so each test mirrors its content into a real file under
 * [com.intellij.openapi.project.Project.getBasePath] with a unique file name for the resolver's
 * real local-file lookup.
 */
class IntroduceMemberSelectionResolverTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = IntroduceMemberSelectionResolver()

    // --- Step 1: successful resolution ---

    fun testResolvesExactLiteralWithContainingClass() {
        val range = configureMarkedFile(
            "LiteralMember.java",
            "class LiteralMember { int value() { return <selection>12</selection>; } }",
        )

        val result = resolver.resolve(project, "LiteralMember.java", range)

        assertTrue(result is IntroduceMemberSelectionResolution.Success)
        val selection = (result as IntroduceMemberSelectionResolution.Success).selection
        assertEquals("12", selection.expression.text)
        assertEquals("int", selection.memberType.canonicalText)
        assertEquals("LiteralMember", selection.containingClass.name)
    }

    fun testResolvesExactStringLiteral() {
        val range = configureMarkedFile(
            "StringMember.java",
            "class StringMember { Object value() { return <selection>\"hello\"</selection>; } }",
        )

        val result = resolver.resolve(project, "StringMember.java", range)

        assertTrue(result is IntroduceMemberSelectionResolution.Success)
        val selection = (result as IntroduceMemberSelectionResolution.Success).selection
        assertEquals("\"hello\"", selection.expression.text)
        assertEquals("java.lang.String", selection.memberType.canonicalText)
        assertEquals("StringMember", selection.containingClass.name)
    }

    fun testResolvesMethodCallExpression() {
        val range = configureMarkedFile(
            "MethodCallMember.java",
            "class MethodCallMember { int value() { return <selection>compute()</selection>; } int compute() { return 1; } }",
        )

        val result = resolver.resolve(project, "MethodCallMember.java", range)

        assertTrue(result is IntroduceMemberSelectionResolution.Success)
        val selection = (result as IntroduceMemberSelectionResolution.Success).selection
        assertEquals("compute()", selection.expression.text)
        assertEquals("int", selection.memberType.canonicalText)
        assertEquals("MethodCallMember", selection.containingClass.name)
    }

    fun testResolvesCompoundExpression() {
        val range = configureMarkedFile(
            "CompoundMember.java",
            "class CompoundMember { int value() { return <selection>10 + 20</selection>; } }",
        )

        val result = resolver.resolve(project, "CompoundMember.java", range)

        assertTrue(result is IntroduceMemberSelectionResolution.Success)
        val selection = (result as IntroduceMemberSelectionResolution.Success).selection
        assertEquals("10 + 20", selection.expression.text)
        assertEquals("int", selection.memberType.canonicalText)
        assertEquals("CompoundMember", selection.containingClass.name)
    }

    // --- Step 2: rejection ---

    fun testPartialExpressionRangeIsNotIntroducible() {
        val range = configureMarkedFile(
            "PartialMember.java",
            "class PartialMember { int value() { return <selection>10 +</selection> 20; } }",
        )

        requireFailure(
            resolver.resolve(project, "PartialMember.java", range),
            McpRefactoringErrorCode.NO_INTRODUCIBLE_EXPRESSION,
        )
    }

    fun testCrossExpressionRangeIsNotIntroducible() {
        val range = configureMarkedFile(
            "CrossMember.java",
            "class CrossMember { int value() { int a = <selection>10; int b = 20</selection>; return a + b; } }",
        )

        requireFailure(
            resolver.resolve(project, "CrossMember.java", range),
            McpRefactoringErrorCode.NO_INTRODUCIBLE_EXPRESSION,
        )
    }

    fun testAssignmentTargetIsUnsupportedWithoutMutation() {
        val range = configureMarkedFile(
            "LValueMember.java",
            "class LValueMember { void set() { int value = 0; <selection>value</selection> = 2; } }",
        )
        val original = myFixture.editor.document.text

        requireFailure(
            resolver.resolve(project, "LValueMember.java", range),
            McpRefactoringErrorCode.UNSUPPORTED_EXPRESSION,
        )
        assertEquals(original, myFixture.editor.document.text)
    }

    fun testVoidExpressionIsUnsupportedWithoutMutation() {
        val range = configureMarkedFile(
            "VoidValueMember.java",
            "class VoidValueMember { void call() { <selection>System.out.println(1)</selection>; } }",
        )
        val original = myFixture.editor.document.text

        requireFailure(
            resolver.resolve(project, "VoidValueMember.java", range),
            McpRefactoringErrorCode.UNSUPPORTED_EXPRESSION,
        )
        assertEquals(original, myFixture.editor.document.text)
    }

    fun testExpressionOutsideClassIsUnsupportedDestination() {
        val range = configureMarkedFile(
            "package-info.java",
            "@SuppressWarnings(<selection>\"unchecked\"</selection>)\npackage sample;",
        )

        requireFailure(
            resolver.resolve(project, "package-info.java", range),
            McpRefactoringErrorCode.UNSUPPORTED_DESTINATION,
        )
    }

    fun testReadOnlyFileIsRejectedWithoutMutation() {
        mirrorRealFile("ReadOnlyMember.java", "class ReadOnlyMember { int value() { return 12; } }")
        val readOnly = LocalFileSystem.getInstance().findFileByPath(
            Path.of(project.basePath!!, "ReadOnlyMember.java").toString(),
        )!!
        WriteCommandAction.runWriteCommandAction(project) { readOnly.isWritable = false }
        try {
            requireFailure(
                resolver.resolve(project, "ReadOnlyMember.java", SourceRange(1, 1, 1, 2)),
                McpRefactoringErrorCode.READ_ONLY,
            )
        } finally {
            WriteCommandAction.runWriteCommandAction(project) { readOnly.isWritable = true }
        }
    }

    fun testPreservesCommonTargetFailures() {
        requireFailure(
            resolver.resolve(project, "MissingMember.java", SourceRange(1, 1, 1, 2)),
            McpRefactoringErrorCode.FILE_NOT_FOUND,
        )
        mirrorRealFile("EmptyMember.java", "class EmptyMember {}")
        requireFailure(
            resolver.resolve(project, "EmptyMember.java", SourceRange(1, 1, 1, 1)),
            McpRefactoringErrorCode.INVALID_RANGE,
        )
    }

    // --- helpers ---

    private fun configureMarkedFile(fileName: String, markedText: String): SourceRange {
        myFixture.configureByText(fileName, markedText)
        val range = sourceRangeFromEditor()
        mirrorRealFile(fileName, myFixture.editor.document.text)
        return range
    }

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

    private fun mirrorRealFile(fileName: String, text: String): VirtualFile {
        val target = Path.of(project.basePath!!, fileName)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(target.toString())!!
    }

    private fun requireFailure(
        result: IntroduceMemberSelectionResolution,
        expected: McpRefactoringErrorCode,
    ) {
        assertTrue(
            "expected Failure($expected) but was $result",
            result is IntroduceMemberSelectionResolution.Failure,
        )
        val failure = result as IntroduceMemberSelectionResolution.Failure
        assertEquals(expected, failure.code)
        assertTrue("failure message must not be blank", failure.message.isNotBlank())
    }
}
