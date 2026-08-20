package com.example.airefactoring.refactoring.extractsuperclass

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

class IntellijExtractSuperclassExecutorTest : LightJavaCodeInsightFixtureTestCase() {
    private val resolver = ExtractSuperclassSelectionResolver()
    private val executor = IntellijExtractSuperclassExecutor()

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21
    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testExtractsAndReportsFacts() {
        val (sourceFile, _) = fixture()
        val prep = prepare(sourceFile, listOf("doIt"), "ServiceSuper", "example.api")
        val result = runExecutor { execWithNoDialog { executor.extract(project, prep) } }
        assertEquals("example.Service", result.sourceClassQualifiedName)
        assertEquals("ServiceSuper", result.superclassName)
        assertEquals("example.api.ServiceSuper", result.qualifiedSuperclassName)
        assertEquals(listOf("doIt"), result.memberNames)
        assertEquals("example.api", result.targetPackage)
        assertNotNull(result.affectedFiles)
        assertTrue(result.affectedFiles!!.any { it.contains("Service.java") })
        assertTrue(result.affectedFiles!!.any { it.contains("ServiceSuper.java") })
        val newClass = JavaPsiFacade.getInstance(project).findClass("example.api.ServiceSuper", GlobalSearchScope.allScope(project))
        assertNotNull(newClass)
        assertTrue(newClass!!.hasModifierProperty("abstract"))
        assertTrue(newClass.findMethodsByName("doIt", false).isNotEmpty())
        assertTrue(newClass.findMethodsByName("doIt", false).single().hasModifierProperty("abstract"))
        val afterSource = PsiManager.getInstance(project).findFile(sourceFile.virtualFile) as PsiJavaFile
        val afterClass = afterSource.classes.single()
        assertTrue(afterClass.extendsList?.referencedTypes?.any { it.canonicalText == "example.api.ServiceSuper" } == true)
    }

    fun testPersistsSourceAndNewFile() {
        val (sourceFile, _) = fixture()
        val prep = prepare(sourceFile, listOf("doIt"), "ServiceSuper2", null)
        val persister = RecordingNativeRefactoringDocumentPersister()
        val result = runExecutor { execWithNoDialog { IntellijExtractSuperclassExecutor(persister).extract(project, prep) } }
        persister.assertPersistedExactly(*requireNotNull(result.affectedFiles).toTypedArray())
    }

    fun testOneUndoRestoresFiles() {
        val (sourceFile, _) = fixture()
        val beforeText = sourceFile.text
        val beforeQualified = "example.api.ServiceSuper3"
        assertNull(JavaPsiFacade.getInstance(project).findClass(beforeQualified, GlobalSearchScope.allScope(project)))
        val prep = prepare(sourceFile, listOf("doIt"), "ServiceSuper3", "example.api")
        runExecutor { execWithNoDialog { executor.extract(project, prep) } }
        assertNotNull(JavaPsiFacade.getInstance(project).findClass(beforeQualified, GlobalSearchScope.allScope(project)))
        val um = UndoManager.getInstance(project)
        assertTrue(um.isUndoAvailable(null))
        val prev = TestDialogManager.setTestDialog(TestDialog.OK)
        try { um.undo(null) } finally { TestDialogManager.setTestDialog(prev) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(beforeText, sourceFile.text)
        assertNull(JavaPsiFacade.getInstance(project).findClass(beforeQualified, GlobalSearchScope.allScope(project)))
    }

    private fun fixture(): Pair<PsiJavaFile, PsiJavaFile> {
        val source = mirrorRealFile("example/Service.java", """
            package example;
            public class Service {
                public void doIt() {}
                public void run(String s) {}
                public static final int COUNT = 1;
            }
        """.trimIndent())
        val caller = mirrorRealFile("example/Caller.java", """
            package example;
            public class Caller {
                void use(Service s) { s.doIt(); }
            }
        """.trimIndent())
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        return Pair(PsiManager.getInstance(project).findFile(source) as PsiJavaFile, PsiManager.getInstance(project).findFile(caller) as PsiJavaFile)
    }

    private fun prepare(file: PsiJavaFile, memberNames: List<String>, superclassName: String, targetPackage: String?): ExtractSuperclassPreparation {
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val className = "Service"
        val classLine = lineOf("example/Service.java", className)
        val classCol = colOf("example/Service.java", className)
        val classEndLine = lineEndOf("example/Service.java", className)
        val classEndCol = colEndOf("example/Service.java", className)
        val memberLines = memberNames.map { lineOf("example/Service.java", it) }
        val memberCols = memberNames.map { colOf("example/Service.java", it) }
        val memberEndLines = memberNames.map { lineEndOf("example/Service.java", it) }
        val memberEndCols = memberNames.map { colEndOf("example/Service.java", it) }
        val res = resolver.resolve(
            project, "example/Service.java",
            classLine, classCol, classEndLine, classEndCol,
            memberLines, memberCols, memberEndLines, memberEndCols,
            superclassName, targetPackage,
        )
        assertTrue("expected success but was $res", res is ExtractSuperclassSelectionResolution.Success)
        return (res as ExtractSuperclassSelectionResolution.Success).preparation
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
