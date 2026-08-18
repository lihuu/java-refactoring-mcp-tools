package com.example.airefactoring.refactoring.moveinstancemethod

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.move.moveInstanceMethod.MoveInstanceMethodHandler
import com.intellij.refactoring.move.moveInstanceMethod.MoveInstanceMethodProcessor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/**
 * Feasibility evidence for invoking IntelliJ's native Move Instance Method refactoring directly,
 * without an editor, action, or dialog.
 *
 * This is intentionally a platform-level spike rather than an MCP tool test. It proves that
 * `MoveInstanceMethodProcessor` can be constructed headlessly with a method plus a parameter or
 * instance-field target and invoked with no dialog, and that one global Undo restores the fixture.
 *
 * For a PARAMETER target the native path is complete: the method moves to the target class, the
 * old-owner access creates the native bridge parameter through the suggested-name path, and a
 * cross-file caller is rewritten by IntelliJ. For a FIELD target, a spike-observed native gap
 * (documented in the design spec) means `MoveInstanceMethodProcessor.findUsages()` does not gather
 * external method-call sites, so the moved method and its bridge parameter are created but a
 * cross-file caller is left stale.
 */
class MoveInstanceMethodHeadlessSpikeTest : LightJavaCodeInsightFixtureTestCase() {

    fun testMovesMethodToParameterTargetUpdatesCallerAndRestoresWithOneUndo() {
        val invoiceFile = myFixture.addFileToProject(
            "example/Invoice.java",
            """
                package example;

                public class Invoice {
                    private final int amount;

                    public Invoice(int amount) {
                        this.amount = amount;
                    }

                    public int applyDiscount(Customer customer) {
                        return this.amount - customer.discount();
                    }

                    public static class Customer {
                        private final int discountRate;

                        public Customer(int discountRate) {
                            this.discountRate = discountRate;
                        }

                        public int discount() {
                            return discountRate;
                        }
                    }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val callerFile = myFixture.addFileToProject(
            "example/Checkout.java",
            """
                package example;

                public class Checkout {
                    public int charge() {
                        Invoice invoice = new Invoice(100);
                        Invoice.Customer customer = new Invoice.Customer(10);
                        return invoice.applyDiscount(customer);
                    }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val invoiceText = invoiceFile.text
        val callerText = callerFile.text

        val invoiceClass = invoiceFile.classes.single()
        val method = invoiceClass.findMethodsByName("applyDiscount", false).single()
        val targetVariable = method.parameterList.parameters.single()

        runProcessorAndAssertDialogFree(method, targetVariable)

        // The source class no longer owns the method; the target class does.
        assertTrue(
            "source Invoice must lose applyDiscount:\n${invoiceFile.text}",
            invoiceClass.findMethodsByName("applyDiscount", false).isEmpty(),
        )
        val customerClass = invoiceClass.innerClasses.single { it.name == "Customer" }
        val moved = customerClass.findMethodsByName("applyDiscount", false).single()
        assertTrue(
            "target Customer must own applyDiscount:\n${invoiceFile.text}",
            customerClass.findMethodsByName("applyDiscount", false).isNotEmpty(),
        )
        // The native old-owner bridge parameter must exist so the moved method can still reach `amount`.
        assertEquals(1, moved.parameterList.parametersCount)
        assertEquals("example.Invoice", moved.parameterList.parameters[0].type.canonicalText)
        assertTrue("moved method must read the old owner's amount", moved.text.contains("amount"))

        // The caller's receiver and arguments are updated by IntelliJ.
        val call = PsiTreeUtil.findChildOfType(callerFile, com.intellij.psi.PsiMethodCallExpression::class.java)
        assertNotNull("cross-file call disappeared:\n${callerFile.text}", call)
        assertTrue(
            "caller must now invoke on customer with invoice as old-owner argument:\n${callerFile.text}",
            call!!.text == "customer.applyDiscount(invoice)",
        )

        assertOneGlobalUndoRestores(invoiceText, callerText, invoiceFile, callerFile)
    }

    fun testMovesMethodToFieldTargetWithoutCallerAndUndoWithOneUndo() {
        val invoiceFile = myFixture.addFileToProject(
            "example/Invoice.java",
            """
                package example;

                public class Invoice {
                    private final int amount;
                    public final Customer customer;

                    public Invoice(int amount, Customer customer) {
                        this.amount = amount;
                        this.customer = customer;
                    }

                    public int applyDiscount() {
                        return this.amount - this.customer.discount();
                    }

                    public static class Customer {
                        private final int discountRate;

                        public Customer(int discountRate) {
                            this.discountRate = discountRate;
                        }

                        public int discount() {
                            return discountRate;
                        }
                    }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val callerFile = myFixture.addFileToProject(
            "example/FieldCheckout.java",
            """
                package example;

                public class FieldCheckout {
                    public int charge() {
                        Invoice invoice = new Invoice(100, new Invoice.Customer(10));
                        return invoice.customer.applyDiscount();
                    }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val invoiceText = invoiceFile.text
        val callerText = callerFile.text

        val invoiceClass = invoiceFile.classes.single()
        val method = invoiceClass.findMethodsByName("applyDiscount", false).single()
        val targetVariable = invoiceClass.findFieldByName("customer", false)
        assertNotNull("fixture field target not found", targetVariable)

        runProcessorAndAssertDialogFree(method, targetVariable!!)

        assertTrue(
            "source Invoice must lose applyDiscount:\n${invoiceFile.text}",
            invoiceClass.findMethodsByName("applyDiscount", false).isEmpty(),
        )
        val customer = invoiceClass.innerClasses.single { it.name == "Customer" }
        val moved = customer.findMethodsByName("applyDiscount", false).single()
        // Native old-owner bridge parameter created for the field-target variant too.
        assertEquals(1, moved.parameterList.parametersCount)
        assertEquals("example.Invoice", moved.parameterList.parameters[0].type.canonicalText)
        assertTrue("moved method must read the old owner's amount", moved.text.contains("amount"))

        val call = PsiTreeUtil.findChildOfType(callerFile, com.intellij.psi.PsiMethodCallExpression::class.java)
        assertNotNull("cross-file call disappeared:\n${callerFile.text}", call)
        // Native limitation discovered in this spike (documented in the design spec): for a FIELD
        // target, MoveInstanceMethodProcessor.findUsages() collects only the method-body usages and
        // omits external method-call sites, so a cross-file caller is NOT rewritten. The method is
        // still moved and the old-owner bridge parameter created, but the stale caller keeps its
        // zero-argument call. This assertion locks in that observed native behavior.
        assertEquals(
            "field-target external caller must be left unchanged by the native processor",
            "invoice.customer.applyDiscount()",
            call!!.text,
        )

        assertOneGlobalUndoRestores(invoiceText, callerText, invoiceFile, callerFile)
    }

    private fun runProcessorAndAssertDialogFree(method: PsiMethod, targetVariable: PsiVariable) {
        val throwingDialog = object : TestDialog {
            override fun show(message: String): Int =
                throw AssertionError("Move Instance Method must not open a dialog: $message")
        }
        val previousDialog = TestDialogManager.setTestDialog(throwingDialog)
        try {
            val processor = MoveInstanceMethodProcessor(
                project,
                method,
                targetVariable,
                PsiModifier.PUBLIC,
                false,
                MoveInstanceMethodHandler.suggestParameterNames(method, targetVariable),
            )
            processor.setPreviewUsages(false)
            processor.run()
        } finally {
            TestDialogManager.setTestDialog(previousDialog)
        }
        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun assertOneGlobalUndoRestores(
        sourceText: String,
        callerText: String,
        sourceFile: PsiJavaFile,
        callerFile: PsiJavaFile,
    ) {
        val undoManager = UndoManager.getInstance(project)
        assertTrue("Move Instance Method must be available as one global Undo", undoManager.isUndoAvailable(null))
        val undoDialog = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undoManager.undo(null)
        } finally {
            TestDialogManager.setTestDialog(undoDialog)
        }
        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals("source file must be restored byte-for-byte", sourceText, sourceFile.text)
        assertEquals("caller file must be restored byte-for-byte", callerText, callerFile.text)
    }
}
