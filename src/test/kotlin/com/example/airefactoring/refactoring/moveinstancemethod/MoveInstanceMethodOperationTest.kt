package com.example.airefactoring.refactoring.moveinstancemethod

import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Document
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

/**
 * Exercises [MoveInstanceMethodOperation] against the real resolver and a fake executor at the
 * executor boundary. Fixtures are written through the VFS so the shared light-project index stays
 * coherent and no stale in-memory documents trigger MemoryDiskConflict in later tests.
 */
class MoveInstanceMethodOperationTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testSuccessMapsAllNativeResultFacts() {
        val methodRange = nameRange(FILE, SOURCE, "applyDiscount")
        val targetRange = nameRange(FILE, SOURCE, "customer")
        val spy = SpyExecutor()
        val operation = MoveInstanceMethodOperation(executor = spy)

        val json = runOperation {
            operation.execute(
                project,
                FILE,
                methodRange,
                targetRange,
                "public",
            )
        }
        val obj = Json.parseToJsonElement(json).jsonObject

        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("java_move_instance_method", obj.getValue("operation").jsonPrimitive.content)
        assertEquals(project.basePath, obj.getValue("projectBasePath").jsonPrimitive.content)
        assertEquals(FILE, obj.getValue("filePath").jsonPrimitive.content)
        assertEquals("applyDiscount", obj.getValue("methodName").jsonPrimitive.content)
        assertEquals(
            "parameter customer of type example.Invoice.Customer",
            obj.getValue("targetDescription").jsonPrimitive.content,
        )
        assertEquals(
            "example.Invoice.Customer",
            obj.getValue("targetClassQualifiedName").jsonPrimitive.content,
        )
        assertEquals("public", obj.getValue("newVisibility").jsonPrimitive.content)
        assertEquals(1, obj.getValue("updatedCallSiteCount").jsonPrimitive.int)
        assertEquals(
            listOf("example/Checkout.java"),
            obj.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            "Moved applyDiscount to example.Invoice.Customer and updated 1 call sites.",
            obj.getValue("summary").jsonPrimitive.content,
        )
        assertEquals(1, spy.times)
        assertFalse("native executor must run off EDT", spy.wasInvokedOnEdt)
    }

    fun testResolverRefusalAvoidsExecutorInvocation() {
        // Selecting a field (not a method parameter) as the target must be refused by the resolver.
        val methodRange = nameRange(FILE, SOURCE, "applyDiscount")
        val targetRange = nameRange(FILE, SOURCE, "discount")
        val spy = SpyExecutor()
        val operation = MoveInstanceMethodOperation(executor = spy)

        val json = runOperation {
            operation.execute(project, FILE, methodRange, targetRange, "public")
        }

        assertFailureCode(json, "UNSUPPORTED_TARGET")
        assertEquals(0, spy.times)
    }

    fun testNativeConflictMapsToRefactoringConflict() {
        val methodRange = nameRange(FILE, SOURCE, "applyDiscount")
        val targetRange = nameRange(FILE, SOURCE, "customer")
        val operation = MoveInstanceMethodOperation(
            executor = ThrowingExecutor(MoveInstanceMethodConflictException("native conflict")),
        )

        val json = runOperation {
            operation.execute(project, FILE, methodRange, targetRange, "public")
        }

        assertFailureCode(json, "REFACTORING_CONFLICT")
    }

    fun testStalePreparationMapsToPrepareFailed() {
        val methodRange = nameRange(FILE, SOURCE, "applyDiscount")
        val targetRange = nameRange(FILE, SOURCE, "customer")
        val operation = MoveInstanceMethodOperation(
            executor = ThrowingExecutor(MoveInstanceMethodPreparationException("stale")),
        )

        val json = runOperation {
            operation.execute(project, FILE, methodRange, targetRange, "public")
        }

        assertFailureCode(json, "PREPARE_FAILED")
    }

    fun testUnexpectedFailureMapsToRefactoringFailed() {
        val methodRange = nameRange(FILE, SOURCE, "applyDiscount")
        val targetRange = nameRange(FILE, SOURCE, "customer")
        val operation = MoveInstanceMethodOperation(
            executor = ThrowingExecutor(IllegalStateException("boom")),
        )

        val json = runOperation {
            operation.execute(project, FILE, methodRange, targetRange, "public")
        }

        assertFailureCode(json, "REFACTORING_FAILED")
    }

    fun testCancellationAndProcessCancellationEscapeUnchanged() {
        val methodRange = nameRange(FILE, SOURCE, "applyDiscount")
        val targetRange = nameRange(FILE, SOURCE, "customer")

        val coroutineOperation = MoveInstanceMethodOperation(
            executor = ThrowingExecutor(CancellationException("cancelled")),
        )
        try {
            runOperation {
                coroutineOperation.execute(project, FILE, methodRange, targetRange, "public")
            }
            fail("expected CancellationException")
        } catch (_: CancellationException) {
            // Cancellation must remain visible to IntelliJ and the MCP host.
        }

        val processOperation = MoveInstanceMethodOperation(
            executor = ThrowingExecutor(ProcessCanceledException()),
        )
        try {
            runOperation {
                processOperation.execute(project, FILE, methodRange, targetRange, "public")
            }
            fail("expected ProcessCanceledException")
        } catch (_: ProcessCanceledException) {
            // Cancellation must remain visible to IntelliJ and the MCP host.
        }
    }

    // --- helpers ---

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

    private fun nameRange(fileName: String, source: String, name: String): SourceRange {
        mirrorRealFile(fileName, source)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val document = document(fileName)
        val startOffset = document.text.indexOf(name)
        require(startOffset >= 0) { "name '$name' missing in $fileName" }
        return range(document, startOffset, startOffset + name.length)
    }

    private fun mirrorRealFile(fileName: String, text: String): VirtualFile {
        val target = Path.of(project.basePath!!, fileName)
        Files.createDirectories(target.parent)
        if (!Files.exists(target)) Files.createFile(target)
        val virtualFile = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(target.toString())!!
        // Write through the VFS so any cached document/PSI is reloaded across tests; raw disk
        // writes leave stale in-memory documents that trigger MemoryDiskConflict in later runs.
        WriteAction.run<RuntimeException> { VfsUtil.saveText(virtualFile, text) }
        return virtualFile
    }

    private fun document(fileName: String): Document =
        FileDocumentManager.getInstance().getDocument(virtualFile(fileName))!!

    private fun virtualFile(fileName: String): VirtualFile = LocalFileSystem.getInstance()
        .findFileByPath(Path.of(project.basePath!!, fileName).toString())!!

    private fun range(document: Document, startOffset: Int, endOffset: Int): SourceRange {
        fun position(offset: Int): Pair<Int, Int> {
            val line = document.getLineNumber(offset)
            return (line + 1) to (offset - document.getLineStartOffset(line) + 1)
        }

        val (startLine, startColumn) = position(startOffset)
        val (endLine, endColumn) = position(endOffset)
        return SourceRange(startLine, startColumn, endLine, endColumn)
    }

    private fun assertFailureCode(json: String, expected: String) {
        val obj = Json.parseToJsonElement(json).jsonObject
        assertFalse(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals(expected, obj.getValue("code").jsonPrimitive.content)
    }

    private class SpyExecutor(
        private val result: MoveInstanceMethodExecutionResult = successResult(),
    ) : MoveInstanceMethodExecutor {
        var times = 0
        var wasInvokedOnEdt = false

        override suspend fun move(
            project: Project,
            preparation: MoveInstanceMethodPreparation,
        ): MoveInstanceMethodExecutionResult {
            times++
            wasInvokedOnEdt = ApplicationManager.getApplication().isDispatchThread
            return result
        }
    }

    private class ThrowingExecutor(
        private val throwable: Throwable,
    ) : MoveInstanceMethodExecutor {
        override suspend fun move(
            project: Project,
            preparation: MoveInstanceMethodPreparation,
        ): MoveInstanceMethodExecutionResult = throw throwable
    }

    private companion object {
        const val FILE = "MoveInstanceOperation.java"
        val SOURCE =
            """
                package example;

                public class Invoice {
                    private int discount = 0;

                    public int applyDiscount(Customer customer) {
                        return this.discount - customer.discount();
                    }

                    public static class Customer {
                        private final int rate;

                        public Customer(int rate) {
                            this.rate = rate;
                        }

                        public int discount() {
                            return rate;
                        }
                    }
                }
            """.trimIndent()

        private fun successResult() = MoveInstanceMethodExecutionResult(
            methodName = "applyDiscount",
            targetDescription = "parameter customer of type example.Invoice.Customer",
            targetClassQualifiedName = "example.Invoice.Customer",
            newVisibility = "public",
            updatedCallSiteCount = 1,
            affectedFiles = listOf("example/Checkout.java"),
            summary = "Moved applyDiscount to example.Invoice.Customer and updated 1 call sites.",
        )
    }
}
