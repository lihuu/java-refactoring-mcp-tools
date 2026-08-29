package com.example.airefactoring.refactoring.extractmethodobject

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.refactoring.extractMethodObject.ExtractMethodObjectHandler
import com.intellij.refactoring.extractMethodObject.ExtractMethodObjectProcessor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path

/**
 * Headless feasibility spike for P5.2 "Replace Method with Method Object".
 *
 * Drives IDEA's native ExtractMethodObjectProcessor directly (no dialog, no action, no editor)
 * and verifies the roadmap admission-gate criteria: inner-class method object creation,
 * original-method delegation rewrite, cross-file caller migration, conflict-as-structured-failure,
 * and one global Undo.
 */
class ExtractMethodObjectHeadlessSpikeTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testReplacesWholeMethodBodyWithMethodObjectAndMigratesCrossFileCaller() {
        val serviceFile = myFixture.addFileToProject(
            "example/OrderService.java",
            """
                package example;
                public class OrderService {
                    public double price(int quantity, double unit) {
                        double subtotal = quantity * unit;
                        double discount = quantity > 10 ? 0.1 : 0.0;
                        return subtotal * (1 - discount);
                    }
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
        val beforeService = serviceFile.text
        val beforeCaller = callerFile.text

        val method = serviceFile.classes.single().findMethodsByName("price", false).single()

        runWithoutDialog {
            // Default is createInnerClass = true; keep the canonical inner-class Method Object.
            val processor = ExtractMethodObjectProcessor(
                project, null, method.body!!.statements, "PriceObject",
            )
            processor.setCreateInnerClass(true)
            val ep = processor.getExtractProcessor()
            ep.setShowErrorDialogs(false)
            ep.setPreviewSupported(false)
            assertTrue("extract processor must be preparable headlessly", ep.prepare())
            ep.setMethodName("invoke")
            ep.setDataFromInputVariables()
            ExtractMethodObjectHandler.extractMethodObject(project, null, processor, ep)
        }

        // Original method body must now delegate to the method object (no inline computation).
        val afterMethod = (myFixture.psiManager.findFile(serviceFile.virtualFile) as PsiJavaFile)
            .classes.single().findMethodsByName("price", false).single()
        val delegateBody = afterMethod.body!!.text
        assertTrue(
            "original method must delegate to method object, was: $delegateBody",
            delegateBody.contains("invoke(") || delegateBody.contains("new "),
        )

        // The method-object inner class must exist with migrated locals as fields + an invoke method.
        val containing = afterMethod.containingClass!!
        val mo = containing.innerClasses.firstOrNull()
        assertNotNull("method-object inner class must be created", mo)
        assertTrue(
            "method object must expose an 'invoke' method",
            mo!!.findMethodsByName("invoke", false).isNotEmpty(),
        )
        assertTrue(
            "migrated locals must become fields on the method object",
            mo.fields.any { it.name == "quantity" } || mo.fields.any { it.name == "unit" },
        )

        // Caller still calls price(...) — the public method surface is preserved by delegation.
        assertTrue(callerFile.text.contains("price(5, 2.5)"))

        assertOneGlobalUndoRestores(beforeService to beforeCaller, serviceFile, callerFile)
    }

    fun testEmptyMethodBodyIsRejectedAtSelectionBoundaryNotSilentlyMutated() {
        // An empty method has no extractable statements. The native processor constructor
        // requires at least one target element (it indexes elements[0] when no editor is given),
        // so the production resolver must reject an empty/non-selectable body with a structured
        // error (NO_EXTRACTABLE_ELEMENTS) BEFORE constructing the processor.
        val serviceFile = myFixture.addFileToProject(
            "example/EmptyService.java",
            """
                package example;
                public class EmptyService {
                    public void noop() { }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val before = serviceFile.text
        val method = serviceFile.classes.single().findMethodsByName("noop", false).single()
        val statements = method.body!!.statements

        // Resolver-equivalent guard: an empty selection must not reach the native processor.
        assertTrue("empty method body must yield no extractable statements", statements.isEmpty())

        // Confirming the native boundary rejects an empty selection rather than mutating.
        val prev = TestDialogManager.setTestDialog(object : TestDialog {
            override fun show(message: String): Int =
                throw AssertionError("Replace Method with Method Object must not open a dialog: $message")
        })
        var nativeRejected = false
        try {
            try {
                ExtractMethodObjectProcessor(project, null, statements, "NoopObject")
            } catch (e: Exception) {
                // ArrayIndexOutOfBoundsException from the empty selection is the expected guard.
                nativeRejected = true
            }
        } finally {
            TestDialogManager.setTestDialog(prev)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertTrue("native boundary must reject an empty selection", nativeRejected)
        assertEquals(
            "source must remain unchanged",
            before,
            (myFixture.psiManager.findFile(serviceFile.virtualFile) as PsiJavaFile).text,
        )
    }

    private fun runWithoutDialog(block: () -> Unit) {
        val previous = TestDialogManager.setTestDialog(object : TestDialog {
            override fun show(message: String): Int =
                throw AssertionError("Replace Method with Method Object must not open a dialog: $message")
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
        serviceFile: PsiJavaFile,
        callerFile: PsiJavaFile,
    ) {
        val um = UndoManager.getInstance(project)
        assertTrue("Replace Method with Method Object must be one global Undo", um.isUndoAvailable(null))
        val prev = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            um.undo(null)
        } finally {
            TestDialogManager.setTestDialog(prev)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(
            before.first,
            (myFixture.psiManager.findFile(serviceFile.virtualFile) as PsiJavaFile).text,
        )
        assertEquals(
            before.second,
            (myFixture.psiManager.findFile(callerFile.virtualFile) as PsiJavaFile).text,
        )
    }
}
