package com.example.airefactoring.refactoring.useinterface

import com.example.airefactoring.refactoring.RecordingNativeRefactoringDocumentPersister
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.dispatchAllEventsInIdeEventQueue
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

class IntellijUseInterfaceWherePossibleExecutorTest : LightJavaCodeInsightFixtureTestCase() {
    private val resolver = UseInterfaceWherePossibleSelectionResolver()
    private val executor = IntellijUseInterfaceWherePossibleExecutor()

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21
    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testRewritesTypesAndReportsFacts() {
        val (samplesFile, holderFile) = fixture()
        val beforeHolder = (PsiManager.getInstance(project).findFile(holderFile) as PsiJavaFile).text
        val prep = prepare()
        val result = runExecutor { execWithNoDialog { executor.useInterface(project, prep) } }
        assertEquals("a.Impl", result.sourceClassQualifiedName)
        assertEquals("a.Widenable", result.targetInterfaceFqn)
        assertTrue(result.nativeUsageCount >= 4)
        assertNotNull(result.affectedFiles)
        assertTrue(result.affectedFiles!!.any { it.contains("Samples.java") })
        assertTrue(result.affectedFiles!!.any { it.contains("Holder.java") })
        assertEquals(null, result.renamedVariables)
        val afterHolder = PsiManager.getInstance(project).findFile(holderFile) as PsiJavaFile
        assertTrue(afterHolder.text.contains("Widenable field = new Impl();"))
        assertTrue(afterHolder.text.contains("String use(Widenable p)"))
        assertTrue(afterHolder.text.contains("Widenable local = p;"))
        assertTrue(afterHolder.text.contains("local instanceof Impl"))
        assertTrue(afterHolder.text != beforeHolder)
    }

    fun testPersistsExactlyAffectedFiles() {
        fixture()
        val persister = RecordingNativeRefactoringDocumentPersister()
        val prep = prepare()
        val result = runExecutor { execWithNoDialog { IntellijUseInterfaceWherePossibleExecutor(persister).useInterface(project, prep) } }
        persister.assertPersistedExactly(*requireNotNull(result.affectedFiles).toTypedArray())
    }

    fun testOneUndoRestoresFiles() {
        val (samplesFile, holderFile) = fixture()
        val beforeSamples = (PsiManager.getInstance(project).findFile(samplesFile) as PsiJavaFile).text
        val beforeHolder = (PsiManager.getInstance(project).findFile(holderFile) as PsiJavaFile).text
        val prep = prepare()
        runExecutor { execWithNoDialog { executor.useInterface(project, prep) } }
        val um = UndoManager.getInstance(project)
        assertTrue(um.isUndoAvailable(null))
        val prev = TestDialogManager.setTestDialog(TestDialog.OK)
        try { um.undo(null) } finally { TestDialogManager.setTestDialog(prev) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(beforeSamples, (PsiManager.getInstance(project).findFile(samplesFile) as PsiJavaFile).text)
        assertEquals(beforeHolder, (PsiManager.getInstance(project).findFile(holderFile) as PsiJavaFile).text)
    }

    fun testStaleSourcePreparationFails() {
        val (samplesFile, _) = fixture()
        val prep = prepare()
        val doc = FileDocumentManager.getInstance().getDocument(samplesFile)!!
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            val start = doc.text.indexOf("class Impl ") + "class ".length
            doc.replaceString(start, start + "Impl".length, "ImplX")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val error = runCatching {
            runExecutor { execWithNoDialog { executor.useInterface(project, prep) } }
        }.exceptionOrNull()
        assertTrue("expected preparation failure but was $error", error is UseInterfaceWherePossiblePreparationException)
    }

    private fun fixture(): Pair<VirtualFile, VirtualFile> {
        val samples = mirrorRealFile(
            "a/Samples.java",
            """
                package a;
                public interface Widenable { String label(); }
                public class Impl implements Widenable {
                    @Override public String label() { return "x"; }
                    public void onlyOnImpl() {}
                }
            """.trimIndent(),
        )
        val holder = mirrorRealFile(
            "a/Holder.java",
            """
                package a;
                public class Holder {
                    Impl field = new Impl();
                    String use(Impl p) {
                        Impl local = p;
                        boolean isImpl = local instanceof Impl;
                        return local.label();
                    }
                }
            """.trimIndent(),
        )
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        return Pair(samples, holder)
    }

    private fun prepare(): UseInterfaceWherePossiblePreparation {
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val res = resolver.resolve(
            project, "a/Samples.java",
            lineOf("a/Samples.java", "Impl"), colOf("a/Samples.java", "Impl"),
            lineEndOf("a/Samples.java", "Impl"), colEndOf("a/Samples.java", "Impl"),
            "a.Widenable",
        )
        assertTrue("expected success but was $res", res is UseInterfaceWherePossibleSelectionResolution.Success)
        return (res as UseInterfaceWherePossibleSelectionResolution.Success).preparation
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

    private fun mirrorRealFile(path: String, text: String): VirtualFile {
        val t = Path.of(project.basePath!!, path); Files.createDirectories(t.parent); if (!Files.exists(t)) Files.createFile(t)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(t.toString())!!
        WriteAction.run<RuntimeException> { com.intellij.openapi.vfs.VfsUtil.saveText(vf, text) }
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
