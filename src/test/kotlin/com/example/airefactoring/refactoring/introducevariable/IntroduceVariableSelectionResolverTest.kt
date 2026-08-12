package com.example.airefactoring.refactoring.introducevariable

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path

class IntroduceVariableSelectionResolverTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = IntroduceVariableSelectionResolver()

    fun testResolvesOneCompleteReadableExpressionWithType() {
        val range = configureMarkedFile(
            "IntroduceSelection.java",
            "class IntroduceSelection { int value() { return <selection>10 + 20</selection>; } }",
        )

        val result = resolver.resolve(project, "IntroduceSelection.java", range)

        assertTrue(result is IntroduceVariableSelectionResolution.Success)
        val selection = (result as IntroduceVariableSelectionResolution.Success).selection
        assertEquals("10 + 20", selection.expression.text)
        assertEquals("int", selection.variableType.canonicalText)
    }

    fun testPreservesCommonTargetFailures() {
        requireFailure(
            resolver.resolve(project, "Missing.java", SourceRange(1, 1, 1, 2)),
            McpRefactoringErrorCode.FILE_NOT_FOUND,
        )
        mirrorRealFile("EmptySelection.java", "class EmptySelection {}")
        requireFailure(
            resolver.resolve(project, "EmptySelection.java", SourceRange(1, 1, 1, 1)),
            McpRefactoringErrorCode.INVALID_RANGE,
        )
    }

    fun testPartialExpressionRangeIsNotIntroducible() {
        val range = configureMarkedFile(
            "Partial.java",
            "class Partial { int value() { return <selection>10 +</selection> 20; } }",
        )

        requireFailure(
            resolver.resolve(project, "Partial.java", range),
            McpRefactoringErrorCode.NO_INTRODUCIBLE_EXPRESSION,
        )
    }

    fun testCrossExpressionRangeIsNotIntroducible() {
        val range = configureMarkedFile(
            "CrossExpression.java",
            "class CrossExpression { int value() { int a = <selection>10; int b = 20</selection>; return a + b; } }",
        )

        requireFailure(
            resolver.resolve(project, "CrossExpression.java", range),
            McpRefactoringErrorCode.NO_INTRODUCIBLE_EXPRESSION,
        )
    }

    fun testAssignmentTargetIsUnsupportedWithoutMutation() {
        val range = configureMarkedFile(
            "LValue.java",
            "class LValue { void set() { int value = 0; <selection>value</selection> = 2; } }",
        )
        val original = myFixture.editor.document.text

        requireFailure(
            resolver.resolve(project, "LValue.java", range),
            McpRefactoringErrorCode.UNSUPPORTED_EXPRESSION,
        )
        assertEquals(original, myFixture.editor.document.text)
    }

    fun testVoidExpressionIsUnsupportedWithoutMutation() {
        val range = configureMarkedFile(
            "VoidValue.java",
            "class VoidValue { void call() { <selection>System.out.println(1)</selection>; } }",
        )
        val original = myFixture.editor.document.text

        requireFailure(
            resolver.resolve(project, "VoidValue.java", range),
            McpRefactoringErrorCode.UNSUPPORTED_EXPRESSION,
        )
        assertEquals(original, myFixture.editor.document.text)
    }

    fun testUnknownTypeExpressionIsUnsupportedWithoutMutation() {
        val range = configureMarkedFile(
            "UnknownType.java",
            "class UnknownType { Object value() { return <selection>missing()</selection>; } }",
        )
        val original = myFixture.editor.document.text

        requireFailure(
            resolver.resolve(project, "UnknownType.java", range),
            McpRefactoringErrorCode.UNSUPPORTED_EXPRESSION,
        )
        assertEquals(original, myFixture.editor.document.text)
    }

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
        result: IntroduceVariableSelectionResolution,
        expected: McpRefactoringErrorCode,
    ) {
        assertTrue(
            "expected Failure($expected) but was $result",
            result is IntroduceVariableSelectionResolution.Failure,
        )
        val failure = result as IntroduceVariableSelectionResolution.Failure
        assertEquals(expected, failure.code)
        assertTrue(failure.message.isNotBlank())
    }
}
