package com.example.airefactoring.refactoring.extractdelegate

import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.extractclass.ExtractClassProcessor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path

/**
 * Headless feasibility spike for P5 "Extract Delegate".
 *
 * Drives IDEA's native ExtractClassProcessor directly (no dialog, no action, no editor). The
 * processor constructor eagerly builds the extracted class inside its own WriteCommandAction and
 * run() applies usages as a second undoable command; the spike therefore wraps constructor+run()
 * in one outer CommandProcessor command so a single Undo restores everything, and verifies the
 * roadmap admission-gate criteria.
 */
class ExtractDelegateHeadlessSpikeTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    private fun addSourceFile(): PsiJavaFile {
        return myFixture.addFileToProject(
            "example/OrderService.java",
            """
                package example;
                public class OrderService {
                    public double unitPrice = 2.5;
                    private int discount = 1;
                    public double price(int quantity) { return quantity * unitPrice; }
                    public double total() { return price(2) - discount; }
                }
            """.trimIndent(),
        ) as PsiJavaFile
    }

    private fun committedText(vararg files: PsiJavaFile): List<String> {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        return files.map { (myFixture.psiManager.findFile(it.virtualFile) as PsiJavaFile).text }
    }

    private fun runWithoutDialog(block: () -> Unit) {
        val previous = TestDialogManager.setTestDialog(object : TestDialog {
            override fun show(message: String): Int =
                throw AssertionError("Extract Delegate must not open a dialog: $message")
        })
        try {
            block()
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
    }

    /**
     * Constructor+run() inside ONE outer command: the native processor's internal write actions
     * become parts of a single undoable group, so one Undo restores everything.
     */
    private fun extractInOneCommand(cls: com.intellij.psi.PsiClass, extractInnerClass: Boolean, generateAccessors: Boolean) {
        CommandProcessor.getInstance().executeCommand(
            project,
            {
                ExtractClassProcessor(
                    cls,
                    listOfNotNull(cls.findFieldByName("unitPrice", false)),
                    cls.findMethodsByName("price", false).toList(),
                    emptyList(),
                    "example",
                    null,
                    "OrderDelegate",
                    null,
                    generateAccessors,
                    emptyList(),
                    extractInnerClass,
                ).run()
            },
            "Extract Delegate",
            null,
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    fun testExtractsIntoSiblingFileAndOneUndoRestoresEverything() {
        val sourceFile = addSourceFile()
        val before = committedText(sourceFile).single()
        val cls = sourceFile.classes.single()

        runWithoutDialog {
            extractInOneCommand(cls, extractInnerClass = false, generateAccessors = true)
        }

        // extractInnerClass=false creates the delegate as its own class file in the same package.
        val facade = com.intellij.psi.JavaPsiFacade.getInstance(project)
        val delegateClass = facade.findClass(
            "example.OrderDelegate",
            com.intellij.psi.search.GlobalSearchScope.projectScope(project),
        )
        assertNotNull("sibling delegate class must be created", delegateClass)
        val delegateVf = delegateClass!!.containingFile.virtualFile
        assertEquals("example", (myFixture.psiManager.findFile(delegateVf) as PsiJavaFile).packageName)
        assertNotNull("extracted field must exist on the delegate", delegateClass.findFieldByName("unitPrice", false))
        val sourceAfter = committedText(sourceFile).single()
        assertTrue("price implementation must leave the source class", !sourceAfter.contains("quantity * unitPrice"))
        assertTrue("kept method call must be rewritten to the delegate", sourceAfter.contains("orderDelegate"))
        assertTrue("kept method must stay on the source class", sourceAfter.contains("total"))

        val um = UndoManager.getInstance(project)
        assertTrue("Extract Delegate must be one global Undo", um.isUndoAvailable(null))
        val prev = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            um.undo(null)
        } finally {
            TestDialogManager.setTestDialog(prev)
        }
        LocalFileSystem.getInstance().refresh(false)
        assertEquals("one Undo must restore the source byte-identically", before, committedText(sourceFile).single())
        assertTrue(
            "one Undo must remove the generated delegate file",
            delegateVf.path.let { LocalFileSystem.getInstance().findFileByPath(it) } == null,
        )
    }

    fun testExtractsIntoNestedInnerClassAndOneUndoRestoresEverything() {
        val sourceFile = addSourceFile()
        val before = committedText(sourceFile).single()
        val cls = sourceFile.classes.single()

        runWithoutDialog {
            extractInOneCommand(cls, extractInnerClass = true, generateAccessors = true)
        }

        val afterPsi = myFixture.psiManager.findFile(sourceFile.virtualFile) as PsiJavaFile
        assertNotNull(
            "nested delegate must be an inner class of the source",
            afterPsi.classes.single().findInnerClassByName("OrderDelegate", false),
        )

        val um = UndoManager.getInstance(project)
        assertTrue("Extract Delegate must be one global Undo", um.isUndoAvailable(null))
        val prev = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            um.undo(null)
        } finally {
            TestDialogManager.setTestDialog(prev)
        }
        assertEquals("one Undo must restore byte-identically (no stray newline)", before, committedText(sourceFile).single())
    }

    fun testKnownConflictRaisesConflictsInTestsExceptionWithoutMutation() {
        val sourceFile = addSourceFile()
        val before = committedText(sourceFile).single()
        val cls = sourceFile.classes.single()

        var conflict: BaseRefactoringProcessor.ConflictsInTestsException? = null
        try {
            runWithoutDialog {
                // extract total(): it reads the private kept field `discount`, which needs a
                // getter when generateAccessors=false — a native conflict.
                ExtractClassProcessor(
                    cls,
                    emptyList(),
                    cls.findMethodsByName("total", false).toList(),
                    emptyList(),
                    "example",
                    null,
                    "OrderDelegate",
                    null,
                    false,
                    emptyList(),
                    false,
                ).run()
            }
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            conflict = e
        }
        assertNotNull(
            "extracting a method that reads a kept private field with generateAccessors=false must report a conflict",
            conflict,
        )
        assertEquals("no mutation may be applied when conflicts are reported", before, committedText(sourceFile).single())
    }
}