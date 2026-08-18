package com.example.airefactoring.refactoring.makestatic

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.refactoring.makeStatic.MakeClassStaticProcessor
import com.intellij.refactoring.makeStatic.MakeMethodStaticProcessor
import com.intellij.refactoring.makeStatic.Settings
import com.intellij.refactoring.util.VariableData
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/**
 * Feasibility evidence for invoking IntelliJ's native Make Static refactoring directly, without an
 * editor, action, or dialog.
 *
 * This is intentionally a platform-level spike rather than an MCP tool test. It proves that
 * `MakeMethodStaticProcessor` and `MakeClassStaticProcessor` can be constructed headlessly with
 * explicit `Settings` and invoked with no dialog, that field parameters are forwarded in order, and
 * that one global Undo restores the fixture.
 */
class JavaMakeStaticHeadlessSpikeTest : LightJavaCodeInsightFixtureTestCase() {

    fun testMakeMethodStaticRunsHeadlesslyWithFieldParameters() {
        val file = myFixture.addFileToProject(
            "example/Invoice.java",
            """
                package example;

                public class Invoice {
                    private int amount;
                    private int rate;

                    public Invoice(int amount, int rate) {
                        this.amount = amount;
                        this.rate = rate;
                    }

                    public int applyDiscount() {
                        return amount - rate;
                    }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val before = file.text

        val invoiceClass = file.classes.single()
        val method = invoiceClass.findMethodsByName("applyDiscount", false).single()
        val amountField = invoiceClass.findFieldByName("amount", false)!!
        val rateField = invoiceClass.findFieldByName("rate", false)!!

        val settings = Settings(
            true,
            null,
            arrayOf(
                VariableData(amountField).apply { name = "a"; passAsParameter = true },
                VariableData(rateField).apply { name = "r"; passAsParameter = true },
            ),
            false,
        )
        runProcessorAndAssertDialogFree { MakeMethodStaticProcessor(project, method, settings) }

        val converted = file.classes.single().findMethodsByName("applyDiscount", false).single()
        assertTrue(
            "method must become static:\n${converted.text}",
            converted.hasModifierProperty(PsiModifier.STATIC),
        )
        assertEquals(listOf("a", "r"), converted.parameterList.parameters.map { it.name })
        assertTrue("body must use the parameter names", converted.text.contains("a - r"))

        assertOneGlobalUndoRestores(before, file)
    }

    fun testMakeClassStaticRunsHeadlesslyWithoutOuterReference() {
        val file = myFixture.addFileToProject(
            "example/Invoice.java",
            """
                package example;

                public class Invoice {
                    public class Discount {
                        private final int percent;

                        public Discount(int percent) {
                            this.percent = percent;
                        }

                        public int value() {
                            return percent;
                        }
                    }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val before = file.text

        val invoiceClass = file.classes.single()
        val discount = invoiceClass.innerClasses.single { it.name == "Discount" }

        val settings = Settings(false, null, emptyArray(), false)
        runProcessorAndAssertDialogFree { MakeClassStaticProcessor(project, discount, settings) }

        val converted = file.classes.single().innerClasses.single { it.name == "Discount" }
        assertTrue(
            "inner class must become static:\n${converted.text}",
            converted.hasModifierProperty(PsiModifier.STATIC),
        )

        assertOneGlobalUndoRestores(before, file)
    }

    private fun runProcessorAndAssertDialogFree(create: () -> com.intellij.refactoring.BaseRefactoringProcessor) {
        val throwingDialog = object : TestDialog {
            override fun show(message: String): Int =
                throw AssertionError("Java Make Static must not open a dialog: $message")
        }
        val previousDialog = TestDialogManager.setTestDialog(throwingDialog)
        try {
            val processor = create()
            processor.setPreviewUsages(false)
            processor.run()
        } finally {
            TestDialogManager.setTestDialog(previousDialog)
        }
        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun assertOneGlobalUndoRestores(sourceText: String, sourceFile: PsiJavaFile) {
        val undoManager = UndoManager.getInstance(project)
        assertTrue("Make Static must be available as one global Undo", undoManager.isUndoAvailable(null))
        val undoDialog = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undoManager.undo(null)
        } finally {
            TestDialogManager.setTestDialog(undoDialog)
        }
        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals("source file must be restored byte-for-byte", sourceText, sourceFile.text)
    }
}
