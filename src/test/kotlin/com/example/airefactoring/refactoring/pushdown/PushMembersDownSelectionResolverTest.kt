package com.example.airefactoring.refactoring.pushdown

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path

class PushMembersDownSelectionResolverTest : LightJavaCodeInsightFixtureTestCase() {
    private val resolver = PushMembersDownSelectionResolver()
    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21
    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testResolvesSingleTarget() {
        fixture()
        val result = resolver.resolve(
            project, "a/SuperBase.java",
            lineOf("a/SuperBase.java","SuperBase"), colOf("a/SuperBase.java","SuperBase"),
            lineEndOf("a/SuperBase.java","SuperBase"), colEndOf("a/SuperBase.java","SuperBase"),
            listOf(lineOf("a/SuperBase.java","handle")), listOf(colOf("a/SuperBase.java","handle")),
            listOf(lineEndOf("a/SuperBase.java","handle")), listOf(colEndOf("a/SuperBase.java","handle")),
            listOf("a.SubA")
        )
        assertTrue(result is PushMembersDownSelectionResolution.Success)
    }

    fun testResolvesTwoTargets() {
        fixture()
        val result = resolver.resolve(
            project, "a/SuperBase.java",
            lineOf("a/SuperBase.java","SuperBase"), colOf("a/SuperBase.java","SuperBase"),
            lineEndOf("a/SuperBase.java","SuperBase"), colEndOf("a/SuperBase.java","SuperBase"),
            listOf(lineOf("a/SuperBase.java","handle")), listOf(colOf("a/SuperBase.java","handle")),
            listOf(lineEndOf("a/SuperBase.java","handle")), listOf(colEndOf("a/SuperBase.java","handle")),
            listOf("a.SubA","a.SubB")
        )
        assertTrue(result is PushMembersDownSelectionResolution.Success)
    }

    fun testRejectsEmptyMembers() {
        fixture()
        val result = resolver.resolve(project, "a/SuperBase.java",
            lineOf("a/SuperBase.java","SuperBase"), colOf("a/SuperBase.java","SuperBase"),
            lineEndOf("a/SuperBase.java","SuperBase"), colEndOf("a/SuperBase.java","SuperBase"),
            emptyList(), emptyList(), emptyList(), emptyList(),
            listOf("a.SubA"))
        assertTrue(result is PushMembersDownSelectionResolution.Failure)
    }

    fun testRejectsNonDirectChild() {
        fixture()
        // SubAChild is indirect via SubA
        mirrorFile("a/SubAChild.java", "package a; public class SubAChild extends SubA {}")
        val result = resolver.resolve(project, "a/SuperBase.java",
            lineOf("a/SuperBase.java","SuperBase"), colOf("a/SuperBase.java","SuperBase"),
            lineEndOf("a/SuperBase.java","SuperBase"), colEndOf("a/SuperBase.java","SuperBase"),
            listOf(lineOf("a/SuperBase.java","handle")), listOf(colOf("a/SuperBase.java","handle")),
            listOf(lineEndOf("a/SuperBase.java","handle")), listOf(colEndOf("a/SuperBase.java","handle")),
            listOf("a.SubAChild"))
        assertTrue(result is PushMembersDownSelectionResolution.Failure)
    }

    fun testRejectsDuplicateTarget() {
        fixture()
        val result = resolver.resolve(project, "a/SuperBase.java",
            lineOf("a/SuperBase.java","SuperBase"), colOf("a/SuperBase.java","SuperBase"),
            lineEndOf("a/SuperBase.java","SuperBase"), colEndOf("a/SuperBase.java","SuperBase"),
            listOf(lineOf("a/SuperBase.java","handle")), listOf(colOf("a/SuperBase.java","handle")),
            listOf(lineEndOf("a/SuperBase.java","handle")), listOf(colEndOf("a/SuperBase.java","handle")),
            listOf("a.SubA","a.SubA"))
        assertTrue(result is PushMembersDownSelectionResolution.Failure)
    }

    private fun fixture() {
        mirrorFile("a/SuperBase.java", "package a; public class SuperBase { public void handle(String s) {} public static final int COUNT=1; }")
        mirrorFile("a/SubA.java", "package a; public class SubA extends SuperBase {}")
        mirrorFile("a/SubB.java", "package a; public class SubB extends SuperBase {}")
    }
    private fun lineOf(p:String,n:String)=rangeOf(p,n).first
    private fun colOf(p:String,n:String)=rangeOf(p,n).second
    private fun lineEndOf(p:String,n:String)=rangeEndOf(p,n).first
    private fun colEndOf(p:String,n:String)=rangeEndOf(p,n).second
    private fun rangeOf(p:String,n:String):Pair<Int,Int>{
        val vf=LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!,p).toString())!!
        val doc=FileDocumentManager.getInstance().getDocument(vf)!!
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        val off=doc.text.indexOf(n)
        assertTrue("'$n' missing", off>=0)
        val line=doc.getLineNumber(off)
        return (line+1) to (off-doc.getLineStartOffset(line)+1)
    }
    private fun rangeEndOf(p:String,n:String):Pair<Int,Int>{
        val vf=LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!,p).toString())!!
        val doc=FileDocumentManager.getInstance().getDocument(vf)!!
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        val off=doc.text.indexOf(n)
        val end=off+n.length
        val line=doc.getLineNumber(end-1)
        return (line+1) to (end-doc.getLineStartOffset(line)+1)
    }
    private fun mirrorFile(p:String,t:String){
        val target=Path.of(project.basePath!!,p)
        Files.createDirectories(target.parent); try { Files.writeString(target,t) } catch(_:Exception){}
        try { myFixture.addFileToProject(p, t) } catch(_:Exception){}
        LocalFileSystem.getInstance().refreshAndFindFileByPath(target.toString())
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
    }
}
