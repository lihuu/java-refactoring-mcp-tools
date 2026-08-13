package com.example.airefactoring.refactoring.inlinevariable

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path

class InlineVariableSelectionResolverTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = InlineVariableSelectionResolver()

    fun testDeclarationNameAndReferenceResolveSameLocalVariable() {
        val source = """
            class Price {
                int total() {
                    int subtotal = 10 + 20;
                    return subtotal + subtotal;
                }
            }
        """.trimIndent()
        val document = mirrorRealFile("Price.java", source)

        val declaration = resolveAt(document, "subtotal =")
        val reference = resolveAt(document, "subtotal +")

        assertEquals("subtotal", declaration.variable.name)
        assertSame(declaration.variable, reference.variable)
        assertEquals(2, declaration.references.size)
        assertTrue(declaration.references.all { it.resolve() == declaration.variable })
    }

    fun testTypeAndInitializerCoordinatesDoNotBroadenToDeclaration() {
        val document = mirrorRealFile(
            "ExactPoint.java",
            "class ExactPoint { int value() { int subtotal = 10 + 20; return subtotal; } }",
        )

        requireFailure(
            resolveAtRaw(document, "int subtotal", offsetInMarker = 1),
            McpRefactoringErrorCode.NO_TARGET_VARIABLE,
        )
        requireFailure(
            resolveAtRaw(document, "10 + 20"),
            McpRefactoringErrorCode.NO_TARGET_VARIABLE,
        )
    }

    fun testRejectsFieldParameterAndPatternVariable() {
        val document = mirrorRealFile(
            "NonLocal.java",
            """
                class NonLocal {
                    int field = 1;
                    int value(int input, Object candidate) {
                        if (candidate instanceof String text) return field + input + text.length();
                        return 0;
                    }
                }
            """.trimIndent(),
        )

        requireFailure(resolveAtRaw(document, "field ="), McpRefactoringErrorCode.NO_TARGET_VARIABLE)
        requireFailure(resolveAtRaw(document, "input,"), McpRefactoringErrorCode.NO_TARGET_VARIABLE)
        requireFailure(resolveAtRaw(document, "text)"), McpRefactoringErrorCode.NO_TARGET_VARIABLE)
    }

    fun testRejectsMissingInitializerUnusedAndReassignedLocals() {
        requireUnsupported(
            "MissingInitializer.java",
            "class MissingInitializer { int f() { int value; value = 1; return value; } }",
            "value;",
        )
        requireUnsupported(
            "Unused.java",
            "class Unused { int f() { int value = 1; return 2; } }",
            "value =",
        )
        requireUnsupported(
            "Reassigned.java",
            "class Reassigned { int f() { int value = 1; value = 2; return value; } }",
            "value =",
        )
    }

    fun testRejectsResourceVariable() {
        val document = mirrorRealFile(
            "Resource.java",
            """
                import java.io.ByteArrayInputStream;
                class Resource {
                    int f() throws Exception {
                        try (ByteArrayInputStream input = new ByteArrayInputStream(new byte[0])) {
                            return input.read();
                        }
                    }
                }
            """.trimIndent(),
        )

        requireFailure(
            resolveAtRaw(document, "input ="),
            McpRefactoringErrorCode.UNSUPPORTED_VARIABLE,
        )
    }

    fun testPreservesPointAndFileFailures() {
        requireFailure(
            resolver.resolve(project, "Missing.java", 1, 1),
            McpRefactoringErrorCode.FILE_NOT_FOUND,
        )
        mirrorRealFile("Bounds.java", "class Bounds {}")
        requireFailure(
            resolver.resolve(project, "Bounds.java", 50, 1),
            McpRefactoringErrorCode.INVALID_RANGE,
        )
    }

    private fun resolveAt(
        document: Document,
        marker: String,
        occurrence: Int = 0,
    ): InlineVariableSelection {
        val result = resolveAtRaw(document, marker, occurrence)
        assertTrue(
            "expected success but was $result",
            result is InlineVariableSelectionResolution.Success,
        )
        return (result as InlineVariableSelectionResolution.Success).selection
    }

    private fun resolveAtRaw(
        document: Document,
        marker: String,
        occurrence: Int = 0,
        offsetInMarker: Int = 0,
    ): InlineVariableSelectionResolution {
        var offset = -1
        repeat(occurrence + 1) {
            offset = document.text.indexOf(marker, offset + 1)
            assertTrue("marker '$marker' missing", offset >= 0)
        }
        offset += offsetInMarker
        val lineIndex = document.getLineNumber(offset)
        val line = lineIndex + 1
        val column = offset - document.getLineStartOffset(lineIndex) + 1
        val file = FileDocumentManager.getInstance().getFile(document)!!
        return resolver.resolve(project, file.name, line, column)
    }

    private fun mirrorRealFile(fileName: String, source: String): Document {
        val target = Path.of(project.basePath!!, fileName)
        Files.createDirectories(target.parent)
        Files.writeString(target, source)
        val virtualFile = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(target.toString())!!
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        PsiDocumentManager.getInstance(project).commitDocument(document)
        return document
    }

    private fun requireUnsupported(fileName: String, source: String, marker: String) {
        requireFailure(
            resolveAtRaw(mirrorRealFile(fileName, source), marker),
            McpRefactoringErrorCode.UNSUPPORTED_VARIABLE,
        )
    }

    private fun requireFailure(
        result: InlineVariableSelectionResolution,
        expected: McpRefactoringErrorCode,
    ) {
        assertTrue(
            "expected Failure($expected) but was $result",
            result is InlineVariableSelectionResolution.Failure,
        )
        val failure = result as InlineVariableSelectionResolution.Failure
        assertEquals(expected, failure.code)
        assertTrue(failure.message.isNotBlank())
    }
}
