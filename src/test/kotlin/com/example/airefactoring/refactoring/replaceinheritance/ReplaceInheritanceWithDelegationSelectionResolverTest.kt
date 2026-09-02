package com.example.airefactoring.refactoring.replaceinheritance

import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Test

class ReplaceInheritanceWithDelegationSelectionResolverTest : LightJavaCodeInsightFixtureTestCase() {

    private fun getResolver() = ReplaceInheritanceWithDelegationSelectionResolver(project)

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

    private fun mirrorRealFile(path: String, text: String): com.intellij.openapi.vfs.VirtualFile {
        val t = Path.of(project.basePath!!, path)
        Files.createDirectories(t.parent)
        if (!Files.exists(t)) Files.createFile(t)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(t.toString())!!
        WriteAction.run<RuntimeException> { VfsUtil.saveText(vf, text) }
        return vf
    }

    private fun document(path: String): Document {
        val vf = LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, path).toString())!!
        return FileDocumentManager.getInstance().getDocument(vf)!!
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

    private fun rangeForClass(path: String, className: String): SourceRange {
        val doc = document(path)
        val off = doc.text.indexOf(className)
        assertTrue("class $className not found in $path", off >= 0)
        return range(doc, off, off + className.length)
    }

    @Test
    fun testResolveValidInheritance() {
        val path = "example/Derived.java"
        addSamples(path, """
            package example;
            public class Derived extends Base {
                public void doWork() {}
            }
        """)
        addSamples("example/Base.java", """
            package example;
            public class Base {}
        """)

        val resolver = getResolver()
        val range = rangeForClass(path, "Derived")
        val prep = resolver.resolve(
            pathInProject = path,
            startLine = range.startLine, startColumn = range.startColumn,
            endLine = range.endLine, endColumn = range.endColumn,
            targetBaseClassFqn = "example.Base",
            fieldName = "baseDelegate",
            delegateOtherMembers = true,
            generateGetter = true
        )

        assertEquals("example.Derived", prep.sourceClassFqn)
        assertEquals("example.Base", prep.targetBaseClassFqn)
        assertEquals("baseDelegate", prep.fieldName)
    }

    @Test
    fun testResolveInvalidRange() {
        val path = "example/Derived.java"
        addSamples(path, """
            package example;
            public class Derived extends Base {}
        """)

        val resolver = getResolver()
        val doc = document(path)
        val pubOffset = doc.text.indexOf("public")
        val pubRange = range(doc, pubOffset, pubOffset + 6)

        assertThrows(ReplaceInheritanceWithDelegationSelectionResolver.SelectionException::class.java) {
            resolver.resolve(
                pathInProject = path,
                startLine = pubRange.startLine, startColumn = pubRange.startColumn,
                endLine = pubRange.endLine, endColumn = pubRange.endColumn,
                targetBaseClassFqn = "example.Base",
                fieldName = "baseDelegate",
                delegateOtherMembers = true,
                generateGetter = true
            )
        }
    }

    @Test
    fun testResolveNoInheritance() {
        val path = "example/Derived.java"
        addSamples(path, """
            package example;
            public class Derived {}
        """)
        addSamples("example/Base.java", """
            package example;
            public class Base {}
        """)

        val resolver = getResolver()
        val range = rangeForClass(path, "Derived")
        assertThrows(ReplaceInheritanceWithDelegationSelectionResolver.SelectionException::class.java) {
            resolver.resolve(
                pathInProject = path,
                startLine = range.startLine, startColumn = range.startColumn,
                endLine = range.endLine, endColumn = range.endColumn,
                targetBaseClassFqn = "example.Base",
                fieldName = "baseDelegate",
                delegateOtherMembers = true,
                generateGetter = true
            )
        }
    }

    @Test
    fun testResolveInnerClassRejected() {
        val path = "example/Outer.java"
        addSamples(path, """
            package example;
            public class Outer {
                public class Inner extends Base {}
            }
        """)
        addSamples("example/Base.java", """
            package example;
            public class Base {}
        """)

        val resolver = getResolver()
        val doc = document(path)
        val innerOff = doc.text.indexOf("class Inner") + "class ".length
        val innerRange = range(doc, innerOff, innerOff + "Inner".length)
        assertThrows(ReplaceInheritanceWithDelegationSelectionResolver.SelectionException::class.java) {
            resolver.resolve(
                pathInProject = path,
                startLine = innerRange.startLine, startColumn = innerRange.startColumn,
                endLine = innerRange.endLine, endColumn = innerRange.endColumn,
                targetBaseClassFqn = "example.Base",
                fieldName = "baseDelegate",
                delegateOtherMembers = true,
                generateGetter = true
            )
        }
    }

    @Test
    fun testResolveInvalidFieldName() {
        val path = "example/Derived.java"
        addSamples(path, """
            package example;
            public class Derived extends Base {}
        """)
        addSamples("example/Base.java", """
            package example;
            public class Base {}
        """)

        val resolver = getResolver()
        val range = rangeForClass(path, "Derived")
        assertThrows(ReplaceInheritanceWithDelegationSelectionResolver.SelectionException::class.java) {
            resolver.resolve(
                pathInProject = path,
                startLine = range.startLine, startColumn = range.startColumn,
                endLine = range.endLine, endColumn = range.endColumn,
                targetBaseClassFqn = "example.Base",
                fieldName = "123Invalid",
                delegateOtherMembers = true,
                generateGetter = true
            )
        }
    }
}
