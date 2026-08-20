package com.example.airefactoring.refactoring.converttoinstancemethod

import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.dispatchAllEventsInIdeEventQueue
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConvertToInstanceMethodOperationTest : LightJavaCodeInsightFixtureTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21
    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testSuccessMapsNativeFactsAndRunsOffEdt() {
        val methodRange = nameRange(FILE, SOURCE, "format")
        val targetRange = nameRange(FILE, SOURCE, "customer")
        val spy = SpyExecutor()
        val op = ConvertToInstanceMethodOperation(executor = spy)
        val json = runOp { op.execute(project, FILE, methodRange, "parameter", targetRange, "public", false) }
        val obj = Json.parseToJsonElement(json).jsonObject
        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("java_convert_to_instance_method", obj.getValue("operation").jsonPrimitive.content)
        assertEquals(FILE, obj.getValue("filePath").jsonPrimitive.content)
        assertEquals("format", obj.getValue("methodName").jsonPrimitive.content)
        assertEquals("parameter", obj.getValue("targetKind").jsonPrimitive.content)
        assertEquals("example.Customer", obj.getValue("targetClassQualifiedName").jsonPrimitive.content)
        assertEquals(1, obj.getValue("nativeUsageCount").jsonPrimitive.int)
        assertFalse("executor must run off EDT", spy.wasInvokedOnEdt)
        assertEquals(1, spy.times)
    }

    fun testResolverRefusalAvoidsExecutor() {
        val methodRange = nameRange(FILE, SOURCE, "format")
        // pick a non-parameter target (the class name) to trigger failure
        val badTarget = nameRange(FILE, SOURCE, "Invoice")
        val spy = SpyExecutor()
        val op = ConvertToInstanceMethodOperation(executor = spy)
        val json = runOp { op.execute(project, FILE, methodRange, "parameter", badTarget, null, false) }
        assertFailureCode(json, "UNSUPPORTED_TARGET")
        assertEquals(0, spy.times)
    }

    fun testConflictMapsToRefactoringConflict() {
        val methodRange = nameRange(FILE, SOURCE, "format")
        val targetRange = nameRange(FILE, SOURCE, "customer")
        val op = ConvertToInstanceMethodOperation(executor = ThrowingExecutor(ConvertToInstanceMethodConflictException("conflict")))
        val json = runOp { op.execute(project, FILE, methodRange, "parameter", targetRange, null, false) }
        assertFailureCode(json, "REFACTORING_CONFLICT")
    }

    fun testStalePreparationMapsToPrepareFailed() {
        val methodRange = nameRange(FILE, SOURCE, "format")
        val targetRange = nameRange(FILE, SOURCE, "customer")
        val op = ConvertToInstanceMethodOperation(executor = ThrowingExecutor(ConvertToInstanceMethodPreparationException("stale")))
        val json = runOp { op.execute(project, FILE, methodRange, "parameter", targetRange, null, false) }
        assertFailureCode(json, "PREPARE_FAILED")
    }

    fun testUnexpectedMapsToRefactoringFailed() {
        val methodRange = nameRange(FILE, SOURCE, "format")
        val targetRange = nameRange(FILE, SOURCE, "customer")
        val op = ConvertToInstanceMethodOperation(executor = ThrowingExecutor(IllegalStateException("boom")))
        val json = runOp { op.execute(project, FILE, methodRange, "parameter", targetRange, null, false) }
        assertFailureCode(json, "REFACTORING_FAILED")
    }

    fun testCancellationEscapes() {
        val methodRange = nameRange(FILE, SOURCE, "format")
        val targetRange = nameRange(FILE, SOURCE, "customer")
        val op1 = ConvertToInstanceMethodOperation(executor = ThrowingExecutor(CancellationException("c")))
        try { runOp { op1.execute(project, FILE, methodRange, "parameter", targetRange, null, false) }; fail("expected CancellationException") } catch (_: CancellationException) {}
        val op2 = ConvertToInstanceMethodOperation(executor = ThrowingExecutor(ProcessCanceledException()))
        try { runOp { op2.execute(project, FILE, methodRange, "parameter", targetRange, null, false) }; fail("expected PCE") } catch (_: ProcessCanceledException) {}
    }

    private fun runOp(block: suspend () -> String): String {
        val pool = Executors.newSingleThreadExecutor()
        return try {
            val f = pool.submit<String> { runBlocking { block() } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (System.nanoTime() < deadline && !f.isDone) { dispatchAllEventsInIdeEventQueue(); Thread.sleep(1) }
            try { f.get(1, TimeUnit.SECONDS) } catch (e: ExecutionException) { throw e.cause ?: e }
        } finally { pool.shutdownNow() }
    }

    private fun nameRange(fileName: String, source: String, name: String): SourceRange {
        mirrorRealFile(fileName, source)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val doc = FileDocumentManager.getInstance().getDocument(virtualFile(fileName))!!
        val off = doc.text.indexOf(name)
        require(off >= 0) { "missing $name" }
        return range(doc, off, off + name.length)
    }

    private fun mirrorRealFile(fileName: String, text: String): VirtualFile {
        val target = Path.of(project.basePath!!, fileName)
        Files.createDirectories(target.parent); if (!Files.exists(target)) Files.createFile(target)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(target.toString())!!
        WriteAction.run<RuntimeException> { VfsUtil.saveText(vf, text) }
        return vf
    }

    private fun virtualFile(n: String): VirtualFile = LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, n).toString())!!
    private fun range(doc: com.intellij.openapi.editor.Document, s: Int, e: Int): SourceRange {
        fun pos(o: Int) = (doc.getLineNumber(o) + 1) to (o - doc.getLineStartOffset(doc.getLineNumber(o)) + 1)
        val (sl, sc) = pos(s); val (el, ec) = pos(e)
        return SourceRange(sl, sc, el, ec)
    }

    private fun assertFailureCode(json: String, expected: String) {
        val obj = Json.parseToJsonElement(json).jsonObject
        assertFalse(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals(expected, obj.getValue("code").jsonPrimitive.content)
    }

    private class SpyExecutor(private val result: ConvertToInstanceMethodExecutionResult = success()) : ConvertToInstanceMethodExecutor {
        var times = 0; var wasInvokedOnEdt = false
        override suspend fun convert(project: Project, preparation: ConvertToInstanceMethodPreparation): ConvertToInstanceMethodExecutionResult {
            times++; wasInvokedOnEdt = ApplicationManager.getApplication().isDispatchThread; return result
        }
    }

    private class ThrowingExecutor(val t: Throwable) : ConvertToInstanceMethodExecutor {
        override suspend fun convert(project: Project, preparation: ConvertToInstanceMethodPreparation): ConvertToInstanceMethodExecutionResult = throw t
    }

    companion object {
        const val FILE = "ConvertOp.java"
        val SOURCE = "package example; public class Invoice { public static String format(Customer customer) { return customer.name(); } } public class Customer { public String name() { return \"\"; } }"
        private fun success() = ConvertToInstanceMethodExecutionResult("format", "parameter", "parameter customer of type example.Customer", "example.Customer", "public", 1, listOf("example/Invoice.java"), "Converted format to an instance method of example.Customer.")
    }
}
