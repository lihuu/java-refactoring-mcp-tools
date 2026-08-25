package com.example.airefactoring.refactoring.inlinemethod

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.inline.InlineMethodProcessor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class InlineMethodHeadlessSpikeTest : LightJavaCodeInsightFixtureTestCase() {

    fun testInlinesAllJavaUsagesDeletesMethodWithoutEditorOrDialogAndUndoRestoresBothFiles() {
        val rules = myFixture.addFileToProject(
            "example/PricingRules.java",
            """
                package example;

                public final class PricingRules {
                    public static int addTax(int amount) {
                        return amount + 5;
                    }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val checkout = myFixture.addFileToProject(
            "example/Checkout.java",
            """
                package example;

                public final class Checkout {
                    public int total(int amount) {
                        return PricingRules.addTax(amount) + PricingRules.addTax(10);
                    }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val beforeRules = rules.text
        val beforeCheckout = checkout.text

        val method = rules.classes.single().findMethodsByName("addTax", false).single()
        val usages = ReferencesSearch.search(method).findAll()
            .mapNotNull { it.element as? PsiReferenceExpression }

        assertEquals("fixture must provide two Java call usages", 2, usages.size)
        runProcessorAndAssertDialogFree {
            InlineMethodProcessor(
                project,
                method,
                usages.first(),
                null,
                false,
                false,
                false,
            )
        }
        assertTrue(
            "native Inline Method must remove the declaration after inlining every usage",
            rules.classes.single().findMethodsByName("addTax", false).isEmpty(),
        )
        assertFalse(
            "native Inline Method must replace every caller expression",
            checkout.text.contains("PricingRules.addTax"),
        )
        assertOneGlobalUndoRestores(beforeRules, beforeCheckout, rules, checkout)
    }

    private fun runProcessorAndAssertDialogFree(create: () -> InlineMethodProcessor) {
        val throwingDialog = object : TestDialog {
            override fun show(message: String): Int =
                throw AssertionError("Inline Method must not open a dialog: $message")
        }
        val previousDialog = TestDialogManager.setTestDialog(throwingDialog)
        try {
            create().apply { setPreviewUsages(false) }.run()
        } finally {
            TestDialogManager.setTestDialog(previousDialog)
        }
        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun assertOneGlobalUndoRestores(
        beforeRules: String,
        beforeCheckout: String,
        rules: PsiJavaFile,
        checkout: PsiJavaFile,
    ) {
        val undoManager = UndoManager.getInstance(project)
        assertTrue("Inline Method must be available as one global Undo", undoManager.isUndoAvailable(null))
        val previousDialog = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undoManager.undo(null)
        } finally {
            TestDialogManager.setTestDialog(previousDialog)
        }
        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals("method file must be restored byte-for-byte", beforeRules, rules.text)
        assertEquals("caller file must be restored byte-for-byte", beforeCheckout, checkout.text)
    }
}
