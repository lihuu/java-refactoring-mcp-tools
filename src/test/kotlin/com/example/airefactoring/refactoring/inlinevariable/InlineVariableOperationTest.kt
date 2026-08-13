package com.example.airefactoring.refactoring.inlinevariable

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.dispatchAllEventsInIdeEventQueue
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InlineVariableOperationTest : LightJavaCodeInsightFixtureTestCase() {

    fun testSuccessReturnsResolvedNameCountAndNormalizedPaths() {
        val point = validPoint("OperationInline.java")
        val executor = SpyExecutor()
        val operation = InlineVariableOperation(executor = executor)

        val obj = Json.parseToJsonElement(
            runOperation {
                operation.execute(
                    project,
                    "./OperationInline.java",
                    point.first,
                    point.second,
                )
            },
        ).jsonObject

        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("java_inline_variable", obj.getValue("operation").jsonPrimitive.content)
        assertEquals("subtotal", obj.getValue("variableName").jsonPrimitive.content)
        assertEquals(2, obj.getValue("inlinedOccurrenceCount").jsonPrimitive.int)
        assertEquals(project.basePath, obj.getValue("projectBasePath").jsonPrimitive.content)
        assertEquals("OperationInline.java", obj.getValue("filePath").jsonPrimitive.content)
        assertEquals(1, executor.times)
        assertFalse(executor.wasInvokedOnEdt)
    }

    fun testResolverFailureCodeIsPreservedWithoutInvokingExecutor() {
        val executor = SpyExecutor()

        val json = runOperation {
            InlineVariableOperation(executor = executor).execute(
                project,
                "Missing.java",
                1,
                1,
            )
        }

        assertFailureCode(json, "FILE_NOT_FOUND")
        assertEquals(0, executor.times)
    }

    fun testExecutorFailuresMapToStableCodes() {
        val failures = listOf(
            InlineVariableConflictException("conflict") to "REFACTORING_CONFLICT",
            InlineVariablePreparationException("refused") to "PREPARE_FAILED",
            IllegalStateException("boom") to "REFACTORING_FAILED",
        )
        failures.forEachIndexed { index, (throwable, code) ->
            val fileName = "OperationFailure$index.java"
            val point = validPoint(fileName)

            val json = runOperation {
                InlineVariableOperation(executor = SpyExecutor(throwable = throwable))
                    .execute(project, fileName, point.first, point.second)
            }

            assertFailureCode(json, code)
        }
    }

    fun testProcessCanceledExceptionEscapes() {
        assertCancellationEscapes(ProcessCanceledException())
    }

    fun testCoroutineCancellationEscapes() {
        assertCancellationEscapes(CancellationException("cancelled"))
    }

    private fun assertCancellationEscapes(throwable: RuntimeException) {
        val fileName = "OperationCancellation${throwable.javaClass.simpleName}.java"
        val point = validPoint(fileName)
        try {
            runOperation {
                InlineVariableOperation(executor = SpyExecutor(throwable = throwable))
                    .execute(project, fileName, point.first, point.second)
            }
            fail("expected ${throwable.javaClass.simpleName}")
        } catch (actual: RuntimeException) {
            assertSame(throwable, actual)
        }
    }

    private fun validPoint(fileName: String): Pair<Int, Int> {
        val source =
            "class OperationInline { int value() { int subtotal = 1 + 2; return subtotal + subtotal; } }"
        val target = Path.of(project.basePath!!, fileName)
        Files.createDirectories(target.parent)
        Files.writeString(target, source)
        val virtualFile = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(target.toString())!!
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val offset = source.indexOf("subtotal =")
        val lineIndex = document.getLineNumber(offset)
        return (lineIndex + 1) to (offset - document.getLineStartOffset(lineIndex) + 1)
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

    private class SpyExecutor(
        private val result: InlineVariableExecutionResult = InlineVariableExecutionResult(
            "subtotal",
            2,
            "Inlined 2 occurrences of local variable 'subtotal' and removed its declaration.",
        ),
        private val throwable: RuntimeException? = null,
    ) : InlineVariableExecutor {
        var times = 0
        var wasInvokedOnEdt = false

        override suspend fun inline(
            project: Project,
            selection: InlineVariableSelection,
        ): InlineVariableExecutionResult {
            times++
            wasInvokedOnEdt = ApplicationManager.getApplication().isDispatchThread
            throwable?.let { throw it }
            return result
        }
    }
}
