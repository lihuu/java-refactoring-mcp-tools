package com.example.airefactoring.refactoring.extractinterface

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path

class ExtractInterfaceSelectionResolverTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = ExtractInterfaceSelectionResolver()

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testResolvesSinglePublicMethodSamePackage() {
        fixture()
        val result = resolver.resolve(
            project = project,
            pathInProject = "example/Service.java",
            sourceClassStartLine = lineOf("example/Service.java", "Service"),
            sourceClassStartColumn = colOf("example/Service.java", "Service"),
            sourceClassEndLine = lineOfEnd("example/Service.java", "Service"),
            sourceClassEndColumn = colOfEnd("example/Service.java", "Service"),
            memberStartLines = listOf(lineOf("example/Service.java", "doIt")),
            memberStartColumns = listOf(colOf("example/Service.java", "doIt")),
            memberEndLines = listOf(lineOfEnd("example/Service.java", "doIt")),
            memberEndColumns = listOf(colOfEnd("example/Service.java", "doIt")),
            interfaceName = "ServiceApi",
            targetPackage = null,
        )
        assertTrue(result is ExtractInterfaceSelectionResolution.Success)
        val prep = (result as ExtractInterfaceSelectionResolution.Success).preparation
        assertEquals("ServiceApi", prep.interfaceName)
        assertEquals("example.ServiceApi", prep.effectiveQualifiedNameSnapshot)
    }

    fun testResolvesTwoMembersExplicitPackage() {
        fixture()
        val result = resolver.resolve(
            project = project,
            pathInProject = "example/Service.java",
            sourceClassStartLine = lineOf("example/Service.java", "Service"),
            sourceClassStartColumn = colOf("example/Service.java", "Service"),
            sourceClassEndLine = lineOfEnd("example/Service.java", "Service"),
            sourceClassEndColumn = colOfEnd("example/Service.java", "Service"),
            memberStartLines = listOf(lineOf("example/Service.java", "doIt"), lineOf("example/Service.java", "COUNT")),
            memberStartColumns = listOf(colOf("example/Service.java", "doIt"), colOf("example/Service.java", "COUNT")),
            memberEndLines = listOf(lineOfEnd("example/Service.java", "doIt"), lineOfEnd("example/Service.java", "COUNT")),
            memberEndColumns = listOf(colOfEnd("example/Service.java", "doIt"), colOfEnd("example/Service.java", "COUNT")),
            interfaceName = "ServiceApi",
            targetPackage = "example.api",
        )
        assertTrue(result is ExtractInterfaceSelectionResolution.Success)
        assertEquals("example.api.ServiceApi", (result as ExtractInterfaceSelectionResolution.Success).preparation.effectiveQualifiedNameSnapshot)
    }

    fun testRejectsEmptyMembers() {
        fixture()
        val result = resolver.resolve(
            project, "example/Service.java",
            lineOf("example/Service.java", "Service"), colOf("example/Service.java", "Service"),
            lineOfEnd("example/Service.java", "Service"), colOfEnd("example/Service.java", "Service"),
            emptyList(), emptyList(), emptyList(), emptyList(),
            "ServiceApi", null,
        )
        assertTrue(result is ExtractInterfaceSelectionResolution.Failure)
        assertEquals("INVALID_RANGE", (result as ExtractInterfaceSelectionResolution.Failure).code.name)
    }

    fun testRejectsUnequalMemberLists() {
        fixture()
        val result = resolver.resolve(
            project, "example/Service.java",
            lineOf("example/Service.java", "Service"), colOf("example/Service.java", "Service"),
            lineOfEnd("example/Service.java", "Service"), colOfEnd("example/Service.java", "Service"),
            listOf(1, 2), listOf(1), listOf(1, 2), listOf(1, 2),
            "ServiceApi", null,
        )
        assertTrue(result is ExtractInterfaceSelectionResolution.Failure)
    }

    fun testRejectsNonPublicMethod() {
        fixture()
        val result = resolver.resolve(
            project, "example/Service.java",
            lineOf("example/Service.java", "Service"), colOf("example/Service.java", "Service"),
            lineOfEnd("example/Service.java", "Service"), colOfEnd("example/Service.java", "Service"),
            listOf(lineOf("example/Service.java", "help")),
            listOf(colOf("example/Service.java", "help")),
            listOf(lineOfEnd("example/Service.java", "help")),
            listOf(colOfEnd("example/Service.java", "help")),
            "ServiceApi", null,
        )
        assertTrue(result is ExtractInterfaceSelectionResolution.Failure)
    }

    fun testRejectsInvalidInterfaceName() {
        fixture()
        val result = resolver.resolve(
            project, "example/Service.java",
            lineOf("example/Service.java", "Service"), colOf("example/Service.java", "Service"),
            lineOfEnd("example/Service.java", "Service"), colOfEnd("example/Service.java", "Service"),
            listOf(lineOf("example/Service.java", "doIt")),
            listOf(colOf("example/Service.java", "doIt")),
            listOf(lineOfEnd("example/Service.java", "doIt")),
            listOf(colOfEnd("example/Service.java", "doIt")),
            "123bad", null,
        )
        assertTrue(result is ExtractInterfaceSelectionResolution.Failure)
    }

    fun testRejectsDuplicateMember() {
        fixture()
        val line = lineOf("example/Service.java", "doIt")
        val col = colOf("example/Service.java", "doIt")
        val lineE = lineOfEnd("example/Service.java", "doIt")
        val colE = colOfEnd("example/Service.java", "doIt")
        val result = resolver.resolve(
            project, "example/Service.java",
            lineOf("example/Service.java", "Service"), colOf("example/Service.java", "Service"),
            lineOfEnd("example/Service.java", "Service"), colOfEnd("example/Service.java", "Service"),
            listOf(line, line), listOf(col, col), listOf(lineE, lineE), listOf(colE, colE),
            "ServiceApi", null,
        )
        assertTrue(result is ExtractInterfaceSelectionResolution.Failure)
    }

    fun testRejectsHeterogeneousClass() {
        mirrorFile("example/Other.java", "package example; public class Other { public void other() {} }")
        fixture()
        val result = resolver.resolve(
            project, "example/Service.java",
            lineOf("example/Service.java", "Service"), colOf("example/Service.java", "Service"),
            lineOfEnd("example/Service.java", "Service"), colOfEnd("example/Service.java", "Service"),
            listOf(lineOf("example/Other.java", "other")),
            listOf(colOf("example/Other.java", "other")),
            listOf(lineOfEnd("example/Other.java", "other")),
            listOf(colOfEnd("example/Other.java", "other")),
            "ServiceApi", null,
        )
        assertTrue(result is ExtractInterfaceSelectionResolution.Failure)
    }

    fun testRejectsInvalidPackage() {
        fixture()
        val result = resolver.resolve(
            project, "example/Service.java",
            lineOf("example/Service.java", "Service"), colOf("example/Service.java", "Service"),
            lineOfEnd("example/Service.java", "Service"), colOfEnd("example/Service.java", "Service"),
            listOf(lineOf("example/Service.java", "doIt")),
            listOf(colOf("example/Service.java", "doIt")),
            listOf(lineOfEnd("example/Service.java", "doIt")),
            listOf(colOfEnd("example/Service.java", "doIt")),
            "ServiceApi", "bad..pkg",
        )
        assertTrue(result is ExtractInterfaceSelectionResolution.Failure)
    }

    private fun fixture() {
        mirrorFile("example/Service.java", """
            package example;
            public class Service {
                public void doIt() {}
                public static final int COUNT = 1;
                private void help() {}
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
