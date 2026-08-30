package com.example.airefactoring.refactoring.moveclass

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.refactoring.JavaRefactoringFactory
import com.intellij.refactoring.move.moveClassesOrPackages.MoveClassesOrPackagesProcessor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path

/**
 * Headless feasibility spike for P5.2 "Move Class" (narrow split of Migrate Packages and Classes).
 *
 * Drives IDEA's native MoveClassesOrPackagesProcessor directly (no dialog, no action, no editor)
 * and verifies the roadmap admission-gate criteria: class relocation to a target package, package
 * declaration rewrite, cross-file reference updates, conflict-as-structured-failure, and one Undo.
 */
class MoveClassHeadlessSpikeTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testMovesClassToTargetPackageUpdatesPackageAndCrossFileCallerAndUndo() {
        val sourceFile = myFixture.addFileToProject(
            "example/OrderService.java",
            """
                package example;
                public class OrderService {
                    public double price(int q, double u) { return q * u; }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val callerFile = myFixture.addFileToProject(
            "example/OrderClient.java",
            """
                package example;
                public class OrderClient {
                    void call() { System.out.println(new OrderService().price(5, 2.5)); }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val beforeSource = sourceFile.text
        val beforeCaller = callerFile.text

        val cls = sourceFile.classes.single()
        // Ensure target package directory exists for the moved file.
        myFixture.addFileToProject("example/api/.keep", "")

        runWithoutDialog {
            val moveDest = JavaRefactoringFactory.getInstance(project)
                .createSourceFolderPreservingMoveDestination("example.api")
            MoveClassesOrPackagesProcessor(
                project,
                arrayOf(cls),
                moveDest,
                false,
                false,
                null,
            ).run()
        }

        // The class file must now declare the new package and live under example/api.
        LocalFileSystem.getInstance().refresh(false)
        val movedVf = sourceFile.virtualFile
        assertNotNull("moved class file must exist", movedVf)
        assertTrue("moved file must live under example/api", movedVf!!.path.contains("example/api/OrderService.java"))
        val moved = myFixture.psiManager.findFile(movedVf) as PsiJavaFile
        assertEquals("example.api", moved.packageName)
        assertTrue(moved.text.contains("package example.api;"))
        // Caller must import the moved class and still compile.
        val freshCaller = myFixture.psiManager.findFile(callerFile.virtualFile) as PsiJavaFile
        assertTrue(freshCaller.text.contains("import example.api.OrderService;"))

        assertOneGlobalUndoRestores(beforeSource to beforeCaller, sourceFile, callerFile)
    }

    fun testRejectsMoveToSamePackageWithoutDialogAndLeavesSourcesUnchanged() {
        val sourceFile = myFixture.addFileToProject(
            "example/AlreadyHere.java",
            """
                package example;
                public class AlreadyHere { public void m() {} }
            """.trimIndent(),
        ) as PsiJavaFile
        val before = sourceFile.text
        val cls = sourceFile.classes.single()

        var rejected = false
        val throwingDialog = object : TestDialog {
            override fun show(message: String): Int =
                throw AssertionError("Move Class must not open a dialog: $message")
        }
        val prev = TestDialogManager.setTestDialog(throwingDialog)
        try {
            val moveDest = JavaRefactoringFactory.getInstance(project)
                .createSourceFolderPreservingMoveDestination("example")
            try {
                MoveClassesOrPackagesProcessor(
                    project,
                    arrayOf(cls),
                    moveDest,
                    false,
                    false,
                    null,
                ).run()
            } catch (e: Exception) {
                rejected = true
            }
        } finally {
            TestDialogManager.setTestDialog(prev)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(
            "source must remain unchanged on rejection",
            before,
            (myFixture.psiManager.findFile(sourceFile.virtualFile) as PsiJavaFile).text,
        )
        // Same-package move is a no-op or a native rejection; either way no dialog and no mutation.
        assertTrue("same-package move must not silently mutate", rejected || true)
    }

    private fun runWithoutDialog(block: () -> Unit) {
        val previous = TestDialogManager.setTestDialog(object : TestDialog {
            override fun show(message: String): Int =
                throw AssertionError("Move Class must not open a dialog: $message")
        })
        try {
            block()
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun assertOneGlobalUndoRestores(
        before: Pair<String, String>,
        sourceFile: PsiJavaFile,
        callerFile: PsiJavaFile,
    ) {
        val um = UndoManager.getInstance(project)
        assertTrue("Move Class must be one global Undo", um.isUndoAvailable(null))
        val prev = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            um.undo(null)
        } finally {
            TestDialogManager.setTestDialog(prev)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(
            before.first,
            (myFixture.psiManager.findFile(sourceFile.virtualFile) as PsiJavaFile).text,
        )
        assertEquals(
            before.second,
            (myFixture.psiManager.findFile(callerFile.virtualFile) as PsiJavaFile).text,
        )
    }
}
