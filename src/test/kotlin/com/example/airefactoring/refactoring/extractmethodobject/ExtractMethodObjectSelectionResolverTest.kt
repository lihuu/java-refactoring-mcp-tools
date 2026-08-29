package com.example.airefactoring.refactoring.extractmethodobject

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

class ExtractMethodObjectSelectionResolverTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = ExtractMethodObjectSelectionResolver()

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testResolvesExactMethodNameAndContainingFile() {
        val path = "example/MoResolver.java"
        mirrorRealFile(path, """
            package example;
            public class MoResolver {
                public double price(int quantity, double unit) {
                    double subtotal = quantity * unit;
                    return subtotal * 0.9;
                }
            }
        """.trimIndent())
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeForMethod(path, "price")
        val res = resolver.resolve(project, path, range, "PriceObject", "invoke")
        assertTrue("expected Success but was $res", res is ExtractMethodObjectSelectionResolution.Success)
        val prep = (res as ExtractMethodObjectSelectionResolution.Success).preparation
        assertEquals("PriceObject", prep.methodObjectClassName)
        assertEquals("invoke", prep.methodObjectMethodName)
        assertTrue(prep.methodTextSnapshot.contains("price"))
        assertTrue(prep.affectedVirtualFiles.any { it.path.contains("MoResolver") })
    }

    fun testRejectsMethodRangeThatIncludesTypeOrParentheses() {
        val path = "example/MoResolverBadRange.java"
        mirrorRealFile(path, """
            package example;
            public class MoResolverBadRange {
                public void createInvoice(String customer, String currency) {
                    System.out.println(customer + currency);
                }
            }
        """.trimIndent())
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val doc = document(path)
        // range that includes return type "void"
        val voidOffset = doc.text.indexOf("void")
        val voidRange = range(doc, voidOffset, voidOffset + 4)
        val res1 = resolver.resolve(project, path, voidRange, "Req", "invoke")
        assertFailure(res1, McpRefactoringErrorCode.INVALID_RANGE)
        // range that includes parentheses
        val parenOffset = doc.text.indexOf("createInvoice(") + "createInvoice".length
        val parenRange = range(doc, doc.text.indexOf("createInvoice"), parenOffset + 1)
        val res2 = resolver.resolve(project, path, parenRange, "Req", "invoke")
        assertFailure(res2, McpRefactoringErrorCode.INVALID_RANGE)
    }

    fun testRejectsEmptyOrNonSelectableMethodBody() {
        val path = "example/MoResolverEmpty.java"
        mirrorRealFile(path, """
            package example;
            public class MoResolverEmpty {
                public void noop() { }
            }
        """.trimIndent())
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeForMethod(path, "noop")
        val res = resolver.resolve(project, path, range, "NoopObject", "invoke")
        assertFailure(res, McpRefactoringErrorCode.NO_EXTRACTABLE_ELEMENTS)
    }

    fun testRejectsInvalidObjectClassNameOrMethodName() {
        val path = "example/MoResolverNames.java"
        mirrorRealFile(path, """
            package example;
            public class MoResolverNames {
                public void work() { System.out.println("x"); }
            }
        """.trimIndent())
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeForMethod(path, "work")
        assertFailure(resolver.resolve(project, path, range, "", "invoke"), McpRefactoringErrorCode.INVALID_METHOD_NAME)
        assertFailure(resolver.resolve(project, path, range, "1Bad", "invoke"), McpRefactoringErrorCode.INVALID_METHOD_NAME)
        assertFailure(resolver.resolve(project, path, range, "Good", ""), McpRefactoringErrorCode.INVALID_METHOD_NAME)
        assertFailure(resolver.resolve(project, path, range, "Good", "1bad"), McpRefactoringErrorCode.INVALID_METHOD_NAME)
    }

    fun testRejectsReadOnlyMethodFile() {
        val path = "example/MoResolverReadOnly.java"
        val vf = mirrorRealFile(path, """
            package example;
            public class MoResolverReadOnly {
                public void work() { System.out.println("x"); }
            }
        """.trimIndent())
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeForMethod(path, "work")
        WriteAction.run<Throwable> { vf.isWritable = false }
        try {
            val res = resolver.resolve(project, path, range, "WorkObject", "invoke")
            assertFailure(res, McpRefactoringErrorCode.READ_ONLY)
        } finally {
            WriteAction.run<Throwable> { vf.isWritable = true }
        }
    }

    fun testCapturesOnlyTheMethodFileAsAffected() {
        val path = "example/MoResolverAffected.java"
        mirrorRealFile(path, """
            package example;
            public class MoResolverAffected {
                public double price(int quantity, double unit) {
                    double subtotal = quantity * unit;
                    return subtotal * 0.9;
                }
            }
        """.trimIndent())
        mirrorRealFile("example/MoResolverAffectedCaller.java", """
            package example;
            public class MoResolverAffectedCaller {
                void call() { System.out.println(new MoResolverAffected().price(5, 2.5)); }
            }
        """.trimIndent())
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeForMethod(path, "price")
        val res = resolver.resolve(project, path, range, "PriceObject", "invoke")
        assertTrue("expected Success but was $res", res is ExtractMethodObjectSelectionResolution.Success)
        val prep = (res as ExtractMethodObjectSelectionResolution.Success).preparation
        // Call sites are preserved by delegation, so only the method file is affected.
        assertEquals(1, prep.affectedVirtualFiles.size)
        assertTrue(prep.affectedVirtualFiles.single().path.contains("MoResolverAffected.java"))
    }

    private fun rangeForMethod(path: String, methodName: String): SourceRange {
        val doc = document(path)
        val off = doc.text.indexOf(methodName)
        assertTrue("method $methodName not found in $path", off >= 0)
        return range(doc, off, off + methodName.length)
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

    private fun assertFailure(res: ExtractMethodObjectSelectionResolution, expected: McpRefactoringErrorCode) {
        assertTrue("expected Failure($expected) but was $res", res is ExtractMethodObjectSelectionResolution.Failure)
        val f = res as ExtractMethodObjectSelectionResolution.Failure
        assertEquals(expected, f.code)
        assertTrue(f.message.isNotBlank())
    }
}
