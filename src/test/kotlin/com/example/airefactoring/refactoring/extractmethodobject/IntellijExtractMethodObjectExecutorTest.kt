package com.example.airefactoring.refactoring.extractmethodobject

import com.example.airefactoring.refactoring.RecordingNativeRefactoringDocumentPersister
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.dispatchAllEventsInIdeEventQueue
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IntellijExtractMethodObjectExecutorTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = ExtractMethodObjectSelectionResolver()

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testReplacesMethodBodyPersistsExactFileAndUndoRestores() {
        val servicePath = "example/OrderService.java"
        val callerPath = "example/OrderClient.java"
        mirrorRealFile(servicePath, """
            package example;
            public class OrderService {
                public double price(int quantity, double unit) {
                    double subtotal = quantity * unit;
                    double discount = quantity > 10 ? 0.1 : 0.0;
                    return subtotal * (1 - discount);
                }
            }
        """.trimIndent())
        mirrorRealFile(callerPath, """
            package example;
            public class OrderClient {
                void call() { System.out.println(new OrderService().price(5, 2.5)); }
            }
        """.trimIndent())
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val prep = resolve(servicePath, "price", "PriceObject", "invoke")
        val before = listOf(servicePath, callerPath).associateWith { documentText(it) }
        val persister = RecordingNativeRefactoringDocumentPersister()

        val result = runExecutorWithNoDialog {
            IntellijExtractMethodObjectExecutor(persister).replace(project, prep)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertEquals("price", result.methodName)
        assertTrue(result.methodObjectClass.contains("PriceObject"))
        assertEquals("invoke", result.methodObjectMethodName)
        assertTrue(result.affectedFiles.any { it.contains("OrderService") })
        persister.assertPersistedExactly(*result.affectedFiles.toTypedArray())

        // Original method must delegate; method object inner class must exist with fields.
        val serviceText = documentText(servicePath)
        assertTrue(serviceText.contains("PriceObject"))
        assertTrue(serviceText.contains("invoke(") || serviceText.contains("new PriceObject("))
        // Caller unchanged (delegation preserves call sites).
        assertTrue(documentText(callerPath).contains("price(5, 2.5)"))

        // One global Undo restores both files.
        val um = UndoManager.getInstance(project)
        assertTrue(um.isUndoAvailable(null))
        val prev = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            um.undo(null)
        } finally {
            TestDialogManager.setTestDialog(prev)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        before.forEach { (p, txt) -> assertEquals(p, txt, documentText(p)) }
    }

    fun testRejectsStaleMethodSnapshotBeforeMutation() {
        val servicePath = "example/StaleService.java"
        val callerPath = "example/StaleCaller.java"
        mirrorRealFile(servicePath, """
            package example;
            public class StaleService {
                public double price(int quantity, double unit) {
                    double subtotal = quantity * unit;
                    return subtotal * 0.9;
                }
            }
        """.trimIndent())
        mirrorRealFile(callerPath, """
            package example;
            public class StaleCaller { void call(){ System.out.println(new StaleService().price(5, 2.5)); } }
        """.trimIndent())
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val prep = resolve(servicePath, "price", "PriceObject", "invoke")

        // Mutate the method text after resolve.
        val newText = documentText(servicePath).replace("subtotal * 0.9", "subtotal * 0.8")
        val vf = LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, servicePath).toString())!!
        WriteAction.run<RuntimeException> { VfsUtil.saveText(vf, newText) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val before = listOf(servicePath, callerPath).associateWith { documentText(it) }

        try {
            runExecutor { IntellijExtractMethodObjectExecutor().replace(project, prep) }
            fail("expected stale preparation to be rejected")
        } catch (e: ExtractMethodObjectPreparationException) {
            assertTrue(e.message!!.isNotBlank())
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        before.forEach { (p, txt) -> assertEquals(p, txt, documentText(p)) }
    }

    private fun resolve(servicePath: String, methodName: String, className: String, methodObjectMethod: String): ExtractMethodObjectPreparation {
        val range = rangeForMethod(servicePath, methodName)
        val res = resolver.resolve(project, servicePath, range, className, methodObjectMethod)
        assertTrue("expected Success but was $res", res is ExtractMethodObjectSelectionResolution.Success)
        return (res as ExtractMethodObjectSelectionResolution.Success).preparation
    }

    private fun rangeForMethod(path: String, methodName: String): SourceRange {
        val doc = document(path)
        val off = doc.text.indexOf(methodName)
        assertTrue(off >= 0)
        return range(doc, off, off + methodName.length)
    }

    private fun range(doc: com.intellij.openapi.editor.Document, startOff: Int, endOff: Int): SourceRange {
        fun pos(off: Int): Pair<Int, Int> {
            val line = doc.getLineNumber(off)
            return (line + 1) to (off - doc.getLineStartOffset(line) + 1)
        }
        val (sl, sc) = pos(startOff)
        val (el, ec) = pos(endOff)
        return SourceRange(sl, sc, el, ec)
    }

    private fun document(path: String): com.intellij.openapi.editor.Document {
        val vf = LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, path).toString())!!
        return FileDocumentManager.getInstance().getDocument(vf)!!
    }

    private fun documentText(path: String): String = document(path).text

    private fun mirrorRealFile(path: String, text: String): com.intellij.openapi.vfs.VirtualFile {
        val t = Path.of(project.basePath!!, path)
        Files.createDirectories(t.parent)
        if (!Files.exists(t)) Files.createFile(t)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(t.toString())!!
        WriteAction.run<RuntimeException> { VfsUtil.saveText(vf, text) }
        return vf
    }

    private fun <T> runExecutor(block: suspend () -> T): T {
        val pool = Executors.newSingleThreadExecutor()
        return try {
            val f = pool.submit<T> { runBlocking { block() } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (System.nanoTime() < deadline && !f.isDone) {
                dispatchAllEventsInIdeEventQueue()
                Thread.sleep(1)
            }
            try {
                f.get(1, TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun <T> runExecutorWithNoDialog(block: suspend () -> T): T {
        val throwing = object : TestDialog {
            override fun show(message: String): Int =
                throw AssertionError("must not show dialog: $message")
        }
        val prev = TestDialogManager.setTestDialog(throwing)
        try {
            return runExecutor(block)
        } finally {
            TestDialogManager.setTestDialog(prev)
        }
    }
}
