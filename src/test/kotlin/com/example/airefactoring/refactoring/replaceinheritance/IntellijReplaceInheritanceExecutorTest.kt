package com.example.airefactoring.refactoring.replaceinheritance

import com.example.airefactoring.refactoring.RecordingNativeRefactoringDocumentPersister
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.dispatchAllEventsInIdeEventQueue
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import java.nio.file.Path

class IntellijReplaceInheritanceExecutorTest : LightJavaCodeInsightFixtureTestCase() {

    private val persister = RecordingNativeRefactoringDocumentPersister()
    private val executor = IntellijReplaceInheritanceExecutor(persister)

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    private fun mirrorRealFile(path: String, text: String) {
        val t = Path.of(project.basePath!!, path)
        Files.createDirectories(t.parent)
        if (!Files.exists(t)) Files.createFile(t)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(t.toString())!!
        WriteAction.run<RuntimeException> { VfsUtil.saveText(vf, text) }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private fun setupScenario(): Pair<String, PsiClass> {
        mirrorRealFile(
            "example/Base.java",
            """
                package example;
                public class Base {
                    public void doWork() {}
                    public int getValue() { return 42; }
                }
            """.trimIndent(),
        )
        mirrorRealFile(
            "example/Derived.java",
            """
                package example;
                public class Derived extends Base {
                    public void process() {
                        doWork();
                        System.out.println(getValue());
                    }
                }
            """.trimIndent(),
        )
        val derivedFile = psiManager.findFile(
            LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, "example/Derived.java").toString())!!,
        ) as PsiJavaFile
        return "example/Derived.java" to derivedFile.classes.single()
    }

    private fun derivedText(path: String): String =
        (psiManager.findFile(
            LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, path).toString())!!,
        ) as PsiJavaFile).text

    /**
     * Runs the suspend executor off the test (EDT) thread while pumping the IDE event queue,
     * mirroring the accepted IntellijExtractDelegateExecutorTest pattern: calling runBlocking on
     * the EDT itself deadlocks against the executor's `withContext(Dispatchers.EDT)` resumption.
     */
    private fun <T> runExecutor(block: suspend () -> T): T {
        val pool = Executors.newSingleThreadExecutor()
        return try {
            val f = pool.submit<T> { runBlocking { block() } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (System.nanoTime() < deadline && !f.isDone) {
                dispatchAllEventsInIdeEventQueue()
                Thread.sleep(1)
            }
            try {
                f.get(1, TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun preparation(path: String, derivedCls: PsiClass): ReplaceInheritanceWithDelegationPreparation =
        ReplaceInheritanceWithDelegationPreparation(
            classPointer = com.intellij.psi.SmartPointerManager.getInstance(project).createSmartPsiElementPointer(derivedCls),
            classTextSnapshot = derivedCls.text,
            sourceClassFqn = "example.Derived",
            targetBaseClassFqn = "example.Base",
            fieldName = "baseDelegate",
            delegateOtherMembers = true,
            generateGetter = true,
            affectedVirtualFiles = setOf(derivedCls.containingFile!!.virtualFile),
        )

    @Test
    fun testExecuteSuccessfully() {
        val (path, derivedCls) = setupScenario()
        val prep = preparation(path, derivedCls)

        val result = runExecutor {
            executor.execute(project, prep)
        }

        assertTrue(result.summary.contains("example.Derived"))

        val text = derivedText(path)
        assertFalse("inheritance must be removed", text.contains("extends Base"))
        assertTrue("delegate field must be added", text.contains("Base baseDelegate"))
        assertTrue("calls must be delegated", text.contains("baseDelegate.doWork()"))
        assertTrue("getter must be generated", text.contains("public Base getBaseDelegate()"))

        persister.assertPersistedExactly("example/Derived.java")
    }

    @Test
    fun testUndoRestoresOriginalState() {
        val (path, derivedCls) = setupScenario()
        val beforeText = derivedText(path)
        val prep = preparation(path, derivedCls)

        runExecutor {
            executor.execute(project, prep)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val um = UndoManager.getInstance(project)
        assertTrue("undo must be available", um.isUndoAvailable(null))
        val prev = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            um.undo(null)
        } finally {
            TestDialogManager.setTestDialog(prev)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        LocalFileSystem.getInstance().refresh(false)

        assertEquals("one Undo must restore original text", beforeText, derivedText(path))
    }

    @Test
    fun testRejectsStaleSnapshot() {
        val (path, derivedCls) = setupScenario()
        val prep = preparation(path, derivedCls)

        // Manually dirty the class
        WriteCommandAction.runWriteCommandAction(project) {
            val factory = PsiElementFactory.getInstance(project)
            derivedCls.add(factory.createMethodFromText("void stale() {}", null))
        }

        assertThrows(ReplaceInheritancePreparationException::class.java) {
            runExecutor {
                executor.execute(project, prep)
            }
        }
    }
}