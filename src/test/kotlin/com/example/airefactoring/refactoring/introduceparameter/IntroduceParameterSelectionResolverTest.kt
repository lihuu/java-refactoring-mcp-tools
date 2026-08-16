package com.example.airefactoring.refactoring.introduceparameter

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * The light fixture keeps its editor file in an in-memory file system and reuses the project base
 * path across test methods, so each test mirrors its content into a real file under
 * [com.intellij.openapi.project.Project.getBasePath] with a unique file name for the resolver's
 * real local-file lookup.
 */
class IntroduceParameterSelectionResolverTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = IntroduceParameterSelectionResolver()

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    // --- ExpressionTarget fixture ---
    private val expressionTarget =
        "class ExpressionTarget { int total(int count) { return <selection>count * 2</selection>; } }"

    // --- LocalTarget fixture ---
    private val localTarget =
        "class LocalTarget { int total(int count) { int <selection>doubled</selection> = count * 2; return doubled; } }"

    // --- Step 1/2: successful resolution ---

    fun testResolvesExactExpressionAsExpressionKind() {
        val range = configureMarkedFile("ExpressionTarget.java", expressionTarget)

        val result = resolver.resolve(
            project, "ExpressionTarget.java", range, "multiplier",
        )

        assertTrue(
            "expected Success but was $result",
            result is IntroduceParameterSelectionResolution.Success,
        )
        val selection = (result as IntroduceParameterSelectionResolution.Success).selection
        assertEquals(IntroduceParameterSourceKind.EXPRESSION, selection.sourceKind)
        assertEquals("count * 2", selection.expression?.text)
        assertEquals("int", selection.sourceType.canonicalText)
        assertEquals("total", selection.method.name)
        assertEquals(listOf("ExpressionTarget.java"), selection.affectedFiles)
    }

    fun testResolvesLocalDeclarationNameAsLocalVariableKind() {
        val range = configureMarkedFile("LocalTarget.java", localTarget)

        val result = resolver.resolve(
            project, "LocalTarget.java", range, "doubled",
        )

        assertTrue(
            "expected Success but was $result",
            result is IntroduceParameterSelectionResolution.Success,
        )
        val selection = (result as IntroduceParameterSelectionResolution.Success).selection
        assertEquals(IntroduceParameterSourceKind.LOCAL_VARIABLE, selection.sourceKind)
        assertEquals("doubled", selection.localVariable?.name)
        assertEquals("int", selection.sourceType.canonicalText)
        assertEquals("total", selection.method.name)
    }

    fun testResolvesLocalReadReferenceAsLocalVariableKind() {
        val range = configureMarkedFile(
            "LocalReadReference.java",
            "class LocalReadReference { int total(int count) { int doubled = count * 2; return <selection>doubled</selection>; } }",
        )

        val result = resolver.resolve(
            project, "LocalReadReference.java", range, "doubled",
        )

        assertTrue(
            "expected Success but was $result",
            result is IntroduceParameterSelectionResolution.Success,
        )
        val selection = (result as IntroduceParameterSelectionResolution.Success).selection
        assertEquals(IntroduceParameterSourceKind.LOCAL_VARIABLE, selection.sourceKind)
        assertEquals("doubled", selection.localVariable?.name)
        assertEquals("int", selection.sourceType.canonicalText)
    }

    // --- Step: rejections ---

    fun testPartialRangeIsNotIntroducible() {
        val range = configureMarkedFile(
            "PartialExpression.java",
            "class PartialExpression { int total(int count) { return <selection>count *</selection> 2; } }",
        )
        val original = myFixture.editor.document.text

        requireFailure(
            resolver.resolve(project, "PartialExpression.java", range, "multiplier"),
            McpRefactoringErrorCode.NO_INTRODUCIBLE_EXPRESSION,
        )
        assertEquals(original, myFixture.editor.document.text)
    }

    fun testMultipleExpressionsRangeIsNotIntroducible() {
        val range = configureMarkedFile(
            "MultipleExpression.java",
            "class MultipleExpression { int total(int count) { return <selection>count * 2; int x = 1;</selection> x; } }",
        )
        val original = myFixture.editor.document.text

        requireFailure(
            resolver.resolve(project, "MultipleExpression.java", range, "multiplier"),
            McpRefactoringErrorCode.NO_INTRODUCIBLE_EXPRESSION,
        )
        assertEquals(original, myFixture.editor.document.text)
    }

    fun testWrittenLocalIsUnsupportedWithoutMutation() {
        val range = configureMarkedFile(
            "WrittenLocal.java",
            "class WrittenLocal { int total(int count) { int doubled = count * 2; <selection>doubled</selection>++; return doubled; } }",
        )
        val original = myFixture.editor.document.text

        requireFailure(
            resolver.resolve(project, "WrittenLocal.java", range, "doubled"),
            McpRefactoringErrorCode.UNSUPPORTED_EXPRESSION,
        )
        assertEquals(original, myFixture.editor.document.text)
    }

    fun testLocalWithoutInitializerIsUnsupported() {
        val range = configureMarkedFile(
            "NoInitializerLocal.java",
            "class NoInitializerLocal { int total(int count) { int d; d = count * 2; return <selection>d</selection>; } }",
        )
        val original = myFixture.editor.document.text

        requireFailure(
            resolver.resolve(project, "NoInitializerLocal.java", range, "doubled"),
            McpRefactoringErrorCode.UNSUPPORTED_EXPRESSION,
        )
        assertEquals(original, myFixture.editor.document.text)
    }

    fun testVoidExpressionIsUnsupportedWithoutMutation() {
        val range = configureMarkedFile(
            "VoidExpression.java",
            "class VoidExpression { void total() { <selection>System.out.println(1)</selection>; } }",
        )
        val original = myFixture.editor.document.text

        requireFailure(
            resolver.resolve(project, "VoidExpression.java", range, "printed"),
            McpRefactoringErrorCode.UNSUPPORTED_EXPRESSION,
        )
        assertEquals(original, myFixture.editor.document.text)
    }

    fun testUnknownTypeExpressionIsUnsupported() {
        val range = configureMarkedFile(
            "UnknownTypeExpression.java",
            "class UnknownTypeExpression { Object total() { return <selection>unknownValue()</selection>; } }",
        )
        val original = myFixture.editor.document.text

        requireFailure(
            resolver.resolve(project, "UnknownTypeExpression.java", range, "unknown"),
            McpRefactoringErrorCode.UNSUPPORTED_EXPRESSION,
        )
        assertEquals(original, myFixture.editor.document.text)
    }

    fun testAssignmentTargetExpressionIsUnsupported() {
        val range = configureMarkedFile(
            "LValueExpression.java",
            "class LValueExpression { void total() { int value = 0; <selection>value</selection> = 2; } }",
        )
        val original = myFixture.editor.document.text

        requireFailure(
            resolver.resolve(project, "LValueExpression.java", range, "value"),
            McpRefactoringErrorCode.UNSUPPORTED_EXPRESSION,
        )
        assertEquals(original, myFixture.editor.document.text)
    }

    fun testConstructorMethodIsUnsupported() {
        val range = configureMarkedFile(
            "ConstructorTarget.java",
            "class ConstructorTarget { ConstructorTarget(int seed) { int x = <selection>seed * 2</selection>; } }",
        )
        val original = myFixture.editor.document.text

        requireFailure(
            resolver.resolve(project, "ConstructorTarget.java", range, "factor"),
            McpRefactoringErrorCode.UNSUPPORTED_METHOD,
        )
        assertEquals(original, myFixture.editor.document.text)
    }

    fun testExpressionNotInsideAnOrdinaryMethodHasNoTargetMethod() {
        val range = configureMarkedFile(
            "FieldInitializerExpr.java",
            "class FieldInitializerExpr { int field = <selection>10</selection>; }",
        )
        val original = myFixture.editor.document.text

        requireFailure(
            resolver.resolve(project, "FieldInitializerExpr.java", range, "value"),
            McpRefactoringErrorCode.NO_TARGET_METHOD,
        )
        assertEquals(original, myFixture.editor.document.text)
    }

    fun testFieldIsUnsupportedVariableSource() {
        val range = configureMarkedFile(
            "FieldSource.java",
            "class FieldSource { int <selection>field</selection>; }",
        )
        val original = myFixture.editor.document.text

        requireFailure(
            resolver.resolve(project, "FieldSource.java", range, "field"),
            McpRefactoringErrorCode.UNSUPPORTED_VARIABLE,
        )
        assertEquals(original, myFixture.editor.document.text)
    }

    fun testInvalidParameterNameIsRejected() {
        val range = configureMarkedFile("ExpressionTarget.java", expressionTarget)

        requireFailure(
            resolver.resolve(project, "ExpressionTarget.java", range, "1bad name"),
            McpRefactoringErrorCode.INVALID_PARAMETER_NAME,
        )
    }

    fun testParameterNameConflictingWithExistingParameterIsRejectedWithoutMutation() {
        val range = configureMarkedFile(
            "ParamConflict.java",
            "class ParamConflict { int total(int count) { return <selection>count * 2</selection>; } }",
        )
        val original = myFixture.editor.document.text

        requireFailure(
            resolver.resolve(project, "ParamConflict.java", range, "count"),
            McpRefactoringErrorCode.INVALID_PARAMETER_NAME,
        )
        assertEquals(original, myFixture.editor.document.text)
    }

    fun testParameterNameConflictingWithSurvivingLocalIsRejectedWithoutMutation() {
        val range = configureMarkedFile(
            "LocalConflict.java",
            "class LocalConflict { int total(int count) { int multiplier = 5; return <selection>count * multiplier</selection>; } }",
        )
        val original = myFixture.editor.document.text

        requireFailure(
            resolver.resolve(project, "LocalConflict.java", range, "multiplier"),
            McpRefactoringErrorCode.INVALID_PARAMETER_NAME,
        )
        assertEquals(original, myFixture.editor.document.text)
    }

    fun testOverloadSetSharingNameIsUnsupported() {
        val range = configureMarkedFile(
            "OverloadTarget.java",
            "class OverloadTarget { int total(int count) { return <selection>count * 2</selection>; } int total(int count, int extra) { return count; } }",
        )
        val original = myFixture.editor.document.text

        requireFailure(
            resolver.resolve(project, "OverloadTarget.java", range, "multiplier"),
            McpRefactoringErrorCode.UNSUPPORTED_METHOD,
        )
        assertEquals(original, myFixture.editor.document.text)
    }

    fun testOverrideHierarchyIsUnsupported() {
        val range = configureMarkedFile(
            "OverrideBase.java",
            "class OverrideBase { int total(int count) { return <selection>count * 2</selection>; } } class OverrideChild extends OverrideBase { int total(int count) { return count; } }",
        )
        val original = myFixture.editor.document.text

        requireFailure(
            resolver.resolve(project, "OverrideBase.java", range, "multiplier"),
            McpRefactoringErrorCode.UNSUPPORTED_METHOD,
        )
        assertEquals(original, myFixture.editor.document.text)
    }

    // --- Step 3: callers / affected files ---

    fun testAffectedCallerFilesAreComputedFromDirectCalls() {
        val range = configureMarkedFile(
            "Multiplier.java",
            "class Multiplier { int scale(int count) { return <selection>count * 3</selection>; } }",
        )
        mirrorRealFile(
            "CallerOne.java",
            "class CallerOne { int call() { return new Multiplier().scale(5); } }",
        )
        mirrorRealFile(
            "CallerTwo.java",
            "class CallerTwo { int also() { return new Multiplier().scale(9); } }",
        )
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)

        val result = resolver.resolve(project, "Multiplier.java", range, "multiplier")

        assertTrue(
            "expected Success but was $result",
            result is IntroduceParameterSelectionResolution.Success,
        )
        val selection = (result as IntroduceParameterSelectionResolution.Success).selection
        assertEquals(
            listOf("CallerOne.java", "CallerTwo.java", "Multiplier.java"),
            selection.affectedFiles,
        )
    }

    // --- helpers ---

    private fun configureMarkedFile(fileName: String, markedText: String): SourceRange {
        val start = markedText.indexOf(START_MARKER)
        val end = markedText.indexOf(END_MARKER)
        require(start >= 0 && end > start) {
            "fixture must contain exactly one <selection> pair: $markedText"
        }
        val content = markedText.substring(0, start) +
            markedText.substring(start + START_MARKER.length, end) +
            markedText.substring(end + END_MARKER.length)
        myFixture.configureByText(fileName, content)
        val document = myFixture.editor.document
        val startOffset = start
        val endOffset = end - START_MARKER.length
        myFixture.editor.selectionModel.setSelection(startOffset, endOffset)
        mirrorRealFile(fileName, content)
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        return rangeFromOffsets(document, startOffset, endOffset)
    }

    private fun rangeFromOffsets(document: Document, startOffset: Int, endOffset: Int): SourceRange {
        fun position(offset: Int): Pair<Int, Int> {
            val line = document.getLineNumber(offset)
            return (line + 1) to (offset - document.getLineStartOffset(line) + 1)
        }

        val (startLine, startColumn) = position(startOffset)
        val (endLine, endColumn) = position(endOffset)
        return SourceRange(startLine, startColumn, endLine, endColumn)
    }

    private fun mirrorRealFile(fileName: String, text: String): VirtualFile {
        val target = Path.of(project.basePath!!, fileName)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(target.toString())!!
    }

    companion object {
        private const val START_MARKER = "<selection>"
        private const val END_MARKER = "</selection>"
    }

    private fun requireFailure(
        result: IntroduceParameterSelectionResolution,
        expected: McpRefactoringErrorCode,
    ) {
        assertTrue(
            "expected Failure($expected) but was $result",
            result is IntroduceParameterSelectionResolution.Failure,
        )
        val failure = result as IntroduceParameterSelectionResolution.Failure
        assertEquals(expected, failure.code)
        assertTrue("failure message must not be blank", failure.message.isNotBlank())
    }
}
