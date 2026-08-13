package com.example.airefactoring.refactoring.inlinevariable

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.dispatchAllEventsInIdeEventQueue
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IntellijInlineVariableExecutorTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = InlineVariableSelectionResolver()
    private val executor = IntellijInlineVariableExecutor()

    fun testInlinesAllReferencesDeletesDeclarationAndPreservesPrecedence() {
        val selection = resolve(
            "InlineAll.java",
            "class InlineAll { int value() { int sum = 1 + 2; return sum * 3 + sum; } }",
            "sum =",
        )

        val result = runExecutor { executor.inline(project, selection) }
        PsiDocumentManager.getInstance(project).commitDocument(selection.document)

        assertEquals("sum", result.variableName)
        assertEquals(2, result.inlinedOccurrenceCount)
        assertEquals(
            "Inlined 2 occurrences of local variable 'sum' and removed its declaration.",
            result.summary,
        )
        assertFalse(selection.file.text.contains("int sum"))
        assertTrue(selection.file.text, selection.file.text.contains("(1 + 2) * 3"))
        assertTrue(selection.file.text, selection.file.text.contains("+ 1 + 2;"))
    }

    fun testChangedInitializerDependencyFailsWithoutMutation() {
        val selection = resolve(
            "Conflict.java",
            """
                class Conflict {
                    int value() {
                        int base = 1;
                        int result = base + 1;
                        base = 2;
                        return result;
                    }
                }
            """.trimIndent(),
            "result =",
        )
        val original = selection.document.text

        try {
            runExecutor { executor.inline(project, selection) }
            fail("expected conflict")
        } catch (expected: InlineVariableConflictException) {
            assertTrue(expected.message!!.isNotBlank())
        }

        assertEquals(original, selection.document.text)
    }

    fun testSuccessfulBatchExecutionDoesNotOpenDialog() {
        val selection = resolve(
            "NoDialog.java",
            "class NoDialog { int value() { int sum = 1 + 2; return sum; } }",
            "sum =",
        )
        val previous = TestDialogManager.setTestDialog(
            TestDialog { message -> throw AssertionError("Unexpected dialog: $message") },
        )
        try {
            runExecutor { executor.inline(project, selection) }
        } finally {
            TestDialogManager.setTestDialog(previous)
        }

        assertFalse(selection.file.text.contains("int sum"))
    }

    fun testStaleSelectionFailsWithoutAdditionalMutation() {
        val selection = resolve(
            "Stale.java",
            "class Stale { int value() { int sum = 1 + 2; return sum; } }",
            "sum =",
        )
        WriteCommandAction.runWriteCommandAction(project) {
            selection.variable.delete()
        }
        PsiDocumentManager.getInstance(project).commitDocument(selection.document)
        val sourceAfterExternalWrite = selection.document.text

        try {
            runExecutor { executor.inline(project, selection) }
            fail("expected stale preparation failure")
        } catch (expected: InlineVariablePreparationException) {
            assertTrue(expected.message!!.contains("changed"))
        }

        assertEquals(sourceAfterExternalWrite, selection.document.text)
    }

    fun testInlineVariableDoesNotSaveUnrelatedDirtyDocument() {
        val selection = resolve(
            "InlineSaveTarget.java",
            "class InlineSaveTarget { int value() { int sum = 1 + 2; return sum; } }",
            "sum =",
        )
        val unrelated = createAndDirtyRealJavaFile("InlineUnrelated.java")
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(unrelated))

        runExecutor { executor.inline(project, selection) }

        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(unrelated))
    }

    fun testOneGlobalUndoRestoresExactOriginalSource() {
        val selection = resolve(
            "UndoInline.java",
            "class UndoInline { int value() { int sum = 1 + 2; return sum + sum; } }",
            "sum =",
        )
        val original = selection.document.text

        runExecutor { executor.inline(project, selection) }

        val undoManager = UndoManager.getInstance(project)
        assertTrue(undoManager.isUndoAvailable(null))
        val previous = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undoManager.undo(null)
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
        PsiDocumentManager.getInstance(project).commitDocument(selection.document)
        assertEquals(original, selection.document.text)
    }

    private fun resolve(
        fileName: String,
        source: String,
        marker: String,
    ): InlineVariableSelection {
        val virtualFile = mirrorRealFile(fileName, source)
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        val offset = document.text.indexOf(marker)
        assertTrue("marker '$marker' missing", offset >= 0)
        val lineIndex = document.getLineNumber(offset)
        val result = resolver.resolve(
            project,
            fileName,
            lineIndex + 1,
            offset - document.getLineStartOffset(lineIndex) + 1,
        )
        assertTrue(
            "expected successful selection but was $result",
            result is InlineVariableSelectionResolution.Success,
        )
        return (result as InlineVariableSelectionResolution.Success).selection
    }

    private fun createAndDirtyRealJavaFile(fileName: String): Document {
        val virtualFile = mirrorRealFile(fileName, "class Unrelated { int value = 1; }")
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(document.textLength, "\n// dirty")
        }
        return document
    }

    private fun mirrorRealFile(fileName: String, source: String): VirtualFile {
        val target = Path.of(project.basePath!!, fileName)
        Files.createDirectories(target.parent)
        Files.writeString(target, source)
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(target.toString())!!
    }

    private fun <T> runExecutor(block: suspend () -> T): T {
        val pool = Executors.newSingleThreadExecutor()
        return try {
            val future = pool.submit<T> { runBlocking { block() } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (System.nanoTime() < deadline && !future.isDone) {
                dispatchAllEventsInIdeEventQueue()
                Thread.sleep(1)
            }
            try {
                future.get(1, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
