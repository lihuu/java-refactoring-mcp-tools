package com.example.airefactoring.refactoring.extractmethodobject

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.mcp.McpRefactoringResult
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

class ExtractMethodObjectOperationTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        try {
            val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
            PsiTestUtil.addSourceContentToRoots(module, root)
        } catch (_: Exception) {}
    }

    fun testSuccessContainsObjectClassMethodNameCountsAndSortedAffectedFiles() {
        val json = Json.parseToJsonElement(
            McpRefactoringResult.replaceMethodWithMethodObjectSuccess(
                projectBasePath = "/p",
                filePath = "a/Service.java",
                methodName = "price",
                methodObjectClass = "example.OrderService.PriceObject",
                methodObjectMethodName = "invoke",
                migratedFieldCount = 2,
                affectedFiles = listOf("a/Service.java"),
                summary = "Replaced method 'price' with method object 'example.OrderService.PriceObject'.",
            ).toJson()
        ).jsonObject
        assertTrue(json["ok"]!!.jsonPrimitive.boolean)
        assertEquals("example.OrderService.PriceObject", json["methodObjectClass"]!!.jsonPrimitive.content)
        assertEquals("invoke", json["methodObjectMethodName"]!!.jsonPrimitive.content)
        assertEquals(2, json["migratedFieldCount"]!!.jsonPrimitive.content.toInt())
        assertTrue(json.containsKey("affectedFiles"))
        assertTrue(json.containsKey("summary"))
    }

    fun testResolverFailureDoesNotCallExecutor() {
        var executorCalled = false
        val countingExecutor = object : ExtractMethodObjectExecutor {
            override suspend fun replace(
                project: com.intellij.openapi.project.Project,
                preparation: ExtractMethodObjectPreparation,
            ): ExtractMethodObjectExecutionResult {
                executorCalled = true
                return ExtractMethodObjectExecutionResult("m", "c", "invoke", 0, emptyList(), "s")
            }
        }
        mirrorRealFile("example/OpFailService.java", "package example; public class OpFailService { public void foo(){ System.out.println(1); } }")
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val op = ExtractMethodObjectOperation(executor = countingExecutor)
        val badRange = SourceRange(1, 1, 1, 2) // invalid - not exact method name
        val json = runBlocking {
            op.execute(project, "example/OpFailService.java", badRange, "Req", "invoke")
        }
        assertFalse(executorCalled)
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals(false, obj["ok"]!!.jsonPrimitive.boolean)
    }

    fun testConflictMapsToRefactoringConflict() {
        mirrorRealFile("example/OpConflictService.java", "package example; public class OpConflictService { public void createInvoice(String c){ System.out.println(c);} }")
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeFor("example/OpConflictService.java", "createInvoice")
        val conflictExecutor = object : ExtractMethodObjectExecutor {
            override suspend fun replace(
                project: com.intellij.openapi.project.Project,
                preparation: ExtractMethodObjectPreparation,
            ): ExtractMethodObjectExecutionResult {
                throw ExtractMethodObjectConflictException("conflict")
            }
        }
        val op = ExtractMethodObjectOperation(executor = conflictExecutor)
        val json = runBlocking {
            op.execute(project, "example/OpConflictService.java", range, "Req", "invoke")
        }
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals(false, obj["ok"]!!.jsonPrimitive.boolean)
        assertEquals("REFACTORING_CONFLICT", obj["code"]!!.jsonPrimitive.content)
    }

    fun testStalePreparationMapsToPrepareFailed() {
        mirrorRealFile("example/OpStaleService.java", "package example; public class OpStaleService { public void createInvoice(String c){ System.out.println(c);} }")
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeFor("example/OpStaleService.java", "createInvoice")
        val staleExecutor = object : ExtractMethodObjectExecutor {
            override suspend fun replace(
                project: com.intellij.openapi.project.Project,
                preparation: ExtractMethodObjectPreparation,
            ): ExtractMethodObjectExecutionResult {
                throw ExtractMethodObjectPreparationException("stale")
            }
        }
        val op = ExtractMethodObjectOperation(executor = staleExecutor)
        val json = runBlocking {
            op.execute(project, "example/OpStaleService.java", range, "Req", "invoke")
        }
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals("PREPARE_FAILED", obj["code"]!!.jsonPrimitive.content)
    }

    fun testCancellationIsRethrown() {
        mirrorRealFile("example/OpCancelService.java", "package example; public class OpCancelService { public void createInvoice(String c){ System.out.println(c);} }")
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeFor("example/OpCancelService.java", "createInvoice")
        val cancelExecutor = object : ExtractMethodObjectExecutor {
            override suspend fun replace(
                project: com.intellij.openapi.project.Project,
                preparation: ExtractMethodObjectPreparation,
            ): ExtractMethodObjectExecutionResult {
                throw CancellationException("cancel")
            }
        }
        val op = ExtractMethodObjectOperation(executor = cancelExecutor)
        try {
            runBlocking {
                op.execute(project, "example/OpCancelService.java", range, "Req", "invoke")
            }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("cancel", e.message)
        }
    }

    fun testResultOmitsOnlyAbsentMethodObjectFields() {
        val json = Json.parseToJsonElement(
            McpRefactoringResult.replaceMethodWithMethodObjectSuccess(
                projectBasePath = "/p",
                filePath = "a/Service.java",
                methodName = "price",
                methodObjectClass = "example.OrderService.PriceObject",
                methodObjectMethodName = "invoke",
                migratedFieldCount = 2,
                affectedFiles = listOf("a/Service.java"),
                summary = "ok",
            ).toJson()
        ).jsonObject
        assertTrue(json.containsKey("methodObjectClass"))
        assertTrue(json.containsKey("methodObjectMethodName"))
        assertTrue(json.containsKey("migratedFieldCount"))
        val fail = Json.parseToJsonElement(McpRefactoringResult.failure(McpRefactoringErrorCode.INVALID_RANGE, "bad").toJson()).jsonObject
        assertFalse(fail.containsKey("methodObjectClass"))
        assertFalse(fail.containsKey("methodObjectMethodName"))
        assertFalse(fail.containsKey("migratedFieldCount"))
    }

    private fun rangeFor(path: String, methodName: String): SourceRange {
        val doc = document(path)
        val off = doc.text.indexOf(methodName)
        val line = doc.getLineNumber(off)
        val col = off - doc.getLineStartOffset(line) + 1
        return SourceRange(line + 1, col, line + 1, col + methodName.length)
    }

    private fun document(path: String): com.intellij.openapi.editor.Document {
        val vf = LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, path).toString())!!
        return FileDocumentManager.getInstance().getDocument(vf)!!
    }

    private fun mirrorRealFile(path: String, text: String) {
        val t = Path.of(project.basePath!!, path)
        Files.createDirectories(t.parent)
        if (!Files.exists(t)) Files.createFile(t)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(t.toString())!!
        WriteAction.run<RuntimeException> { VfsUtil.saveText(vf, text) }
    }
}
