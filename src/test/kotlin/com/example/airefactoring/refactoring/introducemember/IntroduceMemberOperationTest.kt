package com.example.airefactoring.refactoring.introducemember

import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
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

class IntroduceMemberOperationTest : LightJavaCodeInsightFixtureTestCase() {

    fun testConstantOperationUsesConstantProfileAndOperationName() {
        val fileName = "ConstantOperation.java"
        val range = validRange(
            fileName,
            "class ConstantOperation { int value() { return 12; } }",
            "12",
        )
        val spy = SpyExecutor(
            IntroduceMemberExecutionResult(
                requestedFieldName = "BASE",
                actualFieldName = "BASE",
                fieldType = "int",
                fieldModifiers = listOf("private", "static", "final"),
                initializationPlace = "FIELD_DECLARATION",
                summary = "Introduced constant 'BASE'.",
            ),
        )
        val operation = IntroduceConstantOperation(executor = spy)

        val json = runOperation {
            operation.execute(project, fileName, range, "BASE")
        }
        val obj = Json.parseToJsonElement(json).jsonObject

        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("java_introduce_constant", obj.getValue("operation").jsonPrimitive.content)
        assertEquals("BASE", obj.getValue("requestedFieldName").jsonPrimitive.content)
        assertEquals("BASE", obj.getValue("actualFieldName").jsonPrimitive.content)
        assertEquals("int", obj.getValue("fieldType").jsonPrimitive.content)
        assertEquals(
            listOf("private", "static", "final"),
            obj.getValue("fieldModifiers").jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse(
            "Constant success omits initializationPlace (Field-only metadata)",
            obj.containsKey("initializationPlace"),
        )
        assertEquals(project.basePath, obj.getValue("projectBasePath").jsonPrimitive.content)
        assertEquals(fileName, obj.getValue("filePath").jsonPrimitive.content)
        assertEquals(1, spy.times)
        assertEquals(IntroduceMemberProfile.Constant, spy.lastProfile)
        assertFalse("native executor must run off EDT", spy.wasInvokedOnEdt)
    }

    fun testFieldOperationUsesInstanceFinalFieldProfileAndOperationName() {
        val fileName = "FieldOperation.java"
        val range = validRange(
            fileName,
            "class FieldOperation { int value() { return 12; } }",
            "12",
        )
        val spy = SpyExecutor(
            IntroduceMemberExecutionResult(
                requestedFieldName = "twelve",
                actualFieldName = "twelve",
                fieldType = "int",
                fieldModifiers = listOf("private", "final"),
                initializationPlace = "FIELD_DECLARATION",
                summary = "Introduced field 'twelve'.",
            ),
        )
        val operation = IntroduceFieldOperation(executor = spy)

        val json = runOperation {
            operation.execute(project, fileName, range, "twelve")
        }
        val obj = Json.parseToJsonElement(json).jsonObject

        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("java_introduce_field", obj.getValue("operation").jsonPrimitive.content)
        assertEquals("twelve", obj.getValue("requestedFieldName").jsonPrimitive.content)
        assertEquals("twelve", obj.getValue("actualFieldName").jsonPrimitive.content)
        assertEquals("int", obj.getValue("fieldType").jsonPrimitive.content)
        assertEquals(
            listOf("private", "final"),
            obj.getValue("fieldModifiers").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            "FIELD_DECLARATION",
            obj.getValue("initializationPlace").jsonPrimitive.content,
        )
        assertEquals(1, spy.times)
        assertEquals(IntroduceMemberProfile.InstanceFinalField, spy.lastProfile)
    }

    fun testInvalidJavaIdentifierUsesInvalidFieldNameWithoutResolving() {
        val spy = SpyExecutor()
        val operation = IntroduceConstantOperation(executor = spy)

        val json = runOperation {
            operation.execute(
                project,
                "Missing.java",
                SourceRange(1, 1, 1, 2),
                "123abc",
            )
        }

        assertFailureCode(json, "INVALID_FIELD_NAME")
        assertEquals(0, spy.times)
    }

    fun testResolverFailureIsPreserved() {
        val json = runOperation {
            IntroduceConstantOperation().execute(
                project,
                "Missing.java",
                SourceRange(1, 1, 1, 2),
                "BASE",
            )
        }

        assertFailureCode(json, "FILE_NOT_FOUND")
    }

    fun testNativeConflictMapsToRefactoringConflict() {
        val fileName = "ConflictOperation.java"
        val range = validRange(
            fileName,
            "class ConflictOperation { int value() { return 12; } }",
            "12",
        )
        val operation = IntroduceFieldOperation(
            executor = ThrowingExecutor(IntroduceMemberConflictException("native conflict")),
        )

        val json = runOperation {
            operation.execute(project, fileName, range, "twelve")
        }

        assertFailureCode(json, "REFACTORING_CONFLICT")
    }

    fun testNativePreparationMapsToPrepareFailed() {
        val fileName = "PrepareOperation.java"
        val range = validRange(
            fileName,
            "class PrepareOperation { int value() { return 12; } }",
            "12",
        )
        val operation = IntroduceConstantOperation(
            executor = ThrowingExecutor(IntroduceMemberPreparationException("native refusal")),
        )

        val json = runOperation {
            operation.execute(project, fileName, range, "BASE")
        }

        assertFailureCode(json, "PREPARE_FAILED")
    }

    fun testUnexpectedFailureMapsToRefactoringFailed() {
        val fileName = "UnexpectedOperation.java"
        val range = validRange(
            fileName,
            "class UnexpectedOperation { int value() { return 12; } }",
            "12",
        )
        val operation = IntroduceConstantOperation(
            executor = ThrowingExecutor(IllegalStateException("boom")),
        )

        val json = runOperation {
            operation.execute(project, fileName, range, "BASE")
        }

        assertFailureCode(json, "REFACTORING_FAILED")
    }

    fun testCoroutineAndProcessCancellationPropagateUnchanged() {
        val coroutineFileName = "CoroutineCancelOperation.java"
        val coroutineRange = validRange(
            coroutineFileName,
            "class CoroutineCancelOperation { int value() { return 12; } }",
            "12",
        )
        val coroutineOperation = IntroduceConstantOperation(
            executor = ThrowingExecutor(CancellationException("cancelled")),
        )
        try {
            runOperation {
                coroutineOperation.execute(project, coroutineFileName, coroutineRange, "BASE")
            }
            fail("expected CancellationException")
        } catch (expected: CancellationException) {
            // Coroutine cancellation must remain visible to IntelliJ and the MCP host.
        }

        val processFileName = "ProcessCancelOperation.java"
        val processRange = validRange(
            processFileName,
            "class ProcessCancelOperation { int value() { return 12; } }",
            "12",
        )
        val processOperation = IntroduceConstantOperation(
            executor = ThrowingExecutor(ProcessCanceledException()),
        )
        try {
            runOperation {
                processOperation.execute(project, processFileName, processRange, "BASE")
            }
            fail("expected ProcessCanceledException")
        } catch (expected: ProcessCanceledException) {
            // Cancellation must remain visible to IntelliJ and the MCP host.
        }
    }

    fun testSuccessNormalizesTheResolverApprovedProjectRelativePath() {
        val fileName = "nested/DeepOperation.java"
        val range = validRange(
            fileName,
            "class DeepOperation { int value() { return 12; } }",
            "12",
        )
        val spy = SpyExecutor()
        val operation = IntroduceFieldOperation(executor = spy)

        val json = runOperation {
            operation.execute(project, fileName, range, "twelve")
        }
        val obj = Json.parseToJsonElement(json).jsonObject

        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals(fileName, obj.getValue("filePath").jsonPrimitive.content)
        assertEquals(1, spy.times)
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

    private fun validRange(fileName: String, source: String, expressionText: String): SourceRange {
        val virtualFile = mirrorRealFile(fileName, source)
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        val startOffset = document.text.indexOf(expressionText)
        assertTrue("expression '$expressionText' missing in $fileName", startOffset >= 0)
        return range(document, startOffset, startOffset + expressionText.length)
    }

    private fun mirrorRealFile(fileName: String, text: String): VirtualFile {
        val target = Path.of(project.basePath!!, fileName)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(target.toString())!!
    }

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
        private val result: IntroduceMemberExecutionResult = IntroduceMemberExecutionResult(
            requestedFieldName = "twelve",
            actualFieldName = "twelve",
            fieldType = "int",
            fieldModifiers = listOf("private", "final"),
            initializationPlace = "FIELD_DECLARATION",
            summary = "Introduced field 'twelve'.",
        ),
    ) : IntroduceMemberExecutor {
        var times = 0
        var lastProfile: IntroduceMemberProfile? = null
        var wasInvokedOnEdt = false

        override suspend fun introduce(
            project: Project,
            selection: IntroduceMemberSelection,
            preferredName: String,
            profile: IntroduceMemberProfile,
        ): IntroduceMemberExecutionResult {
            times++
            lastProfile = profile
            wasInvokedOnEdt = ApplicationManager.getApplication().isDispatchThread
            return result
        }
    }

    private class ThrowingExecutor(
        private val throwable: Throwable,
    ) : IntroduceMemberExecutor {
        override suspend fun introduce(
            project: Project,
            selection: IntroduceMemberSelection,
            preferredName: String,
            profile: IntroduceMemberProfile,
        ): IntroduceMemberExecutionResult = throw throwable
    }
}
