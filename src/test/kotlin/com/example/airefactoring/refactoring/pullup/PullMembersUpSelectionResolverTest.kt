package com.example.airefactoring.refactoring.pullup

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path

class PullMembersUpSelectionResolverTest : LightJavaCodeInsightFixtureTestCase() {
    private val resolver = PullMembersUpSelectionResolver()
    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21
    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testResolvesSinglePublicMethod() {
        fixture()
val result = resolver.resolve(
            project, "a/Sub.java",
            lineOf("a/Sub.java","Sub"), colOf("a/Sub.java","Sub"),
            lineEndOf("a/Sub.java","Sub"), colEndOf("a/Sub.java","Sub"),
            listOf(lineOf("a/Sub.java","handle")), listOf(colOf("a/Sub.java","handle")),
            listOf(lineEndOf("a/Sub.java","handle")), listOf(colEndOf("a/Sub.java","handle")),
            "a.Base"
        )
assertTrue(result is PullMembersUpSelectionResolution.Success)
    }

    fun testRejectsEmptyMembers() {
        fixture()
        val result = resolver.resolve(project, "a/Sub.java",
            lineOf("a/Sub.java","Sub"), colOf("a/Sub.java","Sub"),
            lineEndOf("a/Sub.java","Sub"), colEndOf("a/Sub.java","Sub"),
            emptyList(), emptyList(), emptyList(), emptyList(),
            "a.Base")
        assertTrue(result is PullMembersUpSelectionResolution.Failure)
    }

    fun testRejectsNonPublic() {
        fixture()
        val result = resolver.resolve(project, "a/Sub.java",
            lineOf("a/Sub.java","Sub"), colOf("a/Sub.java","Sub"),
            lineEndOf("a/Sub.java","Sub"), colEndOf("a/Sub.java","Sub"),
            listOf(lineOf("a/Sub.java","help")), listOf(colOf("a/Sub.java","help")),
            listOf(lineEndOf("a/Sub.java","help")), listOf(colEndOf("a/Sub.java","help")),
            "a.Base")
        assertTrue(result is PullMembersUpSelectionResolution.Failure)
    }

    fun testRejectsIndirectSuper() {
        fixture()
        // Base is direct super of Sub, but try java.lang.Object as indirect
        val result = resolver.resolve(project, "a/Sub.java",
            lineOf("a/Sub.java","Sub"), colOf("a/Sub.java","Sub"),
            lineEndOf("a/Sub.java","Sub"), colEndOf("a/Sub.java","Sub"),
            listOf(lineOf("a/Sub.java","handle")), listOf(colOf("a/Sub.java","handle")),
            listOf(lineEndOf("a/Sub.java","handle")), listOf(colEndOf("a/Sub.java","handle")),
            "java.lang.Object")
assertTrue(result is PullMembersUpSelectionResolution.Failure)
    }

    fun testRejectsInvalidFqn() {
        fixture()
        val result = resolver.resolve(project, "a/Sub.java",
            lineOf("a/Sub.java","Sub"), colOf("a/Sub.java","Sub"),
            lineEndOf("a/Sub.java","Sub"), colEndOf("a/Sub.java","Sub"),
            listOf(lineOf("a/Sub.java","handle")), listOf(colOf("a/Sub.java","handle")),
            listOf(lineEndOf("a/Sub.java","handle")), listOf(colEndOf("a/Sub.java","handle")),
            "bad..pkg")
        assertTrue(result is PullMembersUpSelectionResolution.Failure)
    }

    private fun fixture() {
        mirrorFile("a/Base.java", "package a; public class Base {}")
        mirrorFile("a/Sub.java", "package a; public class Sub extends Base { public void handle(String s) {} public static final int COUNT=1; private void help(){} }")
    }
    private fun lineOf(path:String, needle:String)=rangeOf(path,needle).first
    private fun colOf(path:String, needle:String)=rangeOf(path,needle).second
    private fun lineEndOf(path:String, needle:String)=rangeEndOf(path,needle).first
    private fun colEndOf(path:String, needle:String)=rangeEndOf(path,needle).second
    private fun rangeOf(path:String, needle:String):Pair<Int,Int>{
        val vf=LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, path).toString())!!
        val doc=FileDocumentManager.getInstance().getDocument(vf)!!
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        val off=doc.text.indexOf(needle)
        assertTrue("'$needle' missing", off>=0)
        val line=doc.getLineNumber(off)
        return (line+1) to (off-doc.getLineStartOffset(line)+1)
    }
    private fun rangeEndOf(path:String, needle:String):Pair<Int,Int>{
        val vf=LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, path).toString())!!
        val doc=FileDocumentManager.getInstance().getDocument(vf)!!
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        val off=doc.text.indexOf(needle)
        val end=off+needle.length
        val line=doc.getLineNumber(end-1)
        return (line+1) to (end-doc.getLineStartOffset(line)+1)
    }
    private fun mirrorFile(path:String, text:String){
        val t=Path.of(project.basePath!!, path)
        Files.createDirectories(t.parent); try { Files.writeString(t,text) } catch(_:Exception){}
        try { myFixture.addFileToProject(path, text) } catch(_:Exception){}
        LocalFileSystem.getInstance().refreshAndFindFileByPath(t.toString())
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
    }
}
