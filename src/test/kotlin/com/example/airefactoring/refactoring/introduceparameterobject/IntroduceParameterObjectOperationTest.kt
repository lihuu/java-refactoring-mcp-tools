package com.example.airefactoring.refactoring.introduceparameterobject

import com.example.airefactoring.mcp.JavaRefactorToolset
import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.mcp.McpRefactoringResult
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.mcpserver.impl.ReflectionToolsProvider
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

class IntroduceParameterObjectOperationTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        try {
            val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
            PsiTestUtil.addSourceContentToRoots(module, root)
        } catch (_: Exception) {}
    }

    fun testSuccessContainsPlacementObjectClassCountsAndSortedAffectedFiles() {
        val json = Json.parseToJsonElement(
            McpRefactoringResult.introduceParameterObjectSuccess(
                projectBasePath = "/p",
                filePath = "a/Service.java",
                methodName = "createInvoice",
                parameterObjectClass = "example.InvoiceRequest",
                placement = "new_top_level",
                mergedParameterCount = 2,
                nativeUsageCount = 1,
                affectedFiles = listOf("a/Caller.java", "a/Service.java"),
                summary = "Introduced parameter object 'example.InvoiceRequest' for 2 parameters of 'createInvoice'.",
            ).toJson()
        ).jsonObject
        assertTrue(json["ok"]!!.jsonPrimitive.content == "true" || json["ok"]!!.jsonPrimitive.boolean)
        assertEquals("example.InvoiceRequest", json["parameterObjectClass"]!!.jsonPrimitive.content)
        assertEquals("new_top_level", json["placement"]!!.jsonPrimitive.content)
        assertEquals(2, json["mergedParameterCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, json["nativeUsageCount"]!!.jsonPrimitive.content.toInt())
    }

    fun testResolverFailureDoesNotCallExecutor() {
        var executorCalled = false
        val countingExecutor = object : IntroduceParameterObjectExecutor {
            override suspend fun introduce(project: com.intellij.openapi.project.Project, preparation: IntroduceParameterObjectPreparation): IntroduceParameterObjectExecutionResult {
                executorCalled = true
                return IntroduceParameterObjectExecutionResult("m","c","p",0,0,emptyList(),"s")
            }
        }
        // Prepare a real file, then call operation with invalid method range -> resolver will fail
        mirrorRealFile("example/OpFailService.java", "package example; public class OpFailService { public void foo(String a){} }")
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val op = IntroduceParameterObjectOperation(executor = countingExecutor)
        val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(
            LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, "example/OpFailService.java").toString())!!
        )!!
        val badRange = SourceRange(1,1,1,2) // invalid - not exact method name
        val json = runBlocking {
            op.execute(project, "example/OpFailService.java", badRange, listOf("a"), "new_inner_class", "Req", null, null, true, false)
        }
        assertFalse(executorCalled)
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals(false, obj["ok"]!!.jsonPrimitive.boolean)
    }

    fun testConflictMapsToRefactoringConflict() {
        // Setup real service with valid method, then executor throws conflict
        mirrorRealFile("example/OpConflictService.java", "package example; public class OpConflictService { public void createInvoice(String c, int d){} }")
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(
            LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, "example/OpConflictService.java").toString())!!
        )!!
        val off = doc.text.indexOf("createInvoice")
        val range = SourceRange(doc.getLineNumber(off)+1, off - doc.getLineStartOffset(doc.getLineNumber(off))+1, doc.getLineNumber(off)+1, off - doc.getLineStartOffset(doc.getLineNumber(off))+1 + "createInvoice".length)
        val conflictExecutor = object : IntroduceParameterObjectExecutor {
            override suspend fun introduce(project: com.intellij.openapi.project.Project, preparation: IntroduceParameterObjectPreparation): IntroduceParameterObjectExecutionResult {
                throw IntroduceParameterObjectConflictException("conflict")
            }
        }
        val op = IntroduceParameterObjectOperation(executor = conflictExecutor)
        val json = runBlocking {
            op.execute(project, "example/OpConflictService.java", range, listOf("c"), "new_inner_class", "Req", null, null, true, false)
        }
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals(false, obj["ok"]!!.jsonPrimitive.boolean)
        assertEquals("REFACTORING_CONFLICT", obj["code"]!!.jsonPrimitive.content)
    }

    fun testStalePreparationMapsToPrepareFailed() {
        mirrorRealFile("example/OpStaleService.java", "package example; public class OpStaleService { public void createInvoice(String c, int d){} }")
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(
            LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, "example/OpStaleService.java").toString())!!
        )!!
        val off = doc.text.indexOf("createInvoice")
        val range = SourceRange(doc.getLineNumber(off)+1, off - doc.getLineStartOffset(doc.getLineNumber(off))+1, doc.getLineNumber(off)+1, off - doc.getLineStartOffset(doc.getLineNumber(off))+1 + "createInvoice".length)
        val staleExecutor = object : IntroduceParameterObjectExecutor {
            override suspend fun introduce(project: com.intellij.openapi.project.Project, preparation: IntroduceParameterObjectPreparation): IntroduceParameterObjectExecutionResult {
                throw IntroduceParameterObjectPreparationException("stale")
            }
        }
        val op = IntroduceParameterObjectOperation(executor = staleExecutor)
        val json = runBlocking {
            op.execute(project, "example/OpStaleService.java", range, listOf("c"), "new_inner_class", "Req", null, null, true, false)
        }
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals("PREPARE_FAILED", obj["code"]!!.jsonPrimitive.content)
    }

    fun testCancellationIsRethrown() {
        mirrorRealFile("example/OpCancelService.java", "package example; public class OpCancelService { public void createInvoice(String c, int d){} }")
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(
            LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, "example/OpCancelService.java").toString())!!
        )!!
        val off = doc.text.indexOf("createInvoice")
        val range = SourceRange(doc.getLineNumber(off)+1, off - doc.getLineStartOffset(doc.getLineNumber(off))+1, doc.getLineNumber(off)+1, off - doc.getLineStartOffset(doc.getLineNumber(off))+1 + "createInvoice".length)
        val cancelExecutor = object : IntroduceParameterObjectExecutor {
            override suspend fun introduce(project: com.intellij.openapi.project.Project, preparation: IntroduceParameterObjectPreparation): IntroduceParameterObjectExecutionResult {
                throw CancellationException("cancel")
            }
        }
        val op = IntroduceParameterObjectOperation(executor = cancelExecutor)
        try {
            runBlocking {
                op.execute(project, "example/OpCancelService.java", range, listOf("c"), "new_inner_class", "Req", null, null, true, false)
            }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("cancel", e.message)
        }
    }

    fun testIntroduceParameterObjectSchemaAndDescriptionPreserveAllPlacementContract() {
        val descriptor = ReflectionToolsProvider().getTools().map { it.descriptor }.single { it.name == "java_introduce_parameter_object" }
        assertEquals(
            setOf(
                "pathInProject","methodStartLine","methodStartColumn","methodEndLine","methodEndColumn",
                "parameterNames","placement","className","targetPackage","existingClassFqn",
                "generateAccessors","escalateVisibility","projectPath"
            ),
            descriptor.inputSchema.propertiesSchema.keys
        )
        assertTrue(descriptor.inputSchema.requiredProperties.containsAll(
            setOf("pathInProject","methodStartLine","methodStartColumn","methodEndLine","methodEndColumn","parameterNames","placement","generateAccessors","escalateVisibility")
        ))
        assertTrue(descriptor.description.contains("new_top_level"))
        assertTrue(descriptor.description.contains("new_inner_class"))
        assertTrue(descriptor.description.contains("existing_class"))
        assertTrue(descriptor.description.contains("native Introduce Parameter Object"))
        assertTrue(descriptor.description.contains("affectedFiles"))
        assertTrue(descriptor.description.contains("Never use direct text edits"))
    }

    fun testResultOmitsOnlyAbsentParameterObjectFields() {
        val json = Json.parseToJsonElement(
            McpRefactoringResult.introduceParameterObjectSuccess(
                projectBasePath = "/p",
                filePath = "a/Service.java",
                methodName = "createInvoice",
                parameterObjectClass = "example.InvoiceRequest",
                placement = "new_top_level",
                mergedParameterCount = 2,
                nativeUsageCount = 1,
                affectedFiles = listOf("a/Caller.java"),
                summary = "ok",
            ).toJson()
        ).jsonObject
        assertTrue(json.containsKey("parameterObjectClass"))
        assertTrue(json.containsKey("placement"))
        val fail = Json.parseToJsonElement(McpRefactoringResult.failure(McpRefactoringErrorCode.INVALID_RANGE, "bad").toJson()).jsonObject
        assertFalse(fail.containsKey("parameterObjectClass"))
        assertFalse(fail.containsKey("placement"))
    }

    private fun mirrorRealFile(path: String, text: String) {
        val t = Path.of(project.basePath!!, path)
        Files.createDirectories(t.parent)
        if (!Files.exists(t)) Files.createFile(t)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(t.toString())!!
        com.intellij.openapi.application.WriteAction.run<RuntimeException> { com.intellij.openapi.vfs.VfsUtil.saveText(vf, text) }
    }
}
