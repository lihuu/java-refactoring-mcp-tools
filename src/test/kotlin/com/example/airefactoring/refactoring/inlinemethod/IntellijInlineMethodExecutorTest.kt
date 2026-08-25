package com.example.airefactoring.refactoring.inlinemethod

import com.example.airefactoring.refactoring.RecordingNativeRefactoringDocumentPersister
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
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

class IntellijInlineMethodExecutorTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testInlinesAllCrossFileCallsDeletesMethodPersistsOnlyAffectedFilesAndUndoRestoresSources() {
        fixture()
        val rulesBefore = textOf("example/PricingRules.java")
        val checkoutBefore = textOf("example/Checkout.java")
        val preparation = preparation()
        val persister = RecordingNativeRefactoringDocumentPersister()

        val result = runExecutor { withoutDialogs { IntellijInlineMethodExecutor(persister).inline(project, preparation) } }

        assertEquals(2, result.inlinedOccurrenceCount)
        assertEquals(listOf("example/Checkout.java", "example/PricingRules.java"), result.affectedFiles)
        assertFalse(textOf("example/PricingRules.java").contains("addTax"))
        assertFalse(textOf("example/Checkout.java").contains("PricingRules.addTax"))
        persister.assertPersistedExactly("example/Checkout.java", "example/PricingRules.java")

        val previousDialog = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            UndoManager.getInstance(project).undo(null)
        } finally {
            TestDialogManager.setTestDialog(previousDialog)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(rulesBefore, textOf("example/PricingRules.java"))
        assertEquals(checkoutBefore, textOf("example/Checkout.java"))
    }

    fun testRejectsStaleMethodSnapshotBeforeNativeMutation() {
        fixture()
        val preparation = preparation()
        val document = FileDocumentManager.getInstance().getDocument(file("example/PricingRules.java"))!!
        val amount = document.text.indexOf("amount + 5")
        WriteCommandAction.runWriteCommandAction(project) { document.replaceString(amount, amount + "amount + 5".length, "amount + 6") }
        PsiDocumentManager.getInstance(project).commitDocument(document)

        try {
            runExecutor { withoutDialogs { IntellijInlineMethodExecutor().inline(project, preparation) } }
            fail("Expected a stale preparation failure")
        } catch (_: InlineMethodPreparationException) {
            // expected
        }
        assertTrue(textOf("example/PricingRules.java").contains("addTax"))
        assertTrue(textOf("example/PricingRules.java").contains("amount + 6"))
    }

    private fun fixture() {
        mirror("example/PricingRules.java", """
            package example;
            public final class PricingRules {
                public static int addTax(int amount) { return amount + 5; }
            }
        """.trimIndent())
        mirror("example/Checkout.java", """
            package example;
            public final class Checkout {
                public int total(int amount) { return PricingRules.addTax(amount) + PricingRules.addTax(10); }
            }
        """.trimIndent())
    }

    private suspend fun <T> withoutDialogs(block: suspend () -> T): T {
        val previous = TestDialogManager.setTestDialog(object : TestDialog {
            override fun show(message: String): Int = throw AssertionError("Inline Method opened a dialog: $message")
        })
        return try {
            block()
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
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
            } catch (exception: ExecutionException) {
                throw exception.cause ?: exception
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun preparation(): InlineMethodPreparation {
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val document = FileDocumentManager.getInstance().getDocument(file("example/PricingRules.java"))!!
        val start = document.text.indexOf("addTax")
        val line = document.getLineNumber(start)
        val range = SourceRange(line + 1, start - document.getLineStartOffset(line) + 1, line + 1, start - document.getLineStartOffset(line) + 7)
        return (InlineMethodSelectionResolver().resolve(project, "example/PricingRules.java", range) as InlineMethodSelectionResolution.Success).preparation
    }

    private fun textOf(path: String) = FileDocumentManager.getInstance().getDocument(file(path))!!.text
    private fun file(path: String) = LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, path).toString())!!
    private fun mirror(path: String, text: String) {
        val target = Path.of(project.basePath!!, path)
        Files.createDirectories(target.parent)
        if (!Files.exists(target)) Files.createFile(target)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(target.toString())!!
        WriteAction.run<RuntimeException> { VfsUtil.saveText(virtualFile, text) }
        FileDocumentManager.getInstance().getDocument(virtualFile)?.let {
            PsiDocumentManager.getInstance(project).commitDocument(it)
        }
    }
}
