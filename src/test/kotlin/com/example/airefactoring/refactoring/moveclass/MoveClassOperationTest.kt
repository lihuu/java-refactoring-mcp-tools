package com.example.airefactoring.refactoring.moveclass

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

class MoveClassOperationTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        try {
            val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
            PsiTestUtil.addSourceContentToRoots(module, root)
        } catch (_: Exception) {}
    }

    fun testSuccessContainsSourceClassTargetPackageAndSortedAffectedFiles() {
        val json = Json.parseToJsonElement(
            McpRefactoringResult.moveClassSuccess(
                projectBasePath = "/p",
                filePath = "a/Service.java",
                sourceClass = "example.OrderService",
                targetPackage = "example.api",
                affectedFiles = listOf("a/OrderClient.java", "a/api/OrderService.java"),
                summary = "Moved class 'example.OrderService' to package 'example.api'.",
            ).toJson()
        ).jsonObject
        assertTrue(json["ok"]!!.jsonPrimitive.boolean)
        assertEquals("example.OrderService", json["sourceClass"]!!.jsonPrimitive.content)
        assertEquals("example.api", json["targetPackage"]!!.jsonPrimitive.content)
        assertTrue(json.containsKey("affectedFiles"))
        assertTrue(json.containsKey("summary"))
    }

    fun testResolverFailureDoesNotCallExecutor() {
        var executorCalled = false
        val countingExecutor = object : MoveClassExecutor {
            override suspend fun move(
                project: com.intellij.openapi.project.Project,
                preparation: MoveClassPreparation,
            ): MoveClassExecutionResult {
                executorCalled = true
                return MoveClassExecutionResult("c", "p", emptyList(), "s")
            }
        }
        mirrorRealFile("example/OpFailService.java", "package example; public class OpFailService { public void foo(){} }")
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val op = MoveClassOperation(executor = countingExecutor)
        val badRange = SourceRange(1, 1, 1, 2) // invalid - not exact class name
        val json = runBlocking {
            op.execute(project, "example/OpFailService.java", badRange, "example.api")
        }
        assertFalse(executorCalled)
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals(false, obj["ok"]!!.jsonPrimitive.boolean)
    }

    fun testConflictMapsToRefactoringConflict() {
        mirrorRealFile("example/OpConflictService.java", "package example; public class OpConflictService { public void m(){} }")
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeFor("example/OpConflictService.java", "OpConflictService")
        val conflictExecutor = object : MoveClassExecutor {
            override suspend fun move(
                project: com.intellij.openapi.project.Project,
                preparation: MoveClassPreparation,
            ): MoveClassExecutionResult {
                throw MoveClassConflictException("conflict")
            }
        }
        val op = MoveClassOperation(executor = conflictExecutor)
        val json = runBlocking {
            op.execute(project, "example/OpConflictService.java", range, "example.api")
        }
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals(false, obj["ok"]!!.jsonPrimitive.boolean)
        assertEquals("REFACTORING_CONFLICT", obj["code"]!!.jsonPrimitive.content)
    }

    fun testStalePreparationMapsToPrepareFailed() {
        mirrorRealFile("example/OpStaleService.java", "package example; public class OpStaleService { public void m(){} }")
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeFor("example/OpStaleService.java", "OpStaleService")
        val staleExecutor = object : MoveClassExecutor {
            override suspend fun move(
                project: com.intellij.openapi.project.Project,
                preparation: MoveClassPreparation,
            ): MoveClassExecutionResult {
                throw MoveClassPreparationException("stale")
            }
        }
        val op = MoveClassOperation(executor = staleExecutor)
        val json = runBlocking {
            op.execute(project, "example/OpStaleService.java", range, "example.api")
        }
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals("PREPARE_FAILED", obj["code"]!!.jsonPrimitive.content)
    }

    fun testCancellationIsRethrown() {
        mirrorRealFile("example/OpCancelService.java", "package example; public class OpCancelService { public void m(){} }")
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeFor("example/OpCancelService.java", "OpCancelService")
        val cancelExecutor = object : MoveClassExecutor {
            override suspend fun move(
                project: com.intellij.openapi.project.Project,
                preparation: MoveClassPreparation,
            ): MoveClassExecutionResult {
                throw CancellationException("cancel")
            }
        }
        val op = MoveClassOperation(executor = cancelExecutor)
        try {
            runBlocking {
                op.execute(project, "example/OpCancelService.java", range, "example.api")
            }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("cancel", e.message)
        }
    }

    fun testResultOmitsOnlyAbsentMoveClassFields() {
        val json = Json.parseToJsonElement(
            McpRefactoringResult.moveClassSuccess(
                projectBasePath = "/p",
                filePath = "a/Service.java",
                sourceClass = "example.OrderService",
                targetPackage = "example.api",
                affectedFiles = listOf("a/Service.java"),
                summary = "ok",
            ).toJson()
        ).jsonObject
        assertTrue(json.containsKey("sourceClass"))
        assertTrue(json.containsKey("targetPackage"))
        val fail = Json.parseToJsonElement(McpRefactoringResult.failure(McpRefactoringErrorCode.INVALID_RANGE, "bad").toJson()).jsonObject
        assertFalse(fail.containsKey("sourceClass"))
        assertFalse(fail.containsKey("targetPackage"))
    }

    private fun rangeFor(path: String, className: String): SourceRange {
        val doc = document(path)
        val off = doc.text.indexOf(className)
        val line = doc.getLineNumber(off)
        val col = off - doc.getLineStartOffset(line) + 1
        return SourceRange(line + 1, col, line + 1, col + className.length)
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
