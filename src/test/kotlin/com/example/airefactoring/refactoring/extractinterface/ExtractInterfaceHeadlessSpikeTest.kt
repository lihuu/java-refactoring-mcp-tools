package com.example.airefactoring.refactoring.extractinterface

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.extractInterface.ExtractInterfaceProcessor
import com.intellij.refactoring.util.DocCommentPolicy
import com.intellij.refactoring.util.classMembers.MemberInfo
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class ExtractInterfaceHeadlessSpikeTest : LightJavaCodeInsightFixtureTestCase() {

    fun testExtractInterfaceRunsHeadlesslyWithSamePackage() {
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

        // Use direct handler logic to get correct naming (processor inverts names)
        runHeadlessExtract(sourceClass, targetDir, "ServiceApi", memberInfos)
        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        // New interface should exist in same package
        val newClass = JavaPsiFacade.getInstance(project).findClass("example.ServiceApi", GlobalSearchScope.allScope(project))
        assertNotNull("new interface example.ServiceApi must exist", newClass)
        assertTrue("ServiceApi must be interface", newClass!!.isInterface)
        assertTrue(newClass.findMethodsByName("doIt", false).isNotEmpty())
        assertNotNull(newClass.findFieldByName("COUNT", false))

        // Source class must implements
        val afterSource = myFixture.psiManager.findFile(file.virtualFile) as PsiJavaFile
        val afterClass = afterSource.classes.single()
        assertTrue(afterClass.implementsList?.referencedTypes?.any { it.canonicalText == "example.ServiceApi" || it.canonicalText == "ServiceApi" } == true)

        assertOneGlobalUndoRestores(beforeSource, file, newClass)
    }

    fun testExtractInterfaceWithExplicitPackage() {
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

        // Create target package directory example.api
        myFixture.addFileToProject("example/api/.keep", "")
        val targetDir = JavaPsiFacade.getInstance(project).findPackage("example.api")!!
            .getDirectories(GlobalSearchScope.allScope(project)).first()

        val memberInfos = arrayOf(MemberInfo(work).apply { isChecked = true; isToAbstract = true })

        runHeadlessExtract(sourceClass, targetDir, "WorkerApi", memberInfos)
        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val newClass = JavaPsiFacade.getInstance(project).findClass("example.api.WorkerApi", GlobalSearchScope.allScope(project))
        assertNotNull(newClass)
        assertTrue(newClass!!.isInterface)
    }

    private fun runHeadlessExtract(
        sourceClass: com.intellij.psi.PsiClass,
        targetDir: com.intellij.psi.PsiDirectory,
        interfaceName: String,
        memberInfos: Array<MemberInfo>,
    ) {
        val throwingDialog = object : TestDialog {
            override fun show(message: String): Int = throw AssertionError("Extract Interface must not open a dialog: $message")
        }
        val previous = TestDialogManager.setTestDialog(throwingDialog)
        try {
            val project = sourceClass.project
            val docPolicy = DocCommentPolicy(DocCommentPolicy.ASIS)
            val method = com.intellij.refactoring.extractInterface.ExtractInterfaceHandler::class.java.getDeclaredMethod(
                "extractInterface",
                com.intellij.psi.PsiDirectory::class.java,
                com.intellij.psi.PsiClass::class.java,
                String::class.java,
                Array<MemberInfo>::class.java,
                DocCommentPolicy::class.java,
            )
            method.isAccessible = true
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                method.invoke(null, targetDir, sourceClass, interfaceName, memberInfos, docPolicy)
            }
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun runProcessorAndAssertDialogFree(create: () -> com.intellij.refactoring.BaseRefactoringProcessor) {
        val throwingDialog = object : TestDialog {
            override fun show(message: String): Int = throw AssertionError("Extract Interface must not open a dialog: $message")
        }
        val previous = TestDialogManager.setTestDialog(throwingDialog)
        try {
            val processor = create()
            processor.setPreviewUsages(false)
            processor.run()
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun assertOneGlobalUndoRestores(sourceText: String, sourceFile: PsiJavaFile, newInterface: com.intellij.psi.PsiClass) {
        val undoManager = UndoManager.getInstance(project)
        assertTrue("Extract Interface must be available as one global Undo", undoManager.isUndoAvailable(null))
        val previous = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undoManager.undo(null)
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals("source file must be restored byte-for-byte", sourceText, sourceFile.text)
        val gone = JavaPsiFacade.getInstance(project).findClass(newInterface.qualifiedName!!, GlobalSearchScope.allScope(project))
        assertNull("new interface must be deleted after Undo", gone)
    }
}
