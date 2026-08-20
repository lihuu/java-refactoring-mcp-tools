package com.example.airefactoring.refactoring.pullup

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.memberPullUp.PullUpProcessor
import com.intellij.refactoring.util.DocCommentPolicy
import com.intellij.refactoring.util.classMembers.MemberInfo
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class PullMembersUpHeadlessSpikeTest : LightJavaCodeInsightFixtureTestCase() {
    fun testPullUpRunsHeadlessly() {
        val baseFile = myFixture.addFileToProject(
            "a/Base.java",
            """
                package a;
                public class Base {}
            """.trimIndent(),
        ) as PsiJavaFile
        val subFile = myFixture.addFileToProject(
            "a/Sub.java",
            """
                package a;
                public class Sub extends Base {
                    public void handle(String s) { System.out.println(s); }
                    public static final int COUNT = 1;
                    private void help() {}
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val beforeSub = subFile.text
        val beforeBase = baseFile.text
        val subClass = (myFixture.psiManager.findFile(subFile.virtualFile) as PsiJavaFile).classes.single()
        val baseClass = (myFixture.psiManager.findFile(baseFile.virtualFile) as PsiJavaFile).classes.single()
        val handle = subClass.findMethodsByName("handle", false).single()
        val count = subClass.findFieldByName("COUNT", false)!!

        val infos = arrayOf(
            MemberInfo(handle).apply { isChecked = true; isToAbstract = true },
            MemberInfo(count).apply { isChecked = true }
        )

        runHeadlessPull(subClass, baseClass, infos)

        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val newBase = JavaPsiFacade.getInstance(project).findClass("a.Base", GlobalSearchScope.allScope(project))!!
        assertTrue(newBase.findMethodsByName("handle", false).isNotEmpty())
        assertTrue(newBase.findMethodsByName("handle", false).single().hasModifierProperty("abstract"))
        assertNotNull(newBase.findFieldByName("COUNT", false))

        val afterSub = myFixture.psiManager.findFile(subFile.virtualFile) as PsiJavaFile
        // Sub should now have abstract method removed (or override)
        // The method should be abstract in base, sub may have override or be abstract itself? For pull, sub retains override? Check.
        // At least sub should not have original concrete handle as before? We check base has it.
        assertTrue(afterSub.text != beforeSub)

        assertOneGlobalUndoRestores(beforeSub to beforeBase, subFile, baseFile, newBase)
    }

    private fun runHeadlessPull(sourceSub: com.intellij.psi.PsiClass, targetSuper: com.intellij.psi.PsiClass, infos: Array<MemberInfo>) {
        val throwingDialog = object : TestDialog { override fun show(message: String): Int = throw AssertionError("Pull Up must not open a dialog: $message") }
        val prev = TestDialogManager.setTestDialog(throwingDialog)
        try {
            val docPolicy = DocCommentPolicy(DocCommentPolicy.ASIS)
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                val processor = object : PullUpProcessor(sourceSub, targetSuper, infos, docPolicy) {
                    override fun showConflicts(conflicts: com.intellij.util.containers.MultiMap<com.intellij.psi.PsiElement, String>, usages: Array<com.intellij.usageView.UsageInfo>?): Boolean {
                        if (!conflicts.isEmpty) throw AssertionError("Unexpected conflicts: $conflicts")
                        return true
                    }
                }
                processor.setPreviewUsages(false)
                processor.run()
            }
        } finally { TestDialogManager.setTestDialog(prev) }
        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun assertOneGlobalUndoRestores(
        before: Pair<String, String>,
        subFile: PsiJavaFile,
        baseFile: PsiJavaFile,
        baseClass: com.intellij.psi.PsiClass
    ) {
        val um = UndoManager.getInstance(project)
        assertTrue(um.isUndoAvailable(null))
        val prev = TestDialogManager.setTestDialog(TestDialog.OK)
        try { um.undo(null) } finally { TestDialogManager.setTestDialog(prev) }
        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(before.first, subFile.text)
        assertEquals(before.second, baseFile.text)
    }
}
