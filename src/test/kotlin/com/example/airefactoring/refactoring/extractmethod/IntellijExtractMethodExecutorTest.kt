package com.example.airefactoring.refactoring.extractmethod

import com.example.airefactoring.refactoring.SourceRange
import com.example.airefactoring.refactoring.RecordingNativeRefactoringDocumentPersister
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Proves the real platform-backed [IntellijExtractMethodExecutor] performs a genuine Extract
 * Method refactoring and maps native preparation refusals to [ExtractMethodPreparationException]
 * instead of a generic exception. Like [ExtractMethodSelectionResolverTest], the light fixture
 * keeps the editor file in an in-memory `temp://` file system and reuses `project.basePath`, so
 * each fixture is mirrored into a unique real file under the base path for the resolver's real
 * local-file lookup.
 */
class IntellijExtractMethodExecutorTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = ExtractMethodSelectionResolver()
    private val executor = IntellijExtractMethodExecutor()

    private val calcMarked =
        "public class Calc {\n" +
            "    void print(int value) {\n" +
            "        <selection>System.out.println(value);</selection>\n" +
            "    }\n" +
            "}"

    // --- helpers ---

    /** Derives the 1-based [SourceRange] from the fixture editor's `<selection>` markers. */
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

    private fun requireSuccess(result: SelectionResolution): ExtractMethodSelection {
        assertTrue("expected Success but was $result", result is SelectionResolution.Success)
        return (result as SelectionResolution.Success).selection
    }

    private fun resolveSelection(fileName: String, range: SourceRange): ExtractMethodSelection =
        requireSuccess(resolver.resolve(project, fileName, range))

    // --- Step 1: real platform-backed extraction ---

    fun testExtractionProducesCallSiteAndHelperMethod() {
        myFixture.configureByText("CalcExec.java", calcMarked)
        val range = sourceRangeFromEditor()
        mirrorRealFile("CalcExec.java", myFixture.editor.document.text)

        val selection = resolveSelection("CalcExec.java", range)
        val summary = executor.extract(project, selection.file, selection.elements, "printValue")

        assertEquals("Extracted method 'printValue'.", summary)
        val text = selection.document.text
        assertTrue("call site missing in:\n$text", text.contains("printValue(value);"))
        assertTrue("helper method missing in:\n$text", text.contains("private void printValue(int value)"))
    }

    fun testSuccessfulExtractionPersistsOnlyTheExtractedFile() {
        myFixture.configureByText("CalcPersistence.java", calcMarked)
        val range = sourceRangeFromEditor()
        mirrorRealFile("CalcPersistence.java", myFixture.editor.document.text)
        val selection = resolveSelection("CalcPersistence.java", range)
        val persister = RecordingNativeRefactoringDocumentPersister()

        IntellijExtractMethodExecutor(persister)
            .extract(project, selection.file, selection.elements, "printValue")

        persister.assertPersistedExactly("CalcPersistence.java")
    }

    // --- Step 2a: preparation failure mapping ---

    fun testInvalidSelectionThrowsExtractMethodPreparationException() {
        // A `super()` call resolves to a statement but is refused by the native processor, which
        // returns null and maps to ExtractMethodPreparationException rather than a generic error.
        val marked =
            "public class Calc {\n" +
                "    Calc() {\n" +
                "        <selection>super();</selection>\n" +
                "    }\n" +
                "}"
        myFixture.configureByText("CalcCtor.java", marked)
        val range = sourceRangeFromEditor()
        mirrorRealFile("CalcCtor.java", myFixture.editor.document.text)
        val selection = requireSuccess(resolver.resolve(project, "CalcCtor.java", range))

        try {
            executor.extract(project, selection.file, selection.elements, "doIt")
            fail("expected ExtractMethodPreparationException for a super() selection")
        } catch (e: ExtractMethodPreparationException) {
            assertTrue("message must not be blank", e.message!!.isNotBlank())
        }
    }

    // --- Step 2b: single-document save contract ---

    fun testExtractionDoesNotSaveUnrelatedDocuments() {
        myFixture.configureByText("CalcSave.java", calcMarked)
        val range = sourceRangeFromEditor()
        mirrorRealFile("CalcSave.java", myFixture.editor.document.text)

        myFixture.configureByText("OtherSave.java", "public class Other { int x = 1; }")
        val other = mirrorRealFile("OtherSave.java", myFixture.editor.document.text)
        val otherDocument = FileDocumentManager.getInstance().getDocument(other)!!
        WriteCommandAction.runWriteCommandAction(project) {
            otherDocument.insertString(otherDocument.textLength, "\n// dirty")
        }
        assertTrue(
            "precondition: second document is unsaved",
            FileDocumentManager.getInstance().isDocumentUnsaved(otherDocument),
        )

        val selection = resolveSelection("CalcSave.java", range)
        executor.extract(project, selection.file, selection.elements, "printValue")

        assertTrue(
            "unrelated document must remain unsaved (executor must not call saveAllDocuments)",
            FileDocumentManager.getInstance().isDocumentUnsaved(otherDocument),
        )
        assertTrue(otherDocument.text.contains("// dirty"))
    }
}
