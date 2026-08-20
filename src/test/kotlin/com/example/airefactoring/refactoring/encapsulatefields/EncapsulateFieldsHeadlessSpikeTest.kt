package com.example.airefactoring.refactoring.encapsulatefields

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.refactoring.encapsulateFields.EncapsulateFieldsDescriptor
import com.intellij.refactoring.encapsulateFields.EncapsulateFieldsProcessor
import com.intellij.refactoring.encapsulateFields.FieldDescriptorImpl
import com.intellij.refactoring.encapsulateFields.JavaEncapsulateFieldHelper
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class EncapsulateFieldsHeadlessSpikeTest : LightJavaCodeInsightFixtureTestCase() {

    fun testEncapsulateFieldsRunsHeadlessly() {
        val file = myFixture.addFileToProject(
            "example/Order.java",
            """
                package example;
                public class Order {
                    int amount;
                    String status;
                    public int use() { return amount; }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val before = file.text
        val orderClass = file.classes.single()
        val amountField = orderClass.findFieldByName("amount", false)!!
        val statusField = orderClass.findFieldByName("status", false)!!
        val helper = JavaEncapsulateFieldHelper()
        val d1 = FieldDescriptorImpl(
            amountField, "getAmount", "setAmount",
            helper.generateMethodPrototype(amountField, "getAmount", true),
            helper.generateMethodPrototype(amountField, "setAmount", false),
        )
        val d2 = FieldDescriptorImpl(
            statusField, "getStatus", "setStatus",
            helper.generateMethodPrototype(statusField, "getStatus", true),
            helper.generateMethodPrototype(statusField, "setStatus", false),
        )
        val descriptor = object : EncapsulateFieldsDescriptor {
            override fun getSelectedFields() = arrayOf(d1, d2)
            override fun getTargetClass() = orderClass
            override fun getFieldsVisibility() = "private"
            override fun getAccessorsVisibility() = "public"
            override fun isToEncapsulateGet() = true
            override fun isToEncapsulateSet() = true
            override fun isToUseAccessorsWhenAccessible() = true
            override fun getJavadocPolicy() = 0
        }
        runProcessorAndAssertDialogFree { EncapsulateFieldsProcessor(project, descriptor) }
        val afterClass = (myFixture.psiManager.findFile(file.virtualFile) as PsiJavaFile).classes.single()
        assertTrue(afterClass.findMethodsByName("getAmount", false).isNotEmpty())
        assertTrue(afterClass.findMethodsByName("setAmount", false).isNotEmpty())
        assertTrue(afterClass.findFieldByName("amount", false)!!.hasModifierProperty(com.intellij.psi.PsiModifier.PRIVATE))
        assertOneGlobalUndoRestores(before, file)
    }

    private fun runProcessorAndAssertDialogFree(create: () -> com.intellij.refactoring.BaseRefactoringProcessor) {
        val throwingDialog = object : TestDialog {
            override fun show(message: String): Int = throw AssertionError("Encapsulate Fields must not open a dialog: $message")
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
        assertTrue("Encapsulate must be available as one global Undo", undoManager.isUndoAvailable(null))
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
