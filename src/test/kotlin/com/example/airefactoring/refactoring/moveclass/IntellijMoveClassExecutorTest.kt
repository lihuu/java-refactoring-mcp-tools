package com.example.airefactoring.refactoring.moveclass

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

class IntellijMoveClassExecutorTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = MoveClassSelectionResolver()

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testMovesClassPersistsExactFilesAndUndoRestores() {
        val classPath = "example/OrderService.java"
        val callerPath = "example/OrderClient.java"
        mirrorRealFile(classPath, """
            package example;
            public class OrderService {
                public double price(int q, double u) { return q * u; }
            }
        """.trimIndent())
        mirrorRealFile(callerPath, """
            package example;
            public class OrderClient {
                void call() { System.out.println(new OrderService().price(5, 2.5)); }
            }
        """.trimIndent())
        mirrorRealFile("example/api/.keep", "")
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val prep = resolve(classPath, "OrderService", "example.api")
        val before = listOf(classPath, callerPath).associateWith { documentText(it) }
        val persister = RecordingNativeRefactoringDocumentPersister()

        val result = runExecutorWithNoDialog {
            IntellijMoveClassExecutor(persister).move(project, prep)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        LocalFileSystem.getInstance().refresh(false)

        assertEquals("example.OrderService", result.sourceClass)
        assertEquals("example.api", result.targetPackage)
        assertTrue(result.affectedFiles.any { it.contains("OrderService") })
        assertTrue(result.affectedFiles.any { it.contains("OrderClient") })
        persister.assertPersistedExactly(*result.affectedFiles.toTypedArray())

        // Moved file must declare the new package; caller must import it.
        val movedVf = LocalFileSystem.getInstance().findFileByPath(
            Path.of(project.basePath!!, "example/api/OrderService.java").toString(),
        )
        assertNotNull("moved class file must exist under example/api", movedVf)
        val movedText = FileDocumentManager.getInstance().getDocument(movedVf!!)!!.text
        assertTrue(movedText.contains("package example.api;"))
        assertTrue(documentText(callerPath).contains("import example.api.OrderService;"))

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
        LocalFileSystem.getInstance().refresh(false)
        before.forEach { (p, txt) -> assertEquals(p, txt, documentText(p)) }
    }

    fun testRejectsStaleClassSnapshotBeforeMutation() {
        val classPath = "example/StaleService.java"
        val callerPath = "example/StaleCaller.java"
        mirrorRealFile(classPath, """
            package example;
            public class StaleService {
                public void m() {}
            }
        """.trimIndent())
        mirrorRealFile(callerPath, """
            package example;
            public class StaleCaller { void call(){ new StaleService().m(); } }
        """.trimIndent())
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val prep = resolve(classPath, "StaleService", "example.api")

        // Mutate the class text after resolve.
        val newText = documentText(classPath).replace("public void m()", "public void m2()")
        val vf = LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, classPath).toString())!!
        WriteAction.run<RuntimeException> { VfsUtil.saveText(vf, newText) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val before = listOf(classPath, callerPath).associateWith { documentText(it) }

        try {
            runExecutor { IntellijMoveClassExecutor().move(project, prep) }
            fail("expected stale preparation to be rejected")
        } catch (e: MoveClassPreparationException) {
            assertTrue(e.message!!.isNotBlank())
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        before.forEach { (p, txt) -> assertEquals(p, txt, documentText(p)) }
    }

    private fun resolve(classPath: String, className: String, targetPackage: String): MoveClassPreparation {
        val range = rangeForClass(classPath, className)
        val res = resolver.resolve(project, classPath, range, targetPackage)
        assertTrue("expected Success but was $res", res is MoveClassSelectionResolution.Success)
        return (res as MoveClassSelectionResolution.Success).preparation
    }

    private fun rangeForClass(path: String, className: String): SourceRange {
        val doc = document(path)
        val off = doc.text.indexOf(className)
        assertTrue(off >= 0)
        return range(doc, off, off + className.length)
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
