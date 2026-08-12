package com.example.airefactoring.refactoring.changesignature

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.dispatchAllEventsInIdeEventQueue
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ChangeSignatureOperationTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testSuccessReturnsExecutorResultAndProjectPath() {
        val fixture = validFixture("OperationSuccess.java")
        val spy = SpyExecutor(successResult(fixture.path))
        val operation = ChangeSignatureOperation(executor = spy)

        val json = runOperation {
            operation.execute(
                project,
                fixture.path,
                fixture.line,
                fixture.column,
                "punctuation",
                "java.lang.String",
                2,
                "\"!\"",
            )
        }
        val obj = Json.parseToJsonElement(json).jsonObject

        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals(
            "java_change_signature_add_parameter",
            obj.getValue("operation").jsonPrimitive.content,
        )
        assertEquals(project.basePath, obj.getValue("projectBasePath").jsonPrimitive.content)
        assertEquals(fixture.path, obj.getValue("filePath").jsonPrimitive.content)
        assertEquals(0, obj.getValue("updatedCallSiteCount").jsonPrimitive.content.toInt())
        assertEquals(
            listOf(fixture.path),
            obj.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(1, spy.times)
        assertFalse("executor must be entered from the MCP background coroutine", spy.wasInvokedOnEdt)
    }

    fun testInvalidNameWinsBeforeMissingFileResolution() {
        val spy = SpyExecutor()
        val json = runOperation {
            ChangeSignatureOperation(executor = spy).execute(
                project,
                "Missing.java",
                1,
                1,
                " bad ",
                "int",
                1,
                "0",
            )
        }

        assertFailureCode(json, "INVALID_PARAMETER_NAME")
        assertEquals(0, spy.times)
    }

    fun testResolverFailureCodeIsPreserved() {
        val json = runOperation {
            ChangeSignatureOperation().execute(
                project,
                "Missing.java",
                1,
                1,
                "value",
                "int",
                1,
                "0",
            )
        }

        assertFailureCode(json, "FILE_NOT_FOUND")
    }

    fun testPreparationExceptionMapsToPrepareFailed() {
        val fixture = validFixture("OperationPrepare.java")
        val operation = ChangeSignatureOperation(
            executor = ThrowingExecutor(ChangeSignaturePreparationException("stale")),
        )

        val json = executeValid(operation, fixture)

        assertFailureCode(json, "PREPARE_FAILED")
    }

    fun testConflictExceptionMapsToRefactoringConflict() {
        val fixture = validFixture("OperationConflict.java")
        val operation = ChangeSignatureOperation(
            executor = ThrowingExecutor(ChangeSignatureConflictException("collision")),
        )

        val json = executeValid(operation, fixture)

        assertFailureCode(json, "REFACTORING_CONFLICT")
    }

    fun testUnexpectedExceptionMapsToRefactoringFailed() {
        val fixture = validFixture("OperationUnexpected.java")
        val operation = ChangeSignatureOperation(
            executor = ThrowingExecutor(IllegalStateException("boom")),
        )

        val json = executeValid(operation, fixture)

        assertFailureCode(json, "REFACTORING_FAILED")
    }

    fun testProcessCanceledExceptionEscapes() {
        val fixture = validFixture("OperationCancel.java")
        val operation = ChangeSignatureOperation(
            executor = ThrowingExecutor(ProcessCanceledException()),
        )

        try {
            executeValid(operation, fixture)
            fail("expected ProcessCanceledException")
        } catch (_: ProcessCanceledException) {
            // Cancellation remains visible to IntelliJ and the MCP host.
        }
    }

    fun testCoroutineCancellationEscapes() {
        val fixture = validFixture("OperationCoroutineCancel.java")
        val operation = ChangeSignatureOperation(
            executor = ThrowingExecutor(CancellationException("cancelled")),
        )

        try {
            executeValid(operation, fixture)
            fail("expected CancellationException")
        } catch (_: CancellationException) {
            // Coroutine cancellation remains visible to IntelliJ and the MCP host.
        }
    }

    private fun executeValid(
        operation: ChangeSignatureOperation,
        fixture: TargetFixture,
    ): String = runOperation {
        operation.execute(
            project,
            fixture.path,
            fixture.line,
            fixture.column,
            "punctuation",
            "java.lang.String",
            2,
            "\"!\"",
        )
    }

    private fun validFixture(path: String): TargetFixture {
        val className = path.substringBeforeLast('.')
        val source = "class $className { String greet(String name) { return name; } }"
        val target = Path.of(project.basePath!!, path)
        Files.createDirectories(target.parent)
        Files.writeString(target, source)
        val virtualFile = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(target.toString())!!
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val offset = document.text.indexOf("greet")
        val line = document.getLineNumber(offset)
        return TargetFixture(
            path = path,
            line = line + 1,
            column = offset - document.getLineStartOffset(line) + 1,
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

    private fun assertFailureCode(json: String, expected: String) {
        val obj = Json.parseToJsonElement(json).jsonObject
        assertFalse(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals(expected, obj.getValue("code").jsonPrimitive.content)
    }

    private data class TargetFixture(
        val path: String,
        val line: Int,
        val column: Int,
    )

    private class SpyExecutor(
        private val result: ChangeSignatureExecutionResult = successResult("Default.java"),
    ) : ChangeSignatureExecutor {
        var times = 0
        var wasInvokedOnEdt = false

        override suspend fun addParameter(
            project: Project,
            preparation: ChangeSignaturePreparation,
        ): ChangeSignatureExecutionResult {
            times++
            wasInvokedOnEdt = ApplicationManager.getApplication().isDispatchThread
            return result
        }
    }

    private class ThrowingExecutor(
        private val throwable: Throwable,
    ) : ChangeSignatureExecutor {
        override suspend fun addParameter(
            project: Project,
            preparation: ChangeSignaturePreparation,
        ): ChangeSignatureExecutionResult = throw throwable
    }

    companion object {
        private fun successResult(path: String) = ChangeSignatureExecutionResult(
            methodName = "greet",
            declarationFilePath = path,
            parameterName = "punctuation",
            parameterType = "java.lang.String",
            parameterPosition = 2,
            defaultCallSiteExpression = "\"!\"",
            updatedCallSiteCount = 0,
            affectedFiles = listOf(path),
            summary = "Added parameter 'punctuation' at position 2 and updated 0 call sites.",
        )
    }
}
