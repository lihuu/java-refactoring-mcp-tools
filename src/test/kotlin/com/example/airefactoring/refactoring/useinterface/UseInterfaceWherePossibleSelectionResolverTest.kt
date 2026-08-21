package com.example.airefactoring.refactoring.useinterface

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path

class UseInterfaceWherePossibleSelectionResolverTest : LightJavaCodeInsightFixtureTestCase() {
    private val resolver = UseInterfaceWherePossibleSelectionResolver()
    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21
    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testResolvesDirectInterfaceTarget() {
        fixture()
        val result = resolve("a/Impl.java", "Impl", "a.Widenable")
        assertTrue(result is UseInterfaceWherePossibleSelectionResolution.Success)
    }

    fun testResolvesTransitiveInterfaceTarget() {
        fixture()
        val result = resolve("a/Impl.java", "Impl", "a.BaseWidenable")
        assertTrue(result is UseInterfaceWherePossibleSelectionResolution.Success)
    }

    fun testRejectsClassSupertypeTarget() {
        fixture()
        val result = resolve("a/Impl.java", "Impl", "a.ImplBase")
        assertTrue(result is UseInterfaceWherePossibleSelectionResolution.Failure)
        assertEquals("UNSUPPORTED_TARGET", (result as UseInterfaceWherePossibleSelectionResolution.Failure).code.name)
    }

    fun testRejectsUnrelatedInterfaceTarget() {
        fixture()
        val result = resolve("a/Impl.java", "Impl", "a.Unrelated")
        assertTrue(result is UseInterfaceWherePossibleSelectionResolution.Failure)
    }

    fun testRejectsSelfTarget() {
        fixture()
        val result = resolve("a/Impl.java", "Impl", "a.Impl")
        assertTrue(result is UseInterfaceWherePossibleSelectionResolution.Failure)
    }

    fun testRejectsMissingTargetFqn() {
        fixture()
        val result = resolve("a/Impl.java", "Impl", "a.Missing")
        assertTrue(result is UseInterfaceWherePossibleSelectionResolution.Failure)
    }

    fun testRejectsInvalidFqn() {
        fixture()
        val result = resolve("a/Impl.java", "Impl", "bad..pkg")
        assertTrue(result is UseInterfaceWherePossibleSelectionResolution.Failure)
    }

    fun testRejectsInterfaceSourceRange() {
        fixture()
        val result = resolve("a/Samples.java", "Widenable", "a.Widenable")
        assertTrue(result is UseInterfaceWherePossibleSelectionResolution.Failure)
    }

    fun testRejectsRangeMiss() {
        fixture()
        val result = resolver.resolve(
            project, "a/Impl.java",
            1, 1, 1, 2,
            "a.Widenable",
        )
        assertTrue(result is UseInterfaceWherePossibleSelectionResolution.Failure)
    }

    fun testUsageSiteSelectionFailureStatesDeclarationOnlyRule() {
        fixture()
        mirrorFile(
            "a/Checkout.java",
            "package a; public class Checkout { private Impl impl; }",
        )
        val result = resolve("a/Checkout.java", "Impl", "a.Widenable")
        assertTrue("expected failure but was $result", result is UseInterfaceWherePossibleSelectionResolution.Failure)
        val failure = result as UseInterfaceWherePossibleSelectionResolution.Failure
        assertEquals("UNSUPPORTED_TARGET", failure.code.name)
        assertTrue(
            "message must reject usage sites explicitly but was: ${failure.message}",
            failure.message.contains("not a usage site"),
        )
    }

    private fun resolve(
        path: String,
        needle: String,
        targetFqn: String,
    ): UseInterfaceWherePossibleSelectionResolution =
        resolver.resolve(
            project, path,
            lineOf(path, needle), colOf(path, needle),
            lineEndOf(path, needle), colEndOf(path, needle),
            targetFqn,
        )

    private fun fixture() {
        mirrorFile(
            "a/Samples.java",
            """
                package a;
                public interface BaseWidenable { String label(); }
                public interface Widenable extends BaseWidenable { String label(); }
                public class ImplBase { public void common() {} }
                public class Unrelated { }
            """.trimIndent(),
        )
        mirrorFile(
            "a/Impl.java",
            "package a; public class Impl extends ImplBase implements Widenable { @Override public String label() { return \"x\"; } public void onlyOnImpl() {} }",
        )
    }

    private fun lineOf(path: String, needle: String) = rangeOf(path, needle).first
    private fun colOf(path: String, needle: String) = rangeOf(path, needle).second
    private fun lineEndOf(path: String, needle: String) = rangeEndOf(path, needle).first
    private fun colEndOf(path: String, needle: String) = rangeEndOf(path, needle).second
    private fun rangeOf(path: String, needle: String): Pair<Int, Int> {
        val vf = LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, path).toString())!!
        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        val off = doc.text.indexOf(needle)
        assertTrue("'$needle' missing", off >= 0)
        val line = doc.getLineNumber(off)
        return (line + 1) to (off - doc.getLineStartOffset(line) + 1)
    }

    private fun rangeEndOf(path: String, needle: String): Pair<Int, Int> {
        val vf = LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, path).toString())!!
        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        val off = doc.text.indexOf(needle)
        val end = off + needle.length
        val line = doc.getLineNumber(end - 1)
        return (line + 1) to (end - doc.getLineStartOffset(line) + 1)
    }

    private fun mirrorFile(path: String, text: String) {
        val t = Path.of(project.basePath!!, path)
        Files.createDirectories(t.parent); try { Files.writeString(t, text) } catch (_: Exception) {}
        try { myFixture.addFileToProject(path, text) } catch (_: Exception) {}
        LocalFileSystem.getInstance().refreshAndFindFileByPath(t.toString())
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
    }
}
