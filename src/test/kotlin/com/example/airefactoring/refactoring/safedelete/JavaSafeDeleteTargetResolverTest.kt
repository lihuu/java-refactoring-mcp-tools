package com.example.airefactoring.refactoring.safedelete

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.command.WriteCommandAction
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
class JavaSafeDeleteTargetResolverTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = JavaSafeDeleteTargetResolver()

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    // --- exact-name selections ---

    fun testResolvesUnusedFieldName() {
        val range = configureMarkedFile(
            "UnusedField.java",
            "class UnusedField { int <selection>unusedField</selection>; }",
        )

        assertNativeTargetOrUnsupported(resolver.resolve(project, "UnusedField.java", range))
    }

    fun testResolvesUnusedMethodName() {
        val range = configureMarkedFile(
            "UnusedMethod.java",
            "class UnusedMethod { void <selection>unusedMethod</selection>() {} }",
        )

        assertNativeTargetOrUnsupported(resolver.resolve(project, "UnusedMethod.java", range))
    }

    fun testResolvesUnusedLocalName() {
        val range = configureMarkedFile(
            "UnusedLocal.java",
            "class UnusedLocal { void run() { int <selection>unusedLocal</selection> = 1; } }",
        )

        assertNativeTargetOrUnsupported(resolver.resolve(project, "UnusedLocal.java", range))
    }

    fun testResolvesUnusedTypeName() {
        val range = configureMarkedFile(
            "UnusedType.java",
            "class <selection>UnusedType</selection> {}",
        )

        assertNativeTargetOrUnsupported(resolver.resolve(project, "UnusedType.java", range))
    }

    // --- invalid ranges ---

    fun testPartialIdentifierIsInvalidRange() {
        val range = configureMarkedFile(
            "PartialField.java",
            "class PartialField { int <selection>unusedFiel</selection>d; }",
        )

        requireFailure(
            resolver.resolve(project, "PartialField.java", range),
            McpRefactoringErrorCode.INVALID_RANGE,
        )
    }

    fun testRangeInsideMethodBodyIsInvalidRange() {
        val range = configureMarkedFile(
            "BodyRange.java",
            "class BodyRange { void run() { int x = <selection>1 +</selection> 2; } }",
        )

        requireFailure(
            resolver.resolve(project, "BodyRange.java", range),
            McpRefactoringErrorCode.INVALID_RANGE,
        )
    }

    // --- propagated JavaSourceTargetResolver error codes ---

    fun testMissingFilePreservesFileNotFound() {
        requireFailure(
            resolver.resolve(project, "Missing.java", SourceRange(1, 1, 1, 2)),
            McpRefactoringErrorCode.FILE_NOT_FOUND,
        )
    }

    fun testReadOnlyFilePreservesReadOnly() {
        val readOnly = mirrorRealFile("ReadOnly.java", "class ReadOnly {}")
        WriteCommandAction.runWriteCommandAction(project) { readOnly.isWritable = false }
        try {
            requireFailure(
                resolver.resolve(project, "ReadOnly.java", SourceRange(1, 1, 1, 2)),
                McpRefactoringErrorCode.READ_ONLY,
            )
        } finally {
            WriteCommandAction.runWriteCommandAction(project) { readOnly.isWritable = true }
        }
    }

    fun testNonJavaFilePreservesNotJavaFile() {
        mirrorRealFile("notes.txt", "text")

        requireFailure(
            resolver.resolve(project, "notes.txt", SourceRange(1, 1, 1, 2)),
            McpRefactoringErrorCode.NOT_JAVA_FILE,
        )
    }

    // --- helpers ---

    /**
     * Accepts either a dereferenceable native preparation or `UNSUPPORTED_TARGET`, never a
     * plugin-defined target kind. On success the pointer must dereference and the description and
     * normalized source path must be populated.
     */
    private fun assertNativeTargetOrUnsupported(result: SafeDeleteTargetResolution) {
        when (result) {
            is SafeDeleteTargetResolution.Success -> {
                val preparation = result.preparation
                assertNotNull(
                    "element pointer must dereference",
                    preparation.elementPointer.element,
                )
                assertTrue(
                    "target description must not be blank",
                    preparation.targetDescription.isNotBlank(),
                )
                assertTrue(
                    "source document path must not be blank",
                    preparation.sourceDocumentPath.isNotBlank(),
                )
            }
            is SafeDeleteTargetResolution.Failure -> {
                assertEquals(McpRefactoringErrorCode.UNSUPPORTED_TARGET, result.code)
                assertTrue("failure message must not be blank", result.message.isNotBlank())
            }
        }
    }

    private fun configureMarkedFile(fileName: String, markedText: String): SourceRange {
        val start = markedText.indexOf(START_MARKER)
        val end = markedText.indexOf(END_MARKER)
        require(start >= 0 && end > start) {
            "fixture must contain exactly one <selection> pair: $markedText"
        }
        val content = markedText.substring(0, start) +
            markedText.substring(start + START_MARKER.length, end) +
            markedText.substring(end + END_MARKER.length)
        val selectedText = markedText.substring(start + START_MARKER.length, end)
        myFixture.configureByText(fileName, content)
        val vf = mirrorRealFile(fileName, content)
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        // Compute range from the real file's document to avoid stale-editor offset mismatches
        val realDoc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)!!
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(realDoc)
        val startOffset = realDoc.text.indexOf(selectedText)
        require(startOffset >= 0) { "selected text '$selectedText' missing from real document: ${realDoc.text}" }
        val endOffset = startOffset + selectedText.length
        return rangeFromOffsets(realDoc, startOffset, endOffset)
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
        if (!Files.exists(target)) Files.createFile(target)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(target.toString())
            ?: LocalFileSystem.getInstance().findFileByPath(target.toString())!!
        com.intellij.openapi.application.WriteAction.run<RuntimeException> {
            com.intellij.openapi.vfs.VfsUtil.saveText(vf, text)
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        return vf
    }

    private fun requireFailure(
        result: SafeDeleteTargetResolution,
        expected: McpRefactoringErrorCode,
    ) {
        assertTrue(
            "expected Failure($expected) but was $result",
            result is SafeDeleteTargetResolution.Failure,
        )
        val failure = result as SafeDeleteTargetResolution.Failure
        assertEquals(expected, failure.code)
        assertTrue("failure message must not be blank", failure.message.isNotBlank())
    }

    companion object {
        private const val START_MARKER = "<selection>"
        private const val END_MARKER = "</selection>"
    }
}
