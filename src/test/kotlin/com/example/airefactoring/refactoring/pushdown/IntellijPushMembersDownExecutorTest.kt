package com.example.airefactoring.refactoring.pushdown

import com.example.airefactoring.refactoring.RecordingNativeRefactoringDocumentPersister
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.dispatchAllEventsInIdeEventQueue
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IntellijPushMembersDownExecutorTest : LightJavaCodeInsightFixtureTestCase() {
    private val resolver = PushMembersDownSelectionResolver()
    private val executor = IntellijPushMembersDownExecutor()

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21
    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testPushesAndReportsFacts() {
        val superFile = fixture()
        val prep = prepare(superFile, listOf("handle"), listOf("a.SubA","a.SubB"))
        val result = runExecutor { execWithNoDialog { executor.push(project, prep) } }
        assertEquals("a.SuperBase", result.sourceClassQualifiedName)
        assertEquals(listOf("a.SubA","a.SubB"), result.targetSubclassFqns)
        assertEquals(listOf("handle"), result.memberNames)
        assertNotNull(result.affectedFiles)
        assertTrue(result.affectedFiles!!.any { it.contains("SuperBase.java") })
        assertTrue(result.affectedFiles!!.any { it.contains("SubA.java") })
        val superCls = JavaPsiFacade.getInstance(project).findClass("a.SuperBase", GlobalSearchScope.allScope(project))!!
        assertTrue(superCls.findMethodsByName("handle", false).single().hasModifierProperty("abstract"))
        val subA = JavaPsiFacade.getInstance(project).findClass("a.SubA", GlobalSearchScope.allScope(project))!!
        assertTrue(subA.findMethodsByName("handle", false).isNotEmpty())
    }

    fun testPersistsSourceAndTargets() {
        val superFile = fixture()
        val prep = prepare(superFile, listOf("handle"), listOf("a.SubA","a.SubB"))
        val persister = RecordingNativeRefactoringDocumentPersister()
        val result = runExecutor { execWithNoDialog { IntellijPushMembersDownExecutor(persister).push(project, prep) } }
        persister.assertPersistedExactly(*requireNotNull(result.affectedFiles).toTypedArray())
    }

    fun testOneUndoRestoresFiles() {
        val superFile = fixture()
        val beforeSuper = (PsiManager.getInstance(project).findFile(superFile) as PsiJavaFile).text
        val subAFile = LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, "a/SubA.java").toString())!!
        val subBFile = LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, "a/SubB.java").toString())!!
        val beforeA = (PsiManager.getInstance(project).findFile(subAFile) as PsiJavaFile).text
        val beforeB = (PsiManager.getInstance(project).findFile(subBFile) as PsiJavaFile).text
        val prep = prepare(superFile, listOf("handle"), listOf("a.SubA","a.SubB"))
        runExecutor { execWithNoDialog { executor.push(project, prep) } }
        val um = UndoManager.getInstance(project)
        assertTrue(um.isUndoAvailable(null))
        val prev = TestDialogManager.setTestDialog(TestDialog.OK)
        try { um.undo(null) } finally { TestDialogManager.setTestDialog(prev) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(beforeSuper, (PsiManager.getInstance(project).findFile(superFile) as PsiJavaFile).text)
        assertEquals(beforeA, (PsiManager.getInstance(project).findFile(subAFile) as PsiJavaFile).text)
        assertEquals(beforeB, (PsiManager.getInstance(project).findFile(subBFile) as PsiJavaFile).text)
    }

    private fun fixture(): com.intellij.openapi.vfs.VirtualFile {
        mirrorRealFile("a/SuperBase.java", "package a; public class SuperBase { public void handle(String s) {} public static final int COUNT=1; }")
        mirrorRealFile("a/SubA.java", "package a; public class SubA extends SuperBase {}")
        mirrorRealFile("a/SubB.java", "package a; public class SubB extends SuperBase {}")
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        return LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, "a/SuperBase.java").toString())!!
    }

    private fun prepare(file: com.intellij.openapi.vfs.VirtualFile, memberNames: List<String>, targetFqns: List<String>): PushMembersDownPreparation {
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val className = "SuperBase"
        val res = resolver.resolve(
            project, "a/SuperBase.java",
            lineOf("a/SuperBase.java", className), colOf("a/SuperBase.java", className), lineEndOf("a/SuperBase.java", className), colEndOf("a/SuperBase.java", className),
            memberNames.map { lineOf("a/SuperBase.java", it) }, memberNames.map { colOf("a/SuperBase.java", it) },
            memberNames.map { lineEndOf("a/SuperBase.java", it) }, memberNames.map { colEndOf("a/SuperBase.java", it) },
            targetFqns
        )
        assertTrue("expected success but was $res", res is PushMembersDownSelectionResolution.Success)
        return (res as PushMembersDownSelectionResolution.Success).preparation
    }

    private fun lineOf(path: String, needle: String): Int = rangeOf(path, needle).first
    private fun colOf(path: String, needle: String): Int = rangeOf(path, needle).second
    private fun lineEndOf(path: String, needle: String): Int = rangeEndOf(path, needle).first
    private fun colEndOf(path: String, needle: String): Int = rangeEndOf(path, needle).second
    private fun rangeOf(path: String, needle: String): Pair<Int, Int> {
        val vf = LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, path).toString())!!
        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        val off = doc.text.indexOf(needle)
        assertTrue("'$needle' missing from $path: ${doc.text}", off >= 0)
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
    private fun mirrorRealFile(path: String, text: String): com.intellij.openapi.vfs.VirtualFile {
        val t = Path.of(project.basePath!!, path); Files.createDirectories(t.parent); if (!Files.exists(t)) Files.createFile(t)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(t.toString())!!
        com.intellij.openapi.application.WriteAction.run<RuntimeException> { com.intellij.openapi.vfs.VfsUtil.saveText(vf, text) }
        return vf
    }
    private suspend fun <T> execWithNoDialog(block: suspend () -> T): T {
        val d = object : TestDialog { override fun show(message: String): Int = throw AssertionError("must not show dialog: $message") }
        val prev = TestDialogManager.setTestDialog(d)
        try { return block() } finally { TestDialogManager.setTestDialog(prev) }
    }
    private fun <T> runExecutor(block: suspend () -> T): T {
        val pool = Executors.newSingleThreadExecutor()
        return try {
            val f = pool.submit<T> { runBlocking { block() } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (System.nanoTime() < deadline && !f.isDone) { dispatchAllEventsInIdeEventQueue(); Thread.sleep(1) }
            try { f.get(1, TimeUnit.SECONDS) } catch (e: ExecutionException) { throw e.cause ?: e }
        } finally { pool.shutdownNow() }
    }
}
