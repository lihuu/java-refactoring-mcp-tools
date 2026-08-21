package com.example.airefactoring.refactoring.useinterface

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.turnRefsToSuper.TurnRefsToSuperProcessor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap

class UseInterfaceWherePossibleHeadlessSpikeTest : LightJavaCodeInsightFixtureTestCase() {

    fun testTurnRefsToSuperRunsHeadlessly() {
        val samplesFile = myFixture.addFileToProject(
            "a/Samples.java",
            """
                package a;
                public interface Widenable { String label(); }
                public class Impl implements Widenable {
                    @Override public String label() { return "x"; }
                    public void onlyOnImpl() {}
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val holderFile = myFixture.addFileToProject(
            "a/Holder.java",
            """
                package a;
                public class Holder {
                    Impl field = new Impl();
                    String use(Impl p) {
                        Impl local = p;
                        boolean isImpl = local instanceof Impl;
                        return local.label();
                    }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val beforeSamples = samplesFile.text
        val beforeHolder = holderFile.text
        val javaFile = { f: PsiJavaFile -> myFixture.psiManager.findFile(f.virtualFile) as PsiJavaFile }
        val impl = javaFile(samplesFile).classes.single { it.name == "Impl" }
        val widenable = javaFile(samplesFile).classes.single { it.name == "Widenable" }

        val renames = runHeadlessTurn(impl, widenable)

        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val afterHolder = javaFile(holderFile).text
        assertTrue("Holder must be rewritten", afterHolder != beforeHolder)
        assertTrue("field type widened", afterHolder.contains("Widenable field = new Impl();"))
        assertTrue("parameter type widened", afterHolder.contains("String use(Widenable p)"))
        assertTrue("local type widened", afterHolder.contains("Widenable local = p;"))
        assertTrue("instanceof operand kept (replaceInstanceOf=false)", afterHolder.contains("local instanceof Impl"))
        assertTrue("no dialog-driven rename expected here", renames.isEmpty())

        assertOneGlobalUndoRestores(beforeSamples to beforeHolder, samplesFile, holderFile)
    }

    private fun runHeadlessTurn(source: com.intellij.psi.PsiClass, target: com.intellij.psi.PsiClass): Map<*, *> {
        val throwingDialog = object : TestDialog {
            override fun show(message: String): Int = throw AssertionError("Use Interface must not open a dialog: $message")
        }
        val prev = TestDialogManager.setTestDialog(throwingDialog)
        try {
            val renames = LinkedHashMap<SmartPsiElementPointer<*>, String>()
            WriteCommandAction.runWriteCommandAction(project) {
                val processor = object : TurnRefsToSuperProcessor(project, source, target, false) {
                    val exposedRenames: Map<SmartPsiElementPointer<*>, String> get() = myVariablesRenames
                    override fun showConflicts(conflicts: MultiMap<PsiElement, String>, usages: Array<UsageInfo>?): Boolean {
                        if (!conflicts.isEmpty) throw AssertionError("Unexpected conflicts: $conflicts")
                        return true
                    }
                }
                processor.setPreviewUsages(false)
                processor.run()
                renames.putAll(processor.exposedRenames)
            }
            return renames
        } finally {
            TestDialogManager.setTestDialog(prev)
        }
    }

    private fun assertOneGlobalUndoRestores(
        before: Pair<String, String>,
        samplesFile: PsiJavaFile,
        holderFile: PsiJavaFile,
    ) {
        val um = UndoManager.getInstance(project)
        assertTrue(um.isUndoAvailable(null))
        val prev = TestDialogManager.setTestDialog(TestDialog.OK)
        try { um.undo(null) } finally { TestDialogManager.setTestDialog(prev) }
        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(before.first, (myFixture.psiManager.findFile(samplesFile.virtualFile) as PsiJavaFile).text)
        assertEquals(before.second, (myFixture.psiManager.findFile(holderFile.virtualFile) as PsiJavaFile).text)
    }
}
