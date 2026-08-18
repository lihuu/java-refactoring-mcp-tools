package com.example.airefactoring.refactoring.makestatic

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
 * Exercises [JavaMakeStaticOperation] against the real resolver and a fake executor at the executor
 * boundary. Fixtures are written through the VFS so the shared light-project index stays coherent and
 * no stale in-memory documents trigger MemoryDiskConflict in later tests.
 */
class JavaMakeStaticOperationTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testSuccessMapsAllNativeResultFacts() {
        val memberRange = nameRange(FILE, SOURCE, "applyDiscount")
        val spy = SpyExecutor()
        val operation = JavaMakeStaticOperation(executor = spy)

        val json = runOperation {
            operation.execute(
                project,
                FILE,
                memberRange,
                replaceUsages = true,
                classParameterName = "order",
                fieldParameters = emptyList(),
                generateDelegate = false,
            )
        }
        val obj = Json.parseToJsonElement(json).jsonObject

        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("java_make_static", obj.getValue("operation").jsonPrimitive.content)
        assertEquals(project.basePath, obj.getValue("projectBasePath").jsonPrimitive.content)
        assertEquals(FILE, obj.getValue("filePath").jsonPrimitive.content)
        assertEquals("applyDiscount", obj.getValue("memberName").jsonPrimitive.content)
        assertEquals("method", obj.getValue("memberKind").jsonPrimitive.content)
        assertTrue(obj.getValue("replaceUsages").jsonPrimitive.boolean)
        assertEquals("order", obj.getValue("classParameterName").jsonPrimitive.content)
        assertEquals(
            listOf("a", "r"),
            obj.getValue("fieldParameterNames").jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse(obj.getValue("generateDelegate").jsonPrimitive.boolean)
        assertEquals(2, obj.getValue("nativeUsageCount").jsonPrimitive.int)
        assertEquals(
            listOf("example/Checkout.java"),
            obj.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            "Made method 'applyDiscount' static and updated 2 native usages.",
            obj.getValue("summary").jsonPrimitive.content,
        )
        assertEquals(1, spy.times)
    }

    fun testResolverRefusalAvoidsExecutorInvocation() {
        // Selecting a field name as the member is refused by the resolver.
        val memberRange = nameRange(FILE, SOURCE, "amount")
        val spy = SpyExecutor()
        val operation = JavaMakeStaticOperation(executor = spy)

        val json = runOperation {
            operation.execute(
                project,
                FILE,
                memberRange,
                replaceUsages = true,
                classParameterName = null,
                fieldParameters = emptyList(),
                generateDelegate = false,
            )
        }

        assertFailureCode(json, "INVALID_RANGE")
        assertEquals(0, spy.times)
    }

    fun testNativeConflictMapsToRefactoringConflict() {
        val memberRange = nameRange(FILE, SOURCE, "applyDiscount")
        val operation = JavaMakeStaticOperation(
            executor = ThrowingExecutor(JavaMakeStaticConflictException("native conflict")),
        )

        val json = runOperation {
            operation.execute(
                project,
                FILE,
                memberRange,
                replaceUsages = true,
                classParameterName = null,
                fieldParameters = emptyList(),
                generateDelegate = false,
            )
        }

        assertFailureCode(json, "REFACTORING_CONFLICT")
    }

    fun testStalePreparationMapsToPrepareFailed() {
        val memberRange = nameRange(FILE, SOURCE, "applyDiscount")
        val operation = JavaMakeStaticOperation(
            executor = ThrowingExecutor(JavaMakeStaticPreparationException("stale")),
        )

        val json = runOperation {
            operation.execute(
                project,
                FILE,
                memberRange,
                replaceUsages = true,
                classParameterName = null,
                fieldParameters = emptyList(),
                generateDelegate = false,
            )
        }

        assertFailureCode(json, "PREPARE_FAILED")
    }

    fun testUnexpectedFailureMapsToRefactoringFailed() {
        val memberRange = nameRange(FILE, SOURCE, "applyDiscount")
        val operation = JavaMakeStaticOperation(
            executor = ThrowingExecutor(IllegalStateException("boom")),
        )

        val json = runOperation {
            operation.execute(
                project,
                FILE,
                memberRange,
                replaceUsages = true,
                classParameterName = null,
                fieldParameters = emptyList(),
                generateDelegate = false,
            )
        }

        assertFailureCode(json, "REFACTORING_FAILED")
    }

    fun testCancellationAndProcessCancellationEscapeUnchanged() {
        val memberRange = nameRange(FILE, SOURCE, "applyDiscount")

        val coroutineOperation = JavaMakeStaticOperation(
            executor = ThrowingExecutor(CancellationException("cancelled")),
        )
        try {
            runOperation {
                coroutineOperation.execute(
                    project,
                    FILE,
                    memberRange,
                    replaceUsages = true,
                    classParameterName = null,
                    fieldParameters = emptyList(),
                    generateDelegate = false,
                )
            }
            fail("expected CancellationException")
        } catch (_: CancellationException) {
            // Cancellation must remain visible to IntelliJ and the MCP host.
        }

        val processOperation = JavaMakeStaticOperation(
            executor = ThrowingExecutor(ProcessCanceledException()),
        )
        try {
            runOperation {
                processOperation.execute(
                    project,
                    FILE,
                    memberRange,
                    replaceUsages = true,
                    classParameterName = null,
                    fieldParameters = emptyList(),
                    generateDelegate = false,
                )
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
        private val result: JavaMakeStaticExecutionResult = successResult(),
    ) : JavaMakeStaticExecutor {
        var times = 0
        var wasInvokedOnEdt = false

        override suspend fun makeStatic(
            project: Project,
            preparation: JavaMakeStaticPreparation,
        ): JavaMakeStaticExecutionResult {
            times++
            wasInvokedOnEdt = ApplicationManager.getApplication().isDispatchThread
            return result
        }
    }

    private class ThrowingExecutor(
        private val throwable: Throwable,
    ) : JavaMakeStaticExecutor {
        override suspend fun makeStatic(
            project: Project,
            preparation: JavaMakeStaticPreparation,
        ): JavaMakeStaticExecutionResult = throw throwable
    }

    private companion object {
        const val FILE = "MakeStaticOperation.java"
        val SOURCE =
            """
                package example;

                public class Order {
                    private int amount;

                    public Order(int amount) {
                        this.amount = amount;
                    }

                    public int applyDiscount() {
                        return amount;
                    }
                }
            """.trimIndent()

        private fun successResult() = JavaMakeStaticExecutionResult(
            memberName = "applyDiscount",
            memberKind = JavaMakeStaticMemberKind.METHOD,
            replaceUsages = true,
            classParameterName = "order",
            fieldParameterNames = listOf("a", "r"),
            generateDelegate = false,
            nativeUsageCount = 2,
            affectedFiles = listOf("example/Checkout.java"),
            summary = "Made method 'applyDiscount' static and updated 2 native usages.",
        )
    }
}
