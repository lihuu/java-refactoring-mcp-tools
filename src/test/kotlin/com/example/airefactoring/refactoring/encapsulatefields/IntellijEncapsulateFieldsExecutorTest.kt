package com.example.airefactoring.refactoring.encapsulatefields

import com.example.airefactoring.refactoring.RecordingNativeRefactoringDocumentPersister
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
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

class IntellijEncapsulateFieldsExecutorTest : LightJavaCodeInsightFixtureTestCase() {
    private val resolver = EncapsulateFieldsSelectionResolver()
    private val executor = IntellijEncapsulateFieldsExecutor()
    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21
    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testEncapsulatesAndReportsFacts() {
        val (orderFile, callerFile) = fixture()
        val prep = prepare(orderFile, listOf("amount", "status"), listOf("getAmount", "setAmount", "getStatus", "setStatus"))
        val result = runExecutor { execWithNoDialog { executor.encapsulate(project, prep) } }
        assertEquals(listOf("amount", "status"), result.fieldNames)
        assertEquals("private", result.fieldsVisibility)
        assertEquals("public", result.accessorsVisibility)
        assertTrue(result.nativeUsageCount >= 2)
        assertNotNull(result.affectedFiles)
        assertTrue(result.affectedFiles!!.any { it.contains("Encapsulate") })
    }

    fun testPersistsSourceAndCaller() {
        val (orderFile, _) = fixture()
        val prep = prepare(orderFile, listOf("amount"), listOf("getAmount", "setAmount"))
        val persister = RecordingNativeRefactoringDocumentPersister()
        val result = runExecutor { execWithNoDialog { IntellijEncapsulateFieldsExecutor(persister).encapsulate(project, prep) } }
        persister.assertPersistedExactly(*requireNotNull(result.affectedFiles).toTypedArray())
    }

    fun testOneUndoRestoresFiles() {
        val (orderFile, callerFile) = fixture()
        val orderText = orderFile.text; val callerText = callerFile.text
        val prep = prepare(orderFile, listOf("amount"), listOf("getAmount", "setAmount"))
        runExecutor { execWithNoDialog { executor.encapsulate(project, prep) } }
        val um = UndoManager.getInstance(project)
        assertTrue(um.isUndoAvailable(null))
        val prev = TestDialogManager.setTestDialog(TestDialog.OK)
        try { um.undo(null) } finally { TestDialogManager.setTestDialog(prev) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(orderText, orderFile.text)
        assertEquals(callerText, callerFile.text)
    }

    private fun fixture(): Pair<PsiJavaFile, PsiJavaFile> {
        val order = mirrorRealFile("example/EncapsulateOrder.java", """
            package example;
            public class EncapsulateOrder {
                int amount;
                String status;
            }
        """.trimIndent())
        val caller = mirrorRealFile("example/EncapsulateOrderCaller.java", """
            package example;
            public class EncapsulateOrderCaller {
                int read(EncapsulateOrder o) { return o.amount; }
                void write(EncapsulateOrder o, String s) { o.status = s; }
            }
        """.trimIndent())
        return Pair(PsiManager.getInstance(project).findFile(order) as PsiJavaFile, PsiManager.getInstance(project).findFile(caller) as PsiJavaFile)
    }

    private fun prepare(file: PsiJavaFile, fieldNames: List<String>, accessorNames: List<String>): EncapsulateFieldsPreparation {
        // accessorNames is flat list: get1,set1,get2,set2...
        val getterNames = mutableListOf<String>()
        val setterNames = mutableListOf<String>()
        for (i in fieldNames.indices) {
            getterNames.add(accessorNames[i * 2])
            setterNames.add(accessorNames[i * 2 + 1])
        }
        val fieldLines = fieldNames.map { lineOf("example/EncapsulateOrder.java", it) }
        val fieldCols = fieldNames.map { colOf("example/EncapsulateOrder.java", it) }
        val fieldEndLines = fieldNames.map { lineEndOf("example/EncapsulateOrder.java", it) }
        val fieldEndCols = fieldNames.map { colEndOf("example/EncapsulateOrder.java", it) }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val res = resolver.resolve(project, "example/EncapsulateOrder.java", fieldLines, fieldCols, fieldEndLines, fieldEndCols, getterNames, setterNames, "private", "public", true, true, true)
        assertTrue("expected success but was $res", res is EncapsulateFieldsSelectionResolution.Success)
        return (res as EncapsulateFieldsSelectionResolution.Success).preparation
    }

    private fun lineOf(path: String, needle: String): Int = rangeOf(path, needle).first
    private fun colOf(path: String, needle: String): Int = rangeOf(path, needle).second
    private fun lineEndOf(path: String, needle: String): Int = rangeEndOf(path, needle).first
    private fun colEndOf(path: String, needle: String): Int = rangeEndOf(path, needle).second

    private fun rangeOf(path: String, needle: String): Pair<Int, Int> {
        val vf = LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, path).toString())!!
        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        val off = doc.text.indexOf(needle)
        assertTrue("'$needle' missing from $path: ${doc.text}", off >= 0)
        // ensure we pick field declaration, not usage: find "int amount" or "String status"
        // For amount/status, first occurrence is declaration, ok
        val line = doc.getLineNumber(off)
        return (line + 1) to (off - doc.getLineStartOffset(line) + 1)
    }

    private fun rangeEndOf(path: String, needle: String): Pair<Int, Int> {
        val vf = LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, path).toString())!!
        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        val off = doc.text.indexOf(needle)
        val end = off + needle.length
        val line = doc.getLineNumber(end - 1)
        return (line + 1) to (end - doc.getLineStartOffset(line) + 1)
    }

    private fun mirrorRealFile(path: String, text: String): com.intellij.openapi.vfs.VirtualFile {
        val t = Path.of(project.basePath!!, path); Files.createDirectories(t.parent); if (!Files.exists(t)) Files.createFile(t)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(t.toString())!!
        com.intellij.openapi.application.WriteAction.run<RuntimeException> { com.intellij.openapi.vfs.VfsUtil.saveText(vf, text) }
        return vf
    }

    private suspend fun <T> execWithNoDialog(block: suspend () -> T): T {
        val d = object : TestDialog { override fun show(message: String): Int = throw AssertionError("must not show dialog: $message") }
        val prev = TestDialogManager.setTestDialog(d)
        try { return block() } finally { TestDialogManager.setTestDialog(prev) }
    }

    private fun <T> runExecutor(block: suspend () -> T): T {
        val pool = Executors.newSingleThreadExecutor()
        return try {
            val f = pool.submit<T> { runBlocking { block() } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (System.nanoTime() < deadline && !f.isDone) { dispatchAllEventsInIdeEventQueue(); Thread.sleep(1) }
            try { f.get(1, TimeUnit.SECONDS) } catch (e: ExecutionException) { throw e.cause ?: e }
        } finally { pool.shutdownNow() }
    }
}
