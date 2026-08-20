package com.example.airefactoring.refactoring.extractsuperclass

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.extractSuperclass.ExtractSuperClassUtil
import com.intellij.refactoring.util.DocCommentPolicy
import com.intellij.refactoring.util.classMembers.MemberInfo
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class ExtractSuperclassHeadlessSpikeTest : LightJavaCodeInsightFixtureTestCase() {

    fun testExtractSuperclassRunsHeadlesslyWithSamePackage() {
        val file = myFixture.addFileToProject(
            "example/Service.java",
            """
                package example;
                public class Service {
                    public void doIt() {}
                    public void run(String s) {}
                    public static final int COUNT = 1;
                    private void help() {}
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val beforeSource = file.text
        val sourceClass = file.classes.single()
        val doIt = sourceClass.findMethodsByName("doIt", false).single()
        val count = sourceClass.findFieldByName("COUNT", false)!!

        val targetDir = file.containingDirectory!!

        val memberInfos = listOf(doIt, count).map { member ->
            MemberInfo(member).apply {
                isChecked = true
                if (member is com.intellij.psi.PsiMethod) isToAbstract = true
            }
        }.toTypedArray()

        runHeadlessExtract(sourceClass, targetDir, "ServiceSuper", memberInfos)

        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val newClass = JavaPsiFacade.getInstance(project).findClass("example.ServiceSuper", GlobalSearchScope.allScope(project))
        assertNotNull("new superclass example.ServiceSuper must exist", newClass)
        assertTrue("ServiceSuper must be abstract class", newClass!!.hasModifierProperty("abstract"))
        assertTrue(newClass.findMethodsByName("doIt", false).isNotEmpty())
        assertTrue(newClass.findMethodsByName("doIt", false).single().hasModifierProperty("abstract"))
        assertNotNull(newClass.findFieldByName("COUNT", false))

        val afterSource = myFixture.psiManager.findFile(file.virtualFile) as PsiJavaFile
        val afterClass = afterSource.classes.single()
        assertTrue(afterClass.extendsList?.referencedTypes?.any { it.canonicalText == "example.ServiceSuper" || it.canonicalText == "ServiceSuper" } == true)

        assertOneGlobalUndoRestores(beforeSource, file, newClass)
    }

    fun testExtractSuperclassWithExplicitPackage() {
        val file = myFixture.addFileToProject(
            "example/Worker.java",
            """
                package example;
                public class Worker {
                    public void work() {}
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val sourceClass = file.classes.single()
        val work = sourceClass.findMethodsByName("work", false).single()

        myFixture.addFileToProject("example/api/.keep", "")
        val targetDir = JavaPsiFacade.getInstance(project).findPackage("example.api")!!
            .getDirectories(GlobalSearchScope.allScope(project)).first()

        val memberInfos = arrayOf(MemberInfo(work).apply { isChecked = true; isToAbstract = true })

        runHeadlessExtract(sourceClass, targetDir, "WorkerSuper", memberInfos)

        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val newClass = JavaPsiFacade.getInstance(project).findClass("example.api.WorkerSuper", GlobalSearchScope.allScope(project))
        assertNotNull(newClass)
        assertTrue(newClass!!.hasModifierProperty("abstract"))
    }

    private fun runHeadlessExtract(
        sourceClass: com.intellij.psi.PsiClass,
        targetDir: com.intellij.psi.PsiDirectory,
        superclassName: String,
        memberInfos: Array<MemberInfo>,
    ) {
        val throwingDialog = object : TestDialog {
            override fun show(message: String): Int = throw AssertionError("Extract Superclass must not open a dialog: $message")
        }
        val previous = TestDialogManager.setTestDialog(throwingDialog)
        try {
            val project = sourceClass.project
            val docPolicy = DocCommentPolicy(DocCommentPolicy.ASIS)
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                ExtractSuperClassUtil.extractSuperClass(project, targetDir, superclassName, sourceClass, memberInfos, docPolicy)
            }
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun assertOneGlobalUndoRestores(sourceText: String, sourceFile: PsiJavaFile, newSuper: com.intellij.psi.PsiClass) {
        val undoManager = UndoManager.getInstance(project)
        assertTrue("Extract Superclass must be available as one global Undo", undoManager.isUndoAvailable(null))
        val previous = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undoManager.undo(null)
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals("source file must be restored byte-for-byte", sourceText, sourceFile.text)
        val gone = JavaPsiFacade.getInstance(project).findClass(newSuper.qualifiedName!!, GlobalSearchScope.allScope(project))
        assertNull("new superclass must be deleted after Undo", gone)
    }
}
