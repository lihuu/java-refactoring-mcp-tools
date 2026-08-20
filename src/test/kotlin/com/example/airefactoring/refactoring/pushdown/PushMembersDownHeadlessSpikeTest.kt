package com.example.airefactoring.refactoring.pushdown

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.memberPushDown.PushDownProcessor
import com.intellij.refactoring.util.DocCommentPolicy
import com.intellij.refactoring.util.classMembers.MemberInfo
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.usageView.UsageInfo

class PushMembersDownHeadlessSpikeTest : LightJavaCodeInsightFixtureTestCase() {
    fun testPushDownRunsHeadlessly() {
        val superFile = myFixture.addFileToProject(
            "a/SuperBase.java",
            """
                package a;
                public class SuperBase {
                    public void handle(String s) { System.out.println(s); }
                    public static final int COUNT = 1;
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val subAFile = myFixture.addFileToProject(
            "a/SubA.java",
            """
                package a;
                public class SubA extends SuperBase {}
            """.trimIndent(),
        ) as PsiJavaFile
        val subBFile = myFixture.addFileToProject(
            "a/SubB.java",
            """
                package a;
                public class SubB extends SuperBase {}
            """.trimIndent(),
        ) as PsiJavaFile
        val beforeSuper = superFile.text
        val beforeA = subAFile.text
        val beforeB = subBFile.text

        val superClass = (myFixture.psiManager.findFile(superFile.virtualFile) as PsiJavaFile).classes.single()
        val handle = superClass.findMethodsByName("handle", false).single()
        val infos = listOf(MemberInfo(handle).apply { isChecked = true; isToAbstract = true })

        runHeadlessPush(superClass, infos)

        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val afterSuper = JavaPsiFacade.getInstance(project).findClass("a.SuperBase", GlobalSearchScope.allScope(project))!!
        assertTrue(afterSuper.findMethodsByName("handle", false).single().hasModifierProperty("abstract"))

        val subA = JavaPsiFacade.getInstance(project).findClass("a.SubA", GlobalSearchScope.allScope(project))!!
        assertTrue(subA.findMethodsByName("handle", false).isNotEmpty())
        val subB = JavaPsiFacade.getInstance(project).findClass("a.SubB", GlobalSearchScope.allScope(project))!!
        assertTrue(subB.findMethodsByName("handle", false).isNotEmpty())

        assertOneGlobalUndoRestores(beforeSuper to (beforeA to beforeB), superFile, subAFile, subBFile)
    }

    private fun runHeadlessPush(sourceSuper: com.intellij.psi.PsiClass, infos: List<MemberInfo>) {
        val throwingDialog = object : TestDialog { override fun show(message: String): Int = throw AssertionError("Push Down must not open a dialog: $message") }
        val prev = TestDialogManager.setTestDialog(throwingDialog)
        try {
            val docPolicy = DocCommentPolicy(DocCommentPolicy.ASIS)
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                val processor = object : PushDownProcessor<com.intellij.refactoring.util.classMembers.MemberInfo, com.intellij.psi.PsiMember, com.intellij.psi.PsiClass>(sourceSuper, infos, docPolicy) {
                    override fun showConflicts(conflicts: com.intellij.util.containers.MultiMap<com.intellij.psi.PsiElement, String>, usages: Array<UsageInfo>?): Boolean {
                        if (!conflicts.isEmpty) throw AssertionError("Unexpected conflicts: $conflicts")
                        return true
                    }
                    // Filter to only direct subclasses SubA/SubB via overriding findUsages would be default; our super has both as direct, so all will be used.
                }
                processor.setPreviewUsages(false)
                processor.run()
            }
        } finally { TestDialogManager.setTestDialog(prev) }
        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun assertOneGlobalUndoRestores(
        before: Pair<String, Pair<String,String>>,
        superFile: PsiJavaFile,
        subAFile: PsiJavaFile,
        subBFile: PsiJavaFile,
    ) {
        val um = UndoManager.getInstance(project)
        assertTrue(um.isUndoAvailable(null))
        val prev = TestDialogManager.setTestDialog(TestDialog.OK)
        try { um.undo(null) } finally { TestDialogManager.setTestDialog(prev) }
        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(before.first, superFile.text)
        assertEquals(before.second.first, subAFile.text)
        assertEquals(before.second.second, subBFile.text)
    }
}
