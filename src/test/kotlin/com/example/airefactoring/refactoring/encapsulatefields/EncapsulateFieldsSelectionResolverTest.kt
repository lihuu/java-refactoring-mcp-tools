package com.example.airefactoring.refactoring.encapsulatefields

import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path

class EncapsulateFieldsSelectionResolverTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = EncapsulateFieldsSelectionResolver()

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testResolvesSingleField() {
        fixture()
        val result = resolver.resolve(
            project = project,
            pathInProject = "example/Order.java",
            fieldStartLines = listOf(lineOf("example/Order.java", "amount")),
            fieldStartColumns = listOf(colOf("example/Order.java", "amount")),
            fieldEndLines = listOf(lineOfEnd("example/Order.java", "amount")),
            fieldEndColumns = listOf(colOfEnd("example/Order.java", "amount")),
            getterNames = listOf("getAmount"),
            setterNames = listOf("setAmount"),
            fieldsVisibility = "private",
            accessorsVisibility = "public",
            encapsulateGet = true,
            encapsulateSet = true,
            useAccessorsWhenAccessible = true,
        )
        assertTrue(result is EncapsulateFieldsSelectionResolution.Success)
        val prep = (result as EncapsulateFieldsSelectionResolution.Success).preparation
        assertEquals(listOf("amount"), prep.fieldNames)
        assertEquals(listOf("getAmount"), prep.getterNames)
        assertEquals("private", prep.fieldsVisibility)
    }

    fun testResolvesTwoFieldsSameClass() {
        fixture()
        val result = resolver.resolve(
            project = project,
            pathInProject = "example/Order.java",
            fieldStartLines = listOf(lineOf("example/Order.java", "amount"), lineOf("example/Order.java", "status")),
            fieldStartColumns = listOf(colOf("example/Order.java", "amount"), colOf("example/Order.java", "status")),
            fieldEndLines = listOf(lineOfEnd("example/Order.java", "amount"), lineOfEnd("example/Order.java", "status")),
            fieldEndColumns = listOf(colOfEnd("example/Order.java", "amount"), colOfEnd("example/Order.java", "status")),
            getterNames = listOf("getAmount", "getStatus"),
            setterNames = listOf("setAmount", "setStatus"),
            fieldsVisibility = null,
            accessorsVisibility = "public",
            encapsulateGet = true,
            encapsulateSet = false,
            useAccessorsWhenAccessible = false,
        )
        assertTrue(result is EncapsulateFieldsSelectionResolution.Success)
    }

    fun testRejectsUnequalLists() {
        fixture()
        val result = resolver.resolve(
            project, "example/Order.java",
            listOf(1), listOf(1), listOf(1), listOf(1),
            listOf("getAmount", "getStatus"), listOf("setAmount"),
            "private", "public", true, true, true,
        )
        assertTrue(result is EncapsulateFieldsSelectionResolution.Failure)
        assertEquals("INVALID_RANGE", (result as EncapsulateFieldsSelectionResolution.Failure).code.name)
    }

    fun testRejectsHeterogeneousClass() {
        mirrorFile("example/Other.java", "package example; public class Other { int other; }")
        fixture()
        // Try to select amount from Order and other from Other - different containing classes
        // We need ranges for both fields
        val result = resolver.resolve(
            project, "example/Order.java",
            listOf(lineOf("example/Order.java", "amount"), lineOf("example/Other.java", "other")),
            listOf(colOf("example/Order.java", "amount"), colOf("example/Other.java", "other")),
            listOf(lineOfEnd("example/Order.java", "amount"), lineOfEnd("example/Other.java", "other")),
            listOf(colOfEnd("example/Order.java", "amount"), colOfEnd("example/Other.java", "other")),
            listOf("getAmount", "getOther"), listOf("setAmount", "setOther"),
            "private", "public", true, true, true,
        )
        assertTrue(result is EncapsulateFieldsSelectionResolution.Failure)
    }

    fun testRejectsInvalidGetterName() {
        fixture()
        val result = resolver.resolve(
            project, "example/Order.java",
            listOf(lineOf("example/Order.java", "amount")),
            listOf(colOf("example/Order.java", "amount")),
            listOf(lineOfEnd("example/Order.java", "amount")),
            listOf(colOfEnd("example/Order.java", "amount")),
            listOf("123bad"), listOf("setAmount"),
            "private", "public", true, true, true,
        )
        assertTrue(result is EncapsulateFieldsSelectionResolution.Failure)
    }

    fun testRejectsDuplicateAccessorNames() {
        fixture()
        val result = resolver.resolve(
            project, "example/Order.java",
            listOf(lineOf("example/Order.java", "amount"), lineOf("example/Order.java", "status")),
            listOf(colOf("example/Order.java", "amount"), colOf("example/Order.java", "status")),
            listOf(lineOfEnd("example/Order.java", "amount"), lineOfEnd("example/Order.java", "status")),
            listOf(colOfEnd("example/Order.java", "amount"), colOfEnd("example/Order.java", "status")),
            listOf("getAmount", "getAmount"), listOf("setAmount", "setStatus"),
            "private", "public", true, true, true,
        )
        assertTrue(result is EncapsulateFieldsSelectionResolution.Failure)
    }

    private fun fixture() {
        mirrorFile("example/Order.java", """
            package example;
            public class Order {
                int amount;
                String status;
                int getAmount() { return 0; }
            }
        """.trimIndent())
        // Remove the conflicting getter for most tests - we keep it for conflict test later
        // Actually we need a clean fixture without existing getter for success tests
        // Overwrite without conflicting method for these tests
        mirrorFile("example/Order.java", """
            package example;
            public class Order {
                int amount;
                String status;
            }
        """.trimIndent())
    }

    private fun lineOf(path: String, needle: String): Int = rangeOf(path, needle).first
    private fun colOf(path: String, needle: String): Int = rangeOf(path, needle).second
    private fun lineOfEnd(path: String, needle: String): Int = rangeEndOf(path, needle).first
    private fun colOfEnd(path: String, needle: String): Int = rangeEndOf(path, needle).second

    private fun rangeOf(path: String, needle: String): Pair<Int, Int> {
        val vf = LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, path).toString())!!
        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        val offset = doc.text.indexOf(needle)
        assertTrue("'$needle' missing from $path: ${doc.text}", offset >= 0)
        val line = doc.getLineNumber(offset)
        return (line + 1) to (offset - doc.getLineStartOffset(line) + 1)
    }

    private fun rangeEndOf(path: String, needle: String): Pair<Int, Int> {
        val vf = LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, path).toString())!!
        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        val offset = doc.text.indexOf(needle)
        val end = offset + needle.length
        val line = doc.getLineNumber(end - 1)
        return (line + 1) to (end - doc.getLineStartOffset(line) + 1)
    }

    private fun mirrorFile(path: String, text: String) {
        val target = Path.of(project.basePath!!, path)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
        LocalFileSystem.getInstance().refreshAndFindFileByPath(target.toString())!!
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
    }
}
