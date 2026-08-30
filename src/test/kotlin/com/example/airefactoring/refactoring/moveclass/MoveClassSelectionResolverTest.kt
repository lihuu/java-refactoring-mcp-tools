package com.example.airefactoring.refactoring.moveclass

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path

class MoveClassSelectionResolverTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = MoveClassSelectionResolver()

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testResolvesExactClassNameAndContainingFile() {
        val path = "example/McResolver.java"
        mirrorRealFile(path, """
            package example;
            public class McResolver {
                public void m() {}
            }
        """.trimIndent())
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeForClass(path, "McResolver")
        val res = resolver.resolve(project, path, range, "example.api")
        assertTrue("expected Success but was $res", res is MoveClassSelectionResolution.Success)
        val prep = (res as MoveClassSelectionResolution.Success).preparation
        assertEquals("example.McResolver", prep.sourceClassFqn)
        assertEquals("example.api", prep.targetPackage)
        assertTrue(prep.affectedVirtualFiles.any { it.path.contains("McResolver") })
    }

    fun testRejectsClassRangeThatIncludesModifiersOrTypeParameters() {
        val path = "example/McResolverBadRange.java"
        mirrorRealFile(path, """
            package example;
            public class McResolverBadRange {
                public void m() {}
            }
        """.trimIndent())
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val doc = document(path)
        // range that includes "public" modifier
        val pubOffset = doc.text.indexOf("public")
        val pubRange = range(doc, pubOffset, pubOffset + 6)
        val res1 = resolver.resolve(project, path, pubRange, "example.api")
        assertFailure(res1, McpRefactoringErrorCode.INVALID_RANGE)
    }

    fun testRejectsSamePackageMove() {
        val path = "example/McResolverSame.java"
        mirrorRealFile(path, """
            package example;
            public class McResolverSame {
                public void m() {}
            }
        """.trimIndent())
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeForClass(path, "McResolverSame")
        val res = resolver.resolve(project, path, range, "example")
        assertFailure(res, McpRefactoringErrorCode.UNSUPPORTED_TARGET)
    }

    fun testRejectsNonTopLevelOrNonMovableClass() {
        val path = "example/McResolverInner.java"
        mirrorRealFile(path, """
            package example;
            public class McResolverInner {
                public class Inner { }
            }
        """.trimIndent())
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val doc = document(path)
        val innerOff = doc.text.indexOf("class Inner") + "class".length + 1
        val innerRange = range(doc, innerOff, innerOff + 5)
        val res = resolver.resolve(project, path, innerRange, "example.api")
        assertFailure(res, McpRefactoringErrorCode.UNSUPPORTED_TARGET)
    }

    fun testRejectsInvalidTargetPackage() {
        val path = "example/McResolverPkg.java"
        mirrorRealFile(path, """
            package example;
            public class McResolverPkg {
                public void m() {}
            }
        """.trimIndent())
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeForClass(path, "McResolverPkg")
        assertFailure(resolver.resolve(project, path, range, ""), McpRefactoringErrorCode.INVALID_RANGE)
        assertFailure(resolver.resolve(project, path, range, "1bad"), McpRefactoringErrorCode.INVALID_RANGE)
    }

    fun testRejectsReadOnlyClassFile() {
        val path = "example/McResolverReadOnly.java"
        val vf = mirrorRealFile(path, """
            package example;
            public class McResolverReadOnly {
                public void m() {}
            }
        """.trimIndent())
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeForClass(path, "McResolverReadOnly")
        WriteAction.run<Throwable> { vf.isWritable = false }
        try {
            val res = resolver.resolve(project, path, range, "example.api")
            assertFailure(res, McpRefactoringErrorCode.READ_ONLY)
        } finally {
            WriteAction.run<Throwable> { vf.isWritable = true }
        }
    }

    fun testCapturesCrossFileReferencesAsAffected() {
        val path = "example/McResolverRefs.java"
        mirrorRealFile(path, """
            package example;
            public class McResolverRefs {
                public void m() {}
            }
        """.trimIndent())
        mirrorRealFile("example/McResolverRefsCaller.java", """
            package example;
            public class McResolverRefsCaller {
                void call() { new McResolverRefs().m(); }
            }
        """.trimIndent())
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeForClass(path, "McResolverRefs")
        val res = resolver.resolve(project, path, range, "example.api")
        assertTrue("expected Success but was $res", res is MoveClassSelectionResolution.Success)
        val prep = (res as MoveClassSelectionResolution.Success).preparation
        assertTrue(prep.affectedVirtualFiles.any { it.path.contains("McResolverRefs.java") })
        assertTrue(prep.affectedVirtualFiles.any { it.path.contains("McResolverRefsCaller.java") })
    }

    private fun rangeForClass(path: String, className: String): SourceRange {
        val doc = document(path)
        val off = doc.text.indexOf(className)
        assertTrue("class $className not found in $path", off >= 0)
        return range(doc, off, off + className.length)
    }

    private fun range(doc: Document, startOff: Int, endOff: Int): SourceRange {
        fun pos(off: Int): Pair<Int, Int> {
            val line = doc.getLineNumber(off)
            return (line + 1) to (off - doc.getLineStartOffset(line) + 1)
        }
        val (sl, sc) = pos(startOff)
        val (el, ec) = pos(endOff)
        return SourceRange(sl, sc, el, ec)
    }

    private fun document(path: String): Document {
        val vf = LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, path).toString())!!
        return FileDocumentManager.getInstance().getDocument(vf)!!
    }

    private fun mirrorRealFile(path: String, text: String): com.intellij.openapi.vfs.VirtualFile {
        val t = Path.of(project.basePath!!, path)
        Files.createDirectories(t.parent)
        if (!Files.exists(t)) Files.createFile(t)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(t.toString())!!
        WriteAction.run<RuntimeException> { VfsUtil.saveText(vf, text) }
        return vf
    }

    private fun assertFailure(res: MoveClassSelectionResolution, expected: McpRefactoringErrorCode) {
        assertTrue("expected Failure($expected) but was $res", res is MoveClassSelectionResolution.Failure)
        val f = res as MoveClassSelectionResolution.Failure
        assertEquals(expected, f.code)
        assertTrue(f.message.isNotBlank())
    }
}
