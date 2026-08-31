package com.example.airefactoring.refactoring.extractdelegate

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path

class ExtractDelegateSelectionResolverTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = ExtractDelegateSelectionResolver()

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    private fun addSamples(path: String, text: String) {
        mirrorRealFile(path, text)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private fun addOrderService(): String {
        val path = "example/EdResolver.java"
        addSamples(
            path,
            """
                package example;
                public class EdResolver {
                    public double unitPrice = 2.5;
                    private int discount = 1;
                    public double price(int quantity) { return quantity * unitPrice; }
                    public abstract double abstractRate();
                    public void overloaded(int q) { }
                    public void overloaded(double q) { }
                }
            """.trimIndent(),
        )
        return path
    }

    fun testResolvesClassAndMembersAndAffectedFiles() {
        val path = "example/EdResolverRef.java"
        addSamples(
            path,
            """
                package example;
                public class EdResolverRef {
                    public double unitPrice = 2.5;
                    private int discount = 1;
                    public double price(int q) { return q * unitPrice; }
                }
            """.trimIndent(),
        )
        mirrorRealFile("example/EdResolverRefCaller.java", """
            package example;
            public class EdResolverRefCaller {
                void call() { new EdResolverRef().price(2); }
            }
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeForClass(path, "EdResolverRef")
        val res = resolver.resolve(
            project, path, range,
            extractedFields = listOf("unitPrice"),
            extractedMethods = listOf("price"),
            newClassName = "EdResolverInternal",
            extractInnerClass = false,
        )
        assertTrue("expected Success but was $res", res is ExtractDelegateSelectionResolution.Success)
        val prep = (res as ExtractDelegateSelectionResolution.Success).preparation
        assertEquals("example.EdResolverRef", prep.sourceClassFqn)
        assertEquals("EdResolverInternal", prep.newClassName)
        assertEquals(listOf("unitPrice"), prep.extractedFields)
        assertEquals(listOf("price"), prep.extractedMethods)
        assertFalse(prep.extractInnerClass)
        assertTrue(prep.affectedVirtualFiles.any { it.path.contains("EdResolverRef.java") })
        assertTrue(prep.affectedVirtualFiles.any { it.path.contains("EdResolverRefCaller.java") })
    }

    fun testRejectsClassRangeThatIsNotExactNameIdentifier() {
        val path = "example/EdResolverBadRange.java"
        addSamples(
            path,
            """
                package example;
                public class EdResolverBadRange {
                    public void m() {}
                }
            """.trimIndent(),
        )
        val doc = document(path)
        val pubOffset = doc.text.indexOf("public")
        val pubRange = range(doc, pubOffset, pubOffset + 6)
        val res = resolver.resolve(
            project, path, pubRange,
            extractedFields = emptyList(), extractedMethods = listOf("m"),
            newClassName = "EdDelegate", extractInnerClass = true,
        )
        assertFailure(res, McpRefactoringErrorCode.INVALID_RANGE)
    }

    fun testRejectsNonTopLevelOrNonConcreteClass() {
        val path = "example/EdResolverInner.java"
        addSamples(
            path,
            """
                package example;
                public class EdResolverInner {
                    public class Inner { public void m() {} }
                    public interface Iface { void m(); }
                }
            """.trimIndent(),
        )
        val doc = document(path)
        val innerOff = doc.text.indexOf("class Inner") + "class ".length
        val innerRange = range(doc, innerOff, innerOff + "Inner".length)
        assertFailure(
            resolver.resolve(
                project, path, innerRange,
                extractedFields = emptyList(), extractedMethods = listOf("m"),
                newClassName = "EdDelegate", extractInnerClass = true,
            ),
            McpRefactoringErrorCode.UNSUPPORTED_TARGET,
        )
        val ifaceOff = doc.text.indexOf("interface Iface") + "interface ".length
        val ifaceRange = range(doc, ifaceOff, ifaceOff + "Iface".length)
        assertFailure(
            resolver.resolve(
                project, path, ifaceRange,
                extractedFields = emptyList(), extractedMethods = listOf("m"),
                newClassName = "EdDelegate", extractInnerClass = true,
            ),
            McpRefactoringErrorCode.UNSUPPORTED_TARGET,
        )
    }

    fun testRejectsAmbiguousMissingConstructorOrAbstractMemberNames() {
        val path = "example/EdResolverMembers.java"
        addSamples(
            path,
            """
                package example;
                public class EdResolverMembers {
                    public double unitPrice = 2.5;
                    public double price(int q) { return q * unitPrice; }
                    public double price(long q) { return q * unitPrice; }
                    public abstract double abstractRate();
                    public EdResolverMembers() {}
                    public void m() {}
                }
            """.trimIndent(),
        )
        val range = rangeForClass(path, "EdResolverMembers")
        resolver.resolve(
            project, path, range,
            extractedFields = emptyList(), extractedMethods = listOf("m"),
            newClassName = "EdDelegate", extractInnerClass = true,
        ) // sanity baseline below asserts only failures
        assertFailure(
            resolver.resolve(
                project, path, range,
                extractedFields = emptyList(), extractedMethods = listOf("nonexistent"),
                newClassName = "EdDelegate", extractInnerClass = true,
            ),
            McpRefactoringErrorCode.INVALID_RANGE,
        )
        assertFailure(
            resolver.resolve(
                project, path, range,
                extractedFields = emptyList(), extractedMethods = listOf("price"),
                newClassName = "EdDelegate", extractInnerClass = true,
            ),
            McpRefactoringErrorCode.INVALID_RANGE,
        )
        assertFailure(
            resolver.resolve(
                project, path, range,
                extractedFields = emptyList(), extractedMethods = listOf("abstractRate"),
                newClassName = "EdDelegate", extractInnerClass = true,
            ),
            McpRefactoringErrorCode.INVALID_RANGE,
        )
        assertFailure(
            resolver.resolve(
                project, path, range,
                extractedFields = emptyList(), extractedMethods = listOf("EdResolverMembers"),
                newClassName = "EdDelegate", extractInnerClass = true,
            ),
            McpRefactoringErrorCode.INVALID_RANGE,
        )
        assertFailure(
            resolver.resolve(
                project, path, range,
                extractedFields = listOf("unitPrice", "unitPrice"), extractedMethods = emptyList(),
                newClassName = "EdDelegate", extractInnerClass = true,
            ),
            McpRefactoringErrorCode.INVALID_RANGE,
        )
    }

    fun testRejectsEmptyMemberSelection() {
        val path = "example/EdResolverEmpty.java"
        addSamples(
            path,
            """
                package example;
                public class EdResolverEmpty {
                    public void m() {}
                }
            """.trimIndent(),
        )
        val range = rangeForClass(path, "EdResolverEmpty")
        assertFailure(
            resolver.resolve(
                project, path, range,
                extractedFields = emptyList(), extractedMethods = emptyList(),
                newClassName = "EdDelegate", extractInnerClass = true,
            ),
            McpRefactoringErrorCode.INVALID_RANGE,
        )
    }

    fun testRejectsInvalidOrCollidingNewClassName() {
        val path = "example/EdResolverName.java"
        addSamples(
            path,
            """
                package example;
                public class EdResolverName {
                    public double unitPrice = 2.5;
                    public void m() {}
                }
            """.trimIndent(),
        )
        mirrorRealFile("example/EdDelegate.java", """
            package example;
            public class EdDelegate {
                public void existing() {}
            }
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeForClass(path, "EdResolverName")
        assertFailure(
            resolver.resolve(
                project, path, range,
                extractedFields = listOf("unitPrice"), extractedMethods = emptyList(),
                newClassName = "", extractInnerClass = true,
            ),
            McpRefactoringErrorCode.INVALID_RANGE,
        )
        assertFailure(
            resolver.resolve(
                project, path, range,
                extractedFields = listOf("unitPrice"), extractedMethods = emptyList(),
                newClassName = "1bad", extractInnerClass = true,
            ),
            McpRefactoringErrorCode.INVALID_RANGE,
        )
        assertFailure(
            resolver.resolve(
                project, path, range,
                extractedFields = listOf("unitPrice"), extractedMethods = emptyList(),
                newClassName = "EdDelegate", extractInnerClass = true,
            ),
            McpRefactoringErrorCode.REFACTORING_CONFLICT,
        )
        // Colliding with the source class itself is also a conflict.
        assertFailure(
            resolver.resolve(
                project, path, range,
                extractedFields = listOf("unitPrice"), extractedMethods = emptyList(),
                newClassName = "EdResolverName", extractInnerClass = true,
            ),
            McpRefactoringErrorCode.REFACTORING_CONFLICT,
        )
        // A nested class of the source must not count as a package-level collision.
        mirrorRealFile("example/EdResolverNested.java", """
            package example;
            public class EdResolverNested {
                public double unitPrice = 2.5;
                public void m() {}
                public class EdOther {}
            }
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range2 = rangeForClass("example/EdResolverNested.java", "EdResolverNested")
        val ok = resolver.resolve(
            project, "example/EdResolverNested.java", range2,
            extractedFields = listOf("unitPrice"), extractedMethods = emptyList(),
            newClassName = "EdOther", extractInnerClass = false,
        )
        assertTrue("expected Success but was $ok", ok is ExtractDelegateSelectionResolution.Success)
    }

    fun testRejectsReadOnlyClassFile() {
        val path = "example/EdResolverReadOnly.java"
        addSamples(
            path,
            """
                package example;
                public class EdResolverReadOnly {
                    public double unitPrice = 2.5;
                    public void m() {}
                }
            """.trimIndent(),
        )
        val vf = LocalFileSystem.getInstance()
            .findFileByPath(Path.of(project.basePath!!, path).toString())!!
        val range = rangeForClass(path, "EdResolverReadOnly")
        WriteAction.run<Throwable> { vf.isWritable = false }
        try {
            assertFailure(
                resolver.resolve(
                    project, path, range,
                    extractedFields = listOf("unitPrice"), extractedMethods = emptyList(),
                    newClassName = "EdDelegate", extractInnerClass = true,
                ),
                McpRefactoringErrorCode.READ_ONLY,
            )
        } finally {
            WriteAction.run<Throwable> { vf.isWritable = true }
        }
    }

    fun testCapturesCrossFileReferencesAsAffected() {
        val path = "example/EdResolverRefs.java"
        addSamples(
            path,
            """
                package example;
                public class EdResolverRefs {
                    public double unitPrice = 2.5;
                    public double price(int q) { return q * unitPrice; }
                }
            """.trimIndent(),
        )
        mirrorRealFile("example/EdResolverRefsCaller.java", """
            package example;
            public class EdResolverRefsCaller {
                void call() { new EdResolverRefs().price(2); }
            }
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeForClass(path, "EdResolverRefs")
        val res = resolver.resolve(
            project, path, range,
            extractedFields = listOf("unitPrice"), extractedMethods = listOf("price"),
            newClassName = "EdRefsDelegate", extractInnerClass = false,
        )
        assertTrue("expected Success but was $res", res is ExtractDelegateSelectionResolution.Success)
        val prep = (res as ExtractDelegateSelectionResolution.Success).preparation
        assertTrue(prep.affectedVirtualFiles.any { it.path.contains("EdResolverRefs.java") })
        assertTrue(prep.affectedVirtualFiles.any { it.path.contains("EdResolverRefsCaller.java") })
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

    private fun assertFailure(res: ExtractDelegateSelectionResolution, expected: McpRefactoringErrorCode) {
        assertTrue("expected Failure($expected) but was $res", res is ExtractDelegateSelectionResolution.Failure)
        val f = res as ExtractDelegateSelectionResolution.Failure
        assertEquals(expected, f.code)
        assertTrue(f.message.isNotBlank())
    }
}