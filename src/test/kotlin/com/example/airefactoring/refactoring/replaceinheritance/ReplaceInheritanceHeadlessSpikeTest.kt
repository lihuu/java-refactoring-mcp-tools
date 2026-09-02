package com.example.airefactoring.refactoring.replaceinheritance

import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.refactoring.inheritanceToDelegation.InheritanceToDelegationProcessor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path

/**
 * Headless feasibility spike for "Replace Inheritance with Delegation".
 *
 * Drives IDEA's native InheritanceToDelegationProcessor directly.
 * Verifies that it removes inheritance, adds a delegate field, and updates calls
 * in a single atomic Undo unit.
 */
class ReplaceInheritanceHeadlessSpikeTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    private fun addSourceFiles(): Pair<PsiJavaFile, PsiJavaFile> {
        val base = myFixture.addFileToProject(
            "example/Base.java",
            """
                package example;
                public class Base {
                    public void doWork() { System.out.println("Base work"); }
                    public int getValue() { return 42; }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val derived = myFixture.addFileToProject(
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
        ) as PsiJavaFile
        return base to derived
    }

    private fun committedText(vararg files: PsiJavaFile): List<String> {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        return files.map { (myFixture.psiManager.findFile(it.virtualFile) as PsiJavaFile).text }
    }

    private fun runWithoutDialog(block: () -> Unit) {
        val previous = TestDialogManager.setTestDialog(object : TestDialog {
            override fun show(message: String): Int =
                throw AssertionError("Replace Inheritance must not open a dialog: $message")
        })
        try {
            block()
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
    }

    private fun replaceInheritanceInOneCommand(
        sourceCls: com.intellij.psi.PsiClass,
        baseCls: com.intellij.psi.PsiClass,
        fieldName: String,
        generateGetter: Boolean,
    ) {
        CommandProcessor.getInstance().executeCommand(
            project,
            {
                InheritanceToDelegationProcessor(
                    project,
                    sourceCls,
                    baseCls,
                    fieldName,
                    "", // innerClassName
                    emptyArray(), // delegatedInterfaces
                    emptyArray(), // delegatedMethods
                    true, // delegateOtherMembers
                    generateGetter,
                ).run()
            },
            "Replace Inheritance with Delegation",
            null,
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    fun testReplaceInheritanceAndOneUndoRestoresEverything() {
        val (baseFile, derivedFile) = addSourceFiles()
        val beforeDerived = committedText(derivedFile).single()
        val derivedCls = derivedFile.classes.single()
        val baseCls = baseFile.classes.single()

        runWithoutDialog {
            replaceInheritanceInOneCommand(derivedCls, baseCls, "baseDelegate", generateGetter = true)
        }

        val derivedAfter = committedText(derivedFile).single()

        // Verify inheritance removed
        assertFalse("must no longer extend Base", derivedAfter.contains("extends Base"))

        // Verify delegate field added
        assertTrue("must have delegate field", derivedAfter.contains("Base baseDelegate"))

        // Verify calls updated to delegation
        assertTrue("doWork() call must be delegated", derivedAfter.contains("baseDelegate.doWork()"))
        assertTrue("getValue() call must be delegated", derivedAfter.contains("baseDelegate.getValue()"))

        // Verify getter generated
        assertTrue("must have public getter", derivedAfter.contains("public Base getBaseDelegate()"))

        val um = UndoManager.getInstance(project)
        assertTrue("must be one global Undo", um.isUndoAvailable(null))
        val prev = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            um.undo(null)
        } finally {
            TestDialogManager.setTestDialog(prev)
        }

        assertEquals("one Undo must restore byte-identically", beforeDerived, committedText(derivedFile).single())
    }
}
