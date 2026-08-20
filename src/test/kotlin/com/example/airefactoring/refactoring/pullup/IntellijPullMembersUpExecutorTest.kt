package com.example.airefactoring.refactoring.pullup

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

class IntellijPullMembersUpExecutorTest : LightJavaCodeInsightFixtureTestCase() {
    private val resolver = PullMembersUpSelectionResolver()
    private val executor = IntellijPullMembersUpExecutor()

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21
    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testPullsAndReportsFacts() {
        val (baseFile, subFile) = fixture()
        val prep = prepare(subFile, listOf("handle"), "a.Base")
        val result = runExecutor { execWithNoDialog { executor.pull(project, prep) } }
        assertEquals("a.Sub", result.sourceClassQualifiedName)
        assertEquals("a.Base", result.targetSuperclassFqn)
        assertEquals(listOf("handle"), result.memberNames)
        assertNotNull(result.affectedFiles)
        assertTrue(result.affectedFiles!!.any { it.contains("Sub.java") })
        assertTrue(result.affectedFiles!!.any { it.contains("Base.java") })
        val base = JavaPsiFacade.getInstance(project).findClass("a.Base", GlobalSearchScope.allScope(project))!!
        assertTrue(base.findMethodsByName("handle", false).isNotEmpty())
        assertTrue(base.findMethodsByName("handle", false).single().hasModifierProperty("abstract"))
    }

    fun testPersistsSourceAndTarget() {
        val (_, subFile) = fixture()
        val prep = prepare(subFile, listOf("handle"), "a.Base")
        val persister = RecordingNativeRefactoringDocumentPersister()
        val result = runExecutor { execWithNoDialog { IntellijPullMembersUpExecutor(persister).pull(project, prep) } }
        persister.assertPersistedExactly(*requireNotNull(result.affectedFiles).toTypedArray())
    }

    fun testOneUndoRestoresFiles() {
        val (baseFile, subFile) = fixture()
        val beforeBase = (PsiManager.getInstance(project).findFile(baseFile) as PsiJavaFile).text
        val beforeSub = (PsiManager.getInstance(project).findFile(subFile) as PsiJavaFile).text
        val prep = prepare(subFile, listOf("handle"), "a.Base")
        runExecutor { execWithNoDialog { executor.pull(project, prep) } }
        val um = UndoManager.getInstance(project)
        assertTrue(um.isUndoAvailable(null))
        val prev = TestDialogManager.setTestDialog(TestDialog.OK)
        try { um.undo(null) } finally { TestDialogManager.setTestDialog(prev) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(beforeBase, (PsiManager.getInstance(project).findFile(baseFile) as PsiJavaFile).text)
        assertEquals(beforeSub, (PsiManager.getInstance(project).findFile(subFile) as PsiJavaFile).text)
    }

    private fun fixture(): Pair<com.intellij.openapi.vfs.VirtualFile, com.intellij.openapi.vfs.VirtualFile> {
        val base = mirrorRealFile("a/Base.java", "package a; public class Base {}")
        val sub = mirrorRealFile("a/Sub.java", "package a; public class Sub extends Base { public void handle(String s) {} public static final int COUNT=1; }")
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        return Pair(base, sub)
    }

    private fun prepare(file: com.intellij.openapi.vfs.VirtualFile, memberNames: List<String>, targetFqn: String): PullMembersUpPreparation {
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val className = "Sub"
        val res = resolver.resolve(
            project, "a/Sub.java",
            lineOf("a/Sub.java", className), colOf("a/Sub.java", className), lineEndOf("a/Sub.java", className), colEndOf("a/Sub.java", className),
            memberNames.map { lineOf("a/Sub.java", it) }, memberNames.map { colOf("a/Sub.java", it) },
            memberNames.map { lineEndOf("a/Sub.java", it) }, memberNames.map { colEndOf("a/Sub.java", it) },
            targetFqn
        )
        assertTrue("expected success but was $res", res is PullMembersUpSelectionResolution.Success)
        return (res as PullMembersUpSelectionResolution.Success).preparation
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
