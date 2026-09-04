package com.example.airefactoring.refactoring.introduceparameter

import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Exercises [IntroduceParameterOperation] against the real resolver and a fake executor at the
 * executor boundary. Fixtures use stable, distinctly-prefixed names and are written through the
 * VFS (never raw `Files.writeString`) so the shared light-project index stays coherent and no
 * stale in-memory documents trigger MemoryDiskConflict in later tests.
 */
class IntroduceParameterOperationTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testSuccessMapsSourceKindPositionCountPathsAndSummary() {
        val range = expressionRange(SERVICE_FILE, SERVICE_SOURCE, "rate * 3")
        val spy = SpyExecutor()
        val operation = IntroduceParameterOperation(executor = spy)

        val json = runOperation {
            operation.execute(project, SERVICE_FILE, range, "multiplier")
        }
        val obj = Json.parseToJsonElement(json).jsonObject

        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("java_introduce_parameter", obj.getValue("operation").jsonPrimitive.content)
        assertEquals(project.basePath, obj.getValue("projectBasePath").jsonPrimitive.content)
        assertEquals(SERVICE_FILE, obj.getValue("filePath").jsonPrimitive.content)
        assertEquals("opPrice", obj.getValue("methodName").jsonPrimitive.content)
        assertEquals("multiplier", obj.getValue("parameterName").jsonPrimitive.content)
        assertEquals("int", obj.getValue("parameterType").jsonPrimitive.content)
        assertEquals(2, obj.getValue("parameterPosition").jsonPrimitive.int)
        assertEquals("EXPRESSION", obj.getValue("sourceKind").jsonPrimitive.content)
        assertEquals(2, obj.getValue("updatedCallSiteCount").jsonPrimitive.int)
        assertEquals(
            listOf("IpOpCallerOne.java", "IpOpCallerTwo.java", SERVICE_FILE),
            obj.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            "Introduced parameter 'multiplier' to 'opPrice' and updated 2 call site(s).",
            obj.getValue("summary").jsonPrimitive.content,
        )
        assertEquals(1, spy.times)
        assertFalse("native executor must run off EDT", spy.wasInvokedOnEdt)
    }

    fun testSuccessMapsLocalVariableSourceKind() {
        val range = localVariableRange(LOCAL_FILE, LOCAL_SOURCE, "doubled")
        val spy = SpyExecutor(
            IntroduceParameterExecutionResult(
                methodName = "opPrice",
                parameterName = "doubled",
                parameterType = "int",
                parameterPosition = 2,
                sourceKind = IntroduceParameterSourceKind.LOCAL_VARIABLE,
                updatedCallSiteCount = 0,
                affectedFiles = listOf(LOCAL_FILE),
                summary = "Introduced parameter 'doubled' to 'opPrice' and updated 0 call site(s).",
            ),
        )
        val operation = IntroduceParameterOperation(executor = spy)

        val json = runOperation {
            operation.execute(project, LOCAL_FILE, range, "doubled")
        }
        val obj = Json.parseToJsonElement(json).jsonObject

        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("LOCAL_VARIABLE", obj.getValue("sourceKind").jsonPrimitive.content)
        assertEquals("doubled", obj.getValue("parameterName").jsonPrimitive.content)
        assertEquals(
            listOf(LOCAL_FILE),
            obj.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    fun testSuccessNormalizesNestedDeclarationFilePath() {
        val range = expressionRange(NESTED_FILE, NESTED_SOURCE, "rate * 4")
        val spy = SpyExecutor()
        val operation = IntroduceParameterOperation(executor = spy)

        val json = runOperation {
            operation.execute(project, NESTED_FILE, range, "multiplier")
        }
        val obj = Json.parseToJsonElement(json).jsonObject

        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals(NESTED_FILE, obj.getValue("filePath").jsonPrimitive.content)
        assertEquals(1, spy.times)
    }

    fun testInvalidParameterNameIsRejectedBeforeResolution() {
        val spy = SpyExecutor()
        val operation = IntroduceParameterOperation(executor = spy)

        val json = runOperation {
            operation.execute(
                project,
                "Missing.java",
                SourceRange(1, 1, 1, 2),
                " bad ",
            )
        }

        assertFailureCode(json, "INVALID_PARAMETER_NAME")
        assertEquals(0, spy.times)
    }

    fun testResolverFailureCodeIsPreserved() {
        val spy = SpyExecutor()
        val json = runOperation {
            IntroduceParameterOperation(executor = spy).execute(
                project,
                "Missing.java",
                SourceRange(1, 1, 1, 2),
                "multiplier",
            )
        }

        assertFailureCode(json, "FILE_NOT_FOUND")
        assertEquals(0, spy.times)
    }

    fun testNativeConflictMapsToRefactoringConflict() {
        val range = expressionRange(SERVICE_FILE, SERVICE_SOURCE, "rate * 3")
        val operation = IntroduceParameterOperation(
            executor = ThrowingExecutor(IntroduceParameterConflictException("native conflict")),
        )

        val json = runOperation {
            operation.execute(project, SERVICE_FILE, range, "multiplier")
        }

        assertFailureCode(json, "REFACTORING_CONFLICT")
    }

    fun testNativePreparationMapsToPrepareFailed() {
        val range = expressionRange(SERVICE_FILE, SERVICE_SOURCE, "rate * 3")
        val operation = IntroduceParameterOperation(
            executor = ThrowingExecutor(IntroduceParameterPreparationException("stale")),
        )

        val json = runOperation {
            operation.execute(project, SERVICE_FILE, range, "multiplier")
        }

        assertFailureCode(json, "PREPARE_FAILED")
    }

    fun testStaleResolvedSelectionMapsToPrepareFailedWithoutNativeMutation() {
        val range = expressionRange(SERVICE_FILE, SERVICE_SOURCE, "rate * 3")
        val operation = IntroduceParameterOperation(
            executor = StaleSelectionExecutor(),
        )

        val json = runOperation {
            operation.execute(project, SERVICE_FILE, range, "multiplier")
        }

        assertFailureCode(json, "PREPARE_FAILED")
        assertTrue(document(SERVICE_FILE).text.contains("return rate * 4;"))
        assertFalse("the native processor must not run after the source changes", document(SERVICE_FILE).text.contains("multiplier"))
    }

    fun testUnexpectedFailureMapsToRefactoringFailed() {
        val range = expressionRange(SERVICE_FILE, SERVICE_SOURCE, "rate * 3")
        val operation = IntroduceParameterOperation(
            executor = ThrowingExecutor(IllegalStateException("boom")),
        )

        val json = runOperation {
            operation.execute(project, SERVICE_FILE, range, "multiplier")
        }

        assertFailureCode(json, "REFACTORING_FAILED")
    }

    fun testCancellationAndProcessCancellationEscapeUnchanged() {
        val range = expressionRange(SERVICE_FILE, SERVICE_SOURCE, "rate * 3")

        val coroutineOperation = IntroduceParameterOperation(
            executor = ThrowingExecutor(CancellationException("cancelled")),
        )
        try {
            runOperation {
                coroutineOperation.execute(project, SERVICE_FILE, range, "multiplier")
            }
            fail("expected CancellationException")
        } catch (_: CancellationException) {
            // Cancellation must remain visible to IntelliJ and the MCP host.
        }

        val processOperation = IntroduceParameterOperation(
            executor = ThrowingExecutor(ProcessCanceledException()),
        )
        try {
            runOperation {
                processOperation.execute(project, SERVICE_FILE, range, "multiplier")
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

    private fun expressionRange(fileName: String, source: String, expressionText: String): SourceRange {
        mirrorRealFile(fileName, source)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val document = document(fileName)
        val startOffset = document.text.indexOf(expressionText)
        require(startOffset >= 0) { "expression '$expressionText' missing in $fileName" }
        return range(document, startOffset, startOffset + expressionText.length)
    }

    private fun localVariableRange(fileName: String, source: String, variableName: String): SourceRange {
        mirrorRealFile(fileName, source)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val document = document(fileName)
        val needle = "int $variableName = "
        val nameStart = document.text.indexOf(needle) + "int ".length
        require(nameStart >= "int ".length) { "local variable '$variableName' missing in $fileName" }
        return range(document, nameStart, nameStart + variableName.length)
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
        private val result: IntroduceParameterExecutionResult = successResult(),
    ) : IntroduceParameterExecutor {
        var times = 0
        var wasInvokedOnEdt = false

        override suspend fun introduceParameter(
            project: Project,
            selection: IntroduceParameterSelection,
            parameterName: String,
        ): IntroduceParameterExecutionResult {
            times++
            wasInvokedOnEdt = ApplicationManager.getApplication().isDispatchThread
            return result
        }
    }

    private class ThrowingExecutor(
        private val throwable: Throwable,
    ) : IntroduceParameterExecutor {
        override suspend fun introduceParameter(
            project: Project,
            selection: IntroduceParameterSelection,
            parameterName: String,
        ): IntroduceParameterExecutionResult = throw throwable
    }

    /** Changes the resolver's source document after handoff, then delegates to the real executor. */
    private inner class StaleSelectionExecutor : IntroduceParameterExecutor {
        override suspend fun introduceParameter(
            project: Project,
            selection: IntroduceParameterSelection,
            parameterName: String,
        ): IntroduceParameterExecutionResult {
            withContext(Dispatchers.EDT) {
                val document = document(selection.sourceDocumentPath)
                val sourceStart = document.text.indexOf("rate * 3")
                check(sourceStart >= 0) { "fixture source is missing" }
                WriteCommandAction.runWriteCommandAction(project) {
                    document.replaceString(sourceStart, sourceStart + "rate * 3".length, "rate * 4")
                }
            }
            return IntellijIntroduceParameterExecutor()
                .introduceParameter(project, selection, parameterName)
        }
    }

    private companion object {
        const val SERVICE_FILE = "IpOpService.java"
        const val SERVICE_SOURCE =
            "class IpOpService { int opPrice(int rate) { return rate * 3; } }"
        const val LOCAL_FILE = "IpOpLocal.java"
        const val LOCAL_SOURCE =
            "class IpOpLocal { int opPrice(int rate) { int doubled = rate * 3; return doubled; } }"
        const val NESTED_FILE = "nested/IpOpNested.java"
        const val NESTED_SOURCE =
            "class IpOpNested { int opPrice(int rate) { return rate * 4; } }"

        private fun successResult() = IntroduceParameterExecutionResult(
            methodName = "opPrice",
            parameterName = "multiplier",
            parameterType = "int",
            parameterPosition = 2,
            sourceKind = IntroduceParameterSourceKind.EXPRESSION,
            updatedCallSiteCount = 2,
            affectedFiles = listOf(
                "IpOpCallerOne.java",
                "IpOpCallerTwo.java",
                "IpOpService.java",
            ),
            summary = "Introduced parameter 'multiplier' to 'opPrice' and updated 2 call site(s).",
        )
    }
}
