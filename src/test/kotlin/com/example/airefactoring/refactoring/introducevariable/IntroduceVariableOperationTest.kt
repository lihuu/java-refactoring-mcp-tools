package com.example.airefactoring.refactoring.introducevariable

import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.dispatchAllEventsInIdeEventQueue
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IntroduceVariableOperationTest : LightJavaCodeInsightFixtureTestCase() {

    fun testSuccessReturnsRequestedActualTypeAndProjectPaths() {
        val range = validRange("OperationSuccess.java")
        val spy = SpyExecutor(
            IntroduceVariableExecutionResult(
                "sum1",
                "int",
                "Introduced local variable 'sum1'.",
            ),
        )
        val operation = IntroduceVariableOperation(executor = spy)

        val json = runOperation {
            operation.execute(project, "OperationSuccess.java", range, "sum")
        }
        val obj = Json.parseToJsonElement(json).jsonObject

        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("java_introduce_variable", obj.getValue("operation").jsonPrimitive.content)
        assertEquals("sum", obj.getValue("requestedVariableName").jsonPrimitive.content)
        assertEquals("sum1", obj.getValue("actualVariableName").jsonPrimitive.content)
        assertEquals("int", obj.getValue("variableType").jsonPrimitive.content)
        assertEquals(project.basePath, obj.getValue("projectBasePath").jsonPrimitive.content)
        assertEquals("OperationSuccess.java", obj.getValue("filePath").jsonPrimitive.content)
        assertEquals(1, spy.times)
    }

    fun testInvalidNameWinsBeforeFileResolutionAndDoesNotInvokeExecutor() {
        val spy = SpyExecutor()
        val operation = IntroduceVariableOperation(executor = spy)

        val json = runOperation {
            operation.execute(
                project,
                "Missing.java",
                SourceRange(1, 1, 1, 2),
                " total ",
            )
        }

        assertFailureCode(json, "INVALID_VARIABLE_NAME")
        assertEquals(0, spy.times)
    }

    fun testResolverFailureCodeIsPreserved() {
        val json = runOperation {
            IntroduceVariableOperation().execute(
                project,
                "Missing.java",
                SourceRange(1, 1, 1, 2),
                "value",
            )
        }

        assertFailureCode(json, "FILE_NOT_FOUND")
    }

    fun testUnsupportedExpressionCodeIsPreservedWithoutInvokingExecutor() {
        val range = configureMarkedFile(
            "OperationVoid.java",
            "class OperationVoid { void run() { <selection>System.out.println(1)</selection>; } }",
        )
        val spy = SpyExecutor()

        val json = runOperation {
            IntroduceVariableOperation(executor = spy).execute(
                project,
                "OperationVoid.java",
                range,
                "printed",
            )
        }

        assertFailureCode(json, "UNSUPPORTED_EXPRESSION")
        assertEquals(0, spy.times)
    }

    fun testPreparationExceptionMapsToPrepareFailed() {
        val range = validRange("OperationPrepare.java")
        val operation = IntroduceVariableOperation(
            executor = ThrowingExecutor(IntroduceVariablePreparationException("native refusal")),
        )

        val json = runOperation {
            operation.execute(project, "OperationPrepare.java", range, "sum")
        }

        assertFailureCode(json, "PREPARE_FAILED")
    }

    fun testUnexpectedExceptionMapsToRefactoringFailed() {
        val range = validRange("OperationUnexpected.java")
        val operation = IntroduceVariableOperation(
            executor = ThrowingExecutor(IllegalStateException("boom")),
        )

        val json = runOperation {
            operation.execute(project, "OperationUnexpected.java", range, "sum")
        }

        assertFailureCode(json, "REFACTORING_FAILED")
    }

    fun testProcessCanceledExceptionEscapes() {
        val range = validRange("OperationCancel.java")
        val operation = IntroduceVariableOperation(
            executor = ThrowingExecutor(ProcessCanceledException()),
        )

        try {
            runOperation {
                operation.execute(project, "OperationCancel.java", range, "sum")
            }
            fail("expected ProcessCanceledException")
        } catch (expected: ProcessCanceledException) {
            // Cancellation must remain visible to IntelliJ and the MCP host.
        }
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

    private fun configureMarkedFile(fileName: String, markedText: String): SourceRange {
        myFixture.configureByText(fileName, markedText)
        val document = myFixture.editor.document

        fun position(offset: Int): Pair<Int, Int> {
            val line = document.getLineNumber(offset)
            return (line + 1) to (offset - document.getLineStartOffset(line) + 1)
        }

        val (startLine, startColumn) = position(myFixture.editor.selectionModel.selectionStart)
        val (endLine, endColumn) = position(myFixture.editor.selectionModel.selectionEnd)
        val target = Path.of(project.basePath!!, fileName)
        Files.createDirectories(target.parent)
        Files.writeString(target, document.text)
        LocalFileSystem.getInstance().refreshAndFindFileByPath(target.toString())!!
        return SourceRange(startLine, startColumn, endLine, endColumn)
    }

    private fun validRange(fileName: String): SourceRange = configureMarkedFile(
        fileName,
        "class OperationFixture { int total() { return <selection>10 + 20</selection>; } }",
    )

    private fun assertFailureCode(json: String, expected: String) {
        val obj = Json.parseToJsonElement(json).jsonObject
        assertFalse(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals(expected, obj.getValue("code").jsonPrimitive.content)
    }

    private class SpyExecutor(
        private val result: IntroduceVariableExecutionResult = IntroduceVariableExecutionResult(
            "sum",
            "int",
            "Introduced local variable 'sum'.",
        ),
    ) : IntroduceVariableExecutor {
        var times = 0

        override fun introduce(
            project: Project,
            selection: IntroduceVariableSelection,
            preferredVariableName: String,
        ): IntroduceVariableExecutionResult {
            times++
            return result
        }
    }

    private class ThrowingExecutor(
        private val throwable: Throwable,
    ) : IntroduceVariableExecutor {
        override fun introduce(
            project: Project,
            selection: IntroduceVariableSelection,
            preferredVariableName: String,
        ): IntroduceVariableExecutionResult = throw throwable
    }
}
