package com.example.airefactoring.refactoring.safedelete

import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.dispatchAllEventsInIdeEventQueue
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IntellijSafeDeleteExecutorTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = JavaSafeDeleteTargetResolver()
    private val executor = IntellijSafeDeleteExecutor()

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testDeletesUnreferencedMethodHeadlesslyWithOneUndo() {
        mirrorRealFile(
            "UnusedMethod.java",
            "class UnusedMethod { void unusedMethod() {} }",
        )
        val preparation = resolve("UnusedMethod.java", "unusedMethod")
        val original = documentText("UnusedMethod.java")

        val result = runWithThrowingDialog {
            runExecutor { executor.delete(project, preparation) }
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertEquals(preparation.targetDescription, result.targetDescription)
        assertEquals(0, result.nativeUsageCount)
        assertFalse(
            "the unreferenced method must be deleted",
            documentText("UnusedMethod.java").contains("unusedMethod"),
        )

        val undoManager = UndoManager.getInstance(project)
        assertTrue("Safe Delete must be available as one global Undo", undoManager.isUndoAvailable(null))
        val previous = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undoManager.undo(null)
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(original, documentText("UnusedMethod.java"))
    }

    fun testReferencedMethodThrowsConflictWithoutMutation() {
        mirrorRealFile(
            "StillUsed.java",
            "class StillUsed { void stillUsed() {} void call() { stillUsed(); } }",
        )
        val preparation = resolve("StillUsed.java", "stillUsed")
        val original = documentText("StillUsed.java")

        try {
            runWithThrowingDialog {
                runExecutor { executor.delete(project, preparation) }
            }
            fail("expected SafeDeleteConflictException for the referenced method")
        } catch (e: SafeDeleteConflictException) {
            assertTrue("conflict message must not be blank", e.message.orEmpty().isNotBlank())
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(original, documentText("StillUsed.java"))
    }

    fun testStalePointerThrowsPreparationExceptionWithoutMutation() {
        mirrorRealFile(
            "Stale.java",
            "class Stale { void staleMethod() {} }",
        )
        val preparation = resolve("Stale.java", "staleMethod")
        val document = document("Stale.java")
        WriteCommandAction.runWriteCommandAction(project) {
            val start = document.text.indexOf("staleMethod")
            document.replaceString(start, start + "staleMethod".length, "renamedMethod")
        }
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val afterEdit = documentText("Stale.java")

        try {
            runExecutor { executor.delete(project, preparation) }
            fail("expected SafeDeletePreparationException for the stale pointer")
        } catch (e: SafeDeletePreparationException) {
            assertTrue(e.message.orEmpty().contains("changed"))
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(afterEdit, documentText("Stale.java"))
    }

    // --- helpers ---

    private fun resolve(fileName: String, name: String): SafeDeletePreparation {
        val document = document(fileName)
        val offset = document.text.indexOf(name)
        require(offset >= 0) { "name '$name' missing from $fileName" }
        val result = resolver.resolve(
            project,
            fileName,
            range(document, offset, offset + name.length),
        )
        assertTrue("expected successful resolution but was $result", result is SafeDeleteTargetResolution.Success)
        return (result as SafeDeleteTargetResolution.Success).preparation
    }

    private fun mirrorRealFile(fileName: String, text: String): VirtualFile {
        val target = Path.of(project.basePath!!, fileName)
        Files.createDirectories(target.parent)
        if (!Files.exists(target)) Files.createFile(target)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(target.toString())!!
        WriteAction.run<RuntimeException> { VfsUtil.saveText(virtualFile, text) }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return virtualFile
    }

    /** Runs [block] with a [TestDialog] installed that throws, proving no UI was requested. */
    private fun <T> runWithThrowingDialog(block: () -> T): T {
        val throwingDialog = object : TestDialog {
            override fun show(message: String): Int =
                throw AssertionError("Safe Delete must not open a dialog: $message")
        }
        val previous = TestDialogManager.setTestDialog(throwingDialog)
        try {
            return block()
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
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

    private fun documentText(path: String): String = document(path).text

    private fun document(path: String): Document = FileDocumentManager.getInstance()
        .getDocument(virtualFile(path))!!

    private fun virtualFile(path: String): VirtualFile = LocalFileSystem.getInstance()
        .findFileByPath(Path.of(project.basePath!!, path).toString())!!

    private fun <T> runExecutor(block: suspend () -> T): T {
        val pool = Executors.newSingleThreadExecutor()
        return try {
            val future = pool.submit<T> { runBlocking { block() } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
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
