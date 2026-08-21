package com.example.airefactoring.refactoring.locator

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import junit.framework.TestCase

class LocateSymbolOperationTest : LightJavaCodeInsightFixtureTestCase() {

    private val operation = LocateSymbolOperation()

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    private fun fixture() {
        mirrorFile(
            "example/Widget.java",
            """
                package example;

                public class Widget {
                    private int weight;
                    public Widget(int weight) { this.weight = weight; }
                    public int scale(int factor) {
                        int doubled = factor * 2;
                        return weight * doubled;
                    }
                }
            """.trimIndent(),
        )
    }

    private fun mirrorFile(path: String, text: String) {
        val t = Path.of(project.basePath!!, path)
        Files.createDirectories(t.parent)
        Files.writeString(t, text)
        LocalFileSystem.getInstance().refreshAndFindFileByPath(t.toString())
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    fun testFindsAllDeclarationsOrderedByOffsetWithExactRanges() {
        fixture()
        val obj = Json.parseToJsonElement(runBlocking { operation.execute(project, "example/Widget.java", "weight", null) }).jsonObject
        TestCase.assertEquals(true, obj.getValue("ok").jsonPrimitive.boolean)
        TestCase.assertEquals("java_locate_symbol", obj.getValue("operation").jsonPrimitive.content)
        TestCase.assertEquals(2, obj.getValue("candidateCount").jsonPrimitive.int)
        val candidates = obj.getValue("candidates").jsonArray
        val field = candidates[0].jsonObject
        TestCase.assertEquals("field", field.getValue("kind").jsonPrimitive.content)
        TestCase.assertEquals("example.Widget", field.getValue("containingClassQualifiedName").jsonPrimitive.content)
        TestCase.assertEquals(4, field.getValue("startLine").jsonPrimitive.int)
        TestCase.assertEquals(17, field.getValue("startColumn").jsonPrimitive.int)
        TestCase.assertEquals(23, field.getValue("endColumn").jsonPrimitive.int)
        val parameter = candidates[1].jsonObject
        TestCase.assertEquals("parameter", parameter.getValue("kind").jsonPrimitive.content)
        TestCase.assertEquals(5, parameter.getValue("startLine").jsonPrimitive.int)
    }

    fun testKindFilterNarrowsResults() {
        fixture()
        val obj = Json.parseToJsonElement(runBlocking { operation.execute(project, "example/Widget.java", "weight", "field") }).jsonObject
        TestCase.assertEquals(1, obj.getValue("candidateCount").jsonPrimitive.int)
        TestCase.assertEquals(
            "field",
            obj.getValue("candidates").jsonArray[0].jsonObject.getValue("kind").jsonPrimitive.content,
        )
    }

    fun testUnknownSymbolReturnsEmptySuccess() {
        fixture()
        val obj = Json.parseToJsonElement(runBlocking { operation.execute(project, "example/Widget.java", "nope", null) }).jsonObject
        TestCase.assertEquals(0, obj.getValue("candidateCount").jsonPrimitive.int)
        TestCase.assertEquals(0, obj.getValue("candidates").jsonArray.size)
    }

    fun testMissingFileReturnsFileNotFoundFailure() {
        val json = runBlocking { operation.execute(project, "example/Missing.java", "x", null) }
        val obj = Json.parseToJsonElement(json).jsonObject
        TestCase.assertEquals("false", obj.getValue("ok").jsonPrimitive.content)
        TestCase.assertEquals("FILE_NOT_FOUND", obj.getValue("code").jsonPrimitive.content)
    }
}
