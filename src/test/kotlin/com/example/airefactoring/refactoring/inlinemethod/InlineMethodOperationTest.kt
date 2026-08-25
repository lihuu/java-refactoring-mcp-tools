package com.example.airefactoring.refactoring.inlinemethod

import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.application.WriteAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.IndexingTestUtil
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

class InlineMethodOperationTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testInlineMethodSuccessReturnsMethodNameCountAndNormalizedPaths() {
        val (path, range) = prepareSuccessFixture()
        val spy = SpyExecutor()
        val operation = InlineMethodOperation(executor = spy)

        val json = runOperation { operation.execute(project, path, range) }
        val obj = Json.parseToJsonElement(json).jsonObject

        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("java_inline_method", obj.getValue("operation").jsonPrimitive.content)
        assertEquals(path, obj.getValue("filePath").jsonPrimitive.content)
        assertEquals(project.basePath, obj.getValue("projectBasePath").jsonPrimitive.content)
        assertEquals("addTax", obj.getValue("methodName").jsonPrimitive.content)
        assertEquals(2, obj.getValue("inlinedOccurrenceCount").jsonPrimitive.int)
        assertEquals(listOf("example/CheckoutOp.java", "example/PricingRulesOp.java"), obj.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(obj.getValue("summary").jsonPrimitive.content.contains("addTax"))
        assertFalse(obj.containsKey("code"))
        assertEquals(1, spy.times)
        assertFalse("native executor must run off EDT", spy.wasInvokedOnEdt)
    }

    fun testResolverFailuresArePreservedWithoutInvokingExecutor() {
        val spy = SpyExecutor()
        val operation = InlineMethodOperation(executor = spy)

        val json = runOperation { operation.execute(project, "Missing.java", SourceRange(1, 1, 1, 2)) }
        val obj = Json.parseToJsonElement(json).jsonObject
        assertFalse(obj.getValue("ok").jsonPrimitive.boolean)
        // JavaSourceTargetResolver returns FILE_NOT_FOUND for missing file
        assertEquals("FILE_NOT_FOUND", obj.getValue("code").jsonPrimitive.content)
        assertEquals(0, spy.times)
    }

    fun testExecutorFailuresMapToStableCodes() {
        val cases = listOf(
            InlineMethodConflictException("conflict") to "REFACTORING_CONFLICT",
            InlineMethodPreparationException("stale") to "PREPARE_FAILED",
            IllegalStateException("boom") to "REFACTORING_FAILED",
        )
        cases.forEachIndexed { index, (throwable, expected) ->
            val fileName = "example/OpFailure$index.java"
            // need a valid target for each case; reuse success fixture naming
            val (path, range) = prepareSuccessFixtureForFile("example/OpTarget$index.java", "example/OpCaller$index.java", "method$index")
            val op = InlineMethodOperation(executor = SpyExecutor(throwable = throwable))
            val json = runOperation { op.execute(project, path, range) }
            val obj = Json.parseToJsonElement(json).jsonObject
            assertFalse(obj.getValue("ok").jsonPrimitive.boolean)
            assertEquals(expected, obj.getValue("code").jsonPrimitive.content)
        }
    }

    fun testProcessCanceledExceptionEscapes() {
        val (path, range) = prepareSuccessFixture()
        val operation = InlineMethodOperation(executor = SpyExecutor(throwable = ProcessCanceledException()))
        try {
            runOperation { operation.execute(project, path, range) }
            fail("expected ProcessCanceledException")
        } catch (_: ProcessCanceledException) {
            // expected
        }
    }

    fun testCoroutineCancellationEscapes() {
        val (path, range) = prepareSuccessFixture()
        val operation = InlineMethodOperation(executor = SpyExecutor(throwable = CancellationException("cancelled")))
        try {
            runOperation { operation.execute(project, path, range) }
            fail("expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
    }

    // --- helpers ---

    private fun prepareSuccessFixture(): Pair<String, SourceRange> =
        prepareSuccessFixtureForFile("example/PricingRulesOp.java", "example/CheckoutOp.java", "addTax")

    private fun prepareSuccessFixtureForFile(targetPath: String, callerPath: String, methodName: String): Pair<String, SourceRange> {
        mirrorRealFile(
            targetPath,
            """
                package example;
                public final class ${targetPath.substringAfterLast("/").substringBefore(".")} {
                    public static int $methodName(int amount) { return amount + 5; }
                }
            """.trimIndent(),
        )
        mirrorRealFile(
            callerPath,
            """
                package example;
                public final class ${callerPath.substringAfterLast("/").substringBefore(".")} {
                    public int total(int amount) { return ${targetPath.substringAfterLast("/").substringBefore(".")}.$methodName(amount) + ${targetPath.substringAfterLast("/").substringBefore(".")}.$methodName(10); }
                }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val document = FileDocumentManager.getInstance().getDocument(virtualFile(targetPath))!!
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val offset = document.text.indexOf(methodName)
        check(offset >= 0) { "methodName missing" }
        val range = range(document, offset, offset + methodName.length)
        return targetPath to range
    }

    private fun mirrorRealFile(path: String, text: String) {
        val target = Path.of(project.basePath!!, path)
        Files.createDirectories(target.parent)
        if (!Files.exists(target)) Files.createFile(target)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(target.toString())!!
        WriteAction.run<RuntimeException> { VfsUtil.saveText(virtualFile, text) }
        FileDocumentManager.getInstance().getDocument(virtualFile)?.let { PsiDocumentManager.getInstance(project).commitDocument(it) }
    }

    private fun virtualFile(path: String) =
        LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, path).toString())!!

    private fun range(document: com.intellij.openapi.editor.Document, startOffset: Int, endOffset: Int): SourceRange {
        val startLine = document.getLineNumber(startOffset)
        val endLine = document.getLineNumber(endOffset - 1)
        return SourceRange(
            startLine + 1,
            startOffset - document.getLineStartOffset(startLine) + 1,
            endLine + 1,
            endOffset - document.getLineStartOffset(endLine) + 1,
        )
    }

    private fun runOperation(block: suspend () -> String): String {
        val pool = Executors.newSingleThreadExecutor()
        return try {
            val future = pool.submit<String> { runBlocking { block() } }
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

    private class SpyExecutor(
        private val result: InlineMethodExecutionResult = InlineMethodExecutionResult(
            methodName = "addTax",
            inlinedOccurrenceCount = 2,
            affectedFiles = listOf("example/CheckoutOp.java", "example/PricingRulesOp.java"),
            summary = "Inlined 2 Java calls to 'addTax' and removed its declaration.",
        ),
        private val throwable: RuntimeException? = null,
    ) : InlineMethodExecutor {
        var times = 0
        var wasInvokedOnEdt = false
        override suspend fun inline(project: Project, preparation: InlineMethodPreparation): InlineMethodExecutionResult {
            times++
            wasInvokedOnEdt = ApplicationManager.getApplication().isDispatchThread
            throwable?.let { throw it }
            return result
        }
    }
}
