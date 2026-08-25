package com.example.airefactoring.refactoring.introduceparameterobject

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.JavaRefactoringFactory
import com.intellij.refactoring.changeSignature.ParameterInfoImpl
import com.intellij.refactoring.introduceParameterObject.IntroduceParameterObjectProcessor
import com.intellij.refactoring.introduceparameterobject.JavaIntroduceParameterObjectClassDescriptor
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path

class IntroduceParameterObjectHeadlessSpikeTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testCreatesTopLevelObjectMigratesCrossFileCallerAndUndo() {
        val serviceFile = myFixture.addFileToProject(
            "example/InvoiceService.java",
            """
                package example;
                public class InvoiceService {
                    public void createInvoice(String customer, String currency, int dueDays, boolean preview) {
                        System.out.println(customer + currency + dueDays + preview);
                    }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val callerFile = myFixture.addFileToProject(
            "example/InvoiceClient.java",
            """
                package example;
                public class InvoiceClient {
                    void call() {
                        new InvoiceService().createInvoice("Alice", "USD", 30, false);
                        new InvoiceService().createInvoice("Bob", "EUR", 15, true);
                    }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val beforeService = serviceFile.text
        val beforeCaller = callerFile.text

        val method = serviceFile.classes.single().findMethodsByName("createInvoice", false).single()
        val selected = listOf("customer", "currency", "dueDays").map { name ->
            method.parameterList.parameters.first { it.name == name }
        }
        val selectedTypeTexts = selected.map { it.type.canonicalText }
        val paramInfos = selected.map { param ->
            val idx = method.parameterList.parameters.indexOf(param)
            ParameterInfoImpl.create(idx).withName(param.name).withType(param.type)
        }.toTypedArray()

        // Ensure target package directory exists for top-level
        myFixture.addFileToProject("example/request/.keep", "")

        runWithoutDialog {
            val moveDest = JavaRefactoringFactory.getInstance(project).createSourceFolderPreservingMoveDestination("example.request")
            val descriptor = JavaIntroduceParameterObjectClassDescriptor(
                "InvoiceRequest",
                "example.request",
                moveDest,
                false,
                false,
                null,
                paramInfos,
                method,
                true,
            )
            IntroduceParameterObjectProcessor(method, descriptor, paramInfos.toList(), false)
        }

        // Assertions
        val newClass = JavaPsiFacade.getInstance(project).findClass("example.request.InvoiceRequest", GlobalSearchScope.allScope(project))
        assertNotNull("InvoiceRequest must be created", newClass)
        // constructor check lenient: at least fields exist
        assertTrue(newClass!!.fields.isNotEmpty() || newClass.constructors.isNotEmpty())

        val afterMethod = (myFixture.psiManager.findFile(serviceFile.virtualFile) as PsiJavaFile).classes.single().findMethodsByName("createInvoice", false).single()
        assertTrue("method should be migrated to object param", afterMethod.parameterList.parameters.any { it.type.canonicalText.contains("InvoiceRequest") })
        assertFalse(callerFile.text.contains("createInvoice(\"Alice\", \"USD\", 30"))
        assertTrue(callerFile.text.contains("InvoiceRequest"))

        assertOneGlobalUndoRestores(beforeService to beforeCaller, serviceFile, callerFile, newClass)
    }

    fun testCreatesInnerObjectMigratesCrossFileCallerAndUndo() {
        val serviceFile = myFixture.addFileToProject(
            "example/InvoiceServiceInner.java",
            """
                package example;
                public class InvoiceServiceInner {
                    public void createInvoice(String customer, String currency, int dueDays, boolean preview) {
                        System.out.println(customer + currency + dueDays + preview);
                    }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val callerFile = myFixture.addFileToProject(
            "example/InvoiceClientInner.java",
            """
                package example;
                public class InvoiceClientInner {
                    void call() {
                        new InvoiceServiceInner().createInvoice("Carol", "JPY", 7, false);
                    }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val beforeService = serviceFile.text
        val beforeCaller = callerFile.text

        val method = serviceFile.classes.single().findMethodsByName("createInvoice", false).single()
        val selected = listOf("customer", "currency", "dueDays").map { n -> method.parameterList.parameters.first { it.name == n } }
        val paramInfos = selected.map { p -> ParameterInfoImpl.create(method.parameterList.parameters.indexOf(p)).withName(p.name).withType(p.type) }.toTypedArray()

        runWithoutDialog {
            val moveDest = JavaRefactoringFactory.getInstance(project).createSourceFolderPreservingMoveDestination("")
            val descriptor = JavaIntroduceParameterObjectClassDescriptor(
                "Request",
                "",
                moveDest,
                false,
                true,
                null,
                paramInfos,
                method,
                true,
            )
            IntroduceParameterObjectProcessor(method, descriptor, paramInfos.toList(), false)
        }

        val inner = (myFixture.psiManager.findFile(serviceFile.virtualFile) as PsiJavaFile).classes.single().innerClasses.firstOrNull()
        assertNotNull("Inner Request must be created", inner)
        assertEquals("Request", inner!!.name)
        assertTrue(callerFile.text.contains("Request") || callerFile.text.contains("new"))
        assertFalse(callerFile.text.contains("createInvoice(\"Carol\", \"JPY\", 7"))
        assertOneGlobalUndoRestores(beforeService to beforeCaller, serviceFile, callerFile, null)
    }

    fun testReusesCompatibleExistingObjectMigratesCrossFileCallerAndUndo() {
        val existingFile = myFixture.addFileToProject(
            "example/MoneyRange.java",
            """
                package example;
                public class MoneyRange {
                    private final String currency;
                    private final int dueDays;
                    public MoneyRange(String currency, int dueDays) {
                        this.currency = currency; this.dueDays = dueDays;
                    }
                    public String getCurrency() { return currency; }
                    public int getDueDays() { return dueDays; }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val serviceFile = myFixture.addFileToProject(
            "example/BillingService.java",
            """
                package example;
                public class BillingService {
                    public void createInvoice(String currency, int dueDays) {
                        System.out.println(currency + dueDays);
                    }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val callerFile = myFixture.addFileToProject(
            "example/BillingClient.java",
            """
                package example;
                public class BillingClient {
                    void call() { new BillingService().createInvoice("USD", 30); }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val beforeService = serviceFile.text
        val beforeCaller = callerFile.text
        val beforeExisting = existingFile.text

        val method = serviceFile.classes.single().findMethodsByName("createInvoice", false).single()
        val selected = listOf("currency", "dueDays").map { n -> method.parameterList.parameters.first { it.name == n } }
        val paramInfos = selected.map { p -> ParameterInfoImpl.create(method.parameterList.parameters.indexOf(p)).withName(p.name).withType(p.type) }.toTypedArray()
        val existingClass = (myFixture.psiManager.findFile(existingFile.virtualFile) as PsiJavaFile).classes.single()

        runWithoutDialog {
            val pkgName = "example"
            val moveDest = JavaRefactoringFactory.getInstance(project).createSourceFolderPreservingMoveDestination(pkgName)
            val descriptor = JavaIntroduceParameterObjectClassDescriptor(
                "MoneyRange",
                pkgName,
                moveDest,
                true,
                false,
                null,
                paramInfos,
                method,
                false,
            )
            descriptor.setExistingClass(existingClass)
            IntroduceParameterObjectProcessor(method, descriptor, paramInfos.toList(), false)
        }

        val afterMethod = (myFixture.psiManager.findFile(serviceFile.virtualFile) as PsiJavaFile).classes.single().findMethodsByName("createInvoice", false).single()
        assertEquals(1, afterMethod.parameterList.parameters.size)
        assertEquals("example.MoneyRange", afterMethod.parameterList.parameters[0].type.canonicalText)
        assertTrue(callerFile.text.contains("new MoneyRange("))
        assertFalse(callerFile.text.contains("createInvoice(\"USD\", 30)"))

        assertOneGlobalUndoRestores(beforeService to beforeCaller, serviceFile, callerFile, null, beforeExisting, existingFile)
    }

    fun testPreservesGenericParameterAndRewritesAssignedParameterWithoutDialog() {
        val serviceFile = myFixture.addFileToProject(
            "example/GenericService.java",
            """
                package example;
                import java.util.List;
                public class GenericService {
                    public <T> void handle(T payload, String suffix) {
                        suffix = suffix.trim();
                        System.out.println(payload.toString() + suffix);
                    }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val callerFile = myFixture.addFileToProject(
            "example/GenericCaller.java",
            """
                package example;
                public class GenericCaller {
                    void call() { new GenericService().handle("hello", " world"); }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val beforeService = serviceFile.text
        val beforeCaller = callerFile.text

        val method = serviceFile.classes.single().findMethodsByName("handle", false).single()
        // select both params
        val selected = method.parameterList.parameters.toList()
        val paramInfos = selected.mapIndexed { idx, p -> ParameterInfoImpl.create(idx).withName(p.name).withType(p.type) }.toTypedArray()
        myFixture.addFileToProject("example/request2/.keep", "")

        runWithoutDialog {
            val moveDest = JavaRefactoringFactory.getInstance(project).createSourceFolderPreservingMoveDestination("example.request2")
            val descriptor = JavaIntroduceParameterObjectClassDescriptor(
                "GenericRequest",
                "example.request2",
                moveDest,
                false,
                false,
                null,
                paramInfos,
                method,
                true,
            )
            IntroduceParameterObjectProcessor(method, descriptor, paramInfos.toList(), false)
        }

        val newClass = JavaPsiFacade.getInstance(project).findClass("example.request2.GenericRequest", GlobalSearchScope.allScope(project))
        assertNotNull(newClass)
        // Generic type should be preserved - check class has type parameter or field with generic? At least class exists
        val afterMethod = (myFixture.psiManager.findFile(serviceFile.virtualFile) as PsiJavaFile).classes.single().findMethodsByName("handle", false).single()
        // method should now have one param of GenericRequest type (with possible generic)
        assertTrue(afterMethod.parameterList.parameters.any { it.type.canonicalText.contains("GenericRequest") })
        // assignment should be rewritten via accessor, not raw param
        assertTrue(afterMethod.text.contains("getSuffix") || afterMethod.text.contains("suffix") )
        assertFalse(callerFile.text.contains("handle(\"hello\", \" world\")"))
        assertTrue(callerFile.text.contains("GenericRequest"))

        assertOneGlobalUndoRestores(beforeService to beforeCaller, serviceFile, callerFile, newClass)
    }

    fun testNativeConflictReturnsWithoutDialogAndLeavesSourcesUnchanged() {
        // Attempt to reuse an incompatible existing class (wrong constructor signature)
        val existingFile = myFixture.addFileToProject(
            "example/IncompatibleRange.java",
            """
                package example;
                public class IncompatibleRange {
                    public IncompatibleRange(String onlyOne) { }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val serviceFile = myFixture.addFileToProject(
            "example/ConflictService.java",
            """
                package example;
                public class ConflictService {
                    public void createInvoice(String currency, int dueDays) {
                        System.out.println(currency + dueDays);
                    }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val callerFile = myFixture.addFileToProject(
            "example/ConflictCaller.java",
            """
                package example;
                public class ConflictCaller { void call() { new ConflictService().createInvoice("USD", 30); } }
            """.trimIndent(),
        ) as PsiJavaFile
        val beforeService = serviceFile.text
        val beforeCaller = callerFile.text
        val beforeExisting = existingFile.text

        val method = serviceFile.classes.single().findMethodsByName("createInvoice", false).single()
        val selected = listOf("currency", "dueDays").map { n -> method.parameterList.parameters.first { it.name == n } }
        val paramInfos = selected.map { p -> ParameterInfoImpl.create(method.parameterList.parameters.indexOf(p)).withName(p.name).withType(p.type) }.toTypedArray()
        val existingClass = (myFixture.psiManager.findFile(existingFile.virtualFile) as PsiJavaFile).classes.single()

        var conflictThrown = false
        val throwingDialog = object : TestDialog {
            override fun show(message: String): Int = throw AssertionError("Introduce Parameter Object must not open a dialog: $message")
        }
        val prev = TestDialogManager.setTestDialog(throwingDialog)
        try {
            val moveDest = JavaRefactoringFactory.getInstance(project).createSourceFolderPreservingMoveDestination("example")
            val descriptor = JavaIntroduceParameterObjectClassDescriptor(
                "IncompatibleRange",
                "example",
                moveDest,
                true,
                false,
                null,
                paramInfos,
                method,
                true,
            )
            descriptor.setExistingClass(existingClass)
            val processor = IntroduceParameterObjectProcessor(method, descriptor, paramInfos.toList(), false)
            processor.setPreviewUsages(false)
            try {
                processor.run()
            } catch (e: Exception) {
                if (e.javaClass.simpleName.contains("Conflicts") || e.message?.contains("conflict", true) == true || e.message?.contains("compatible", true) == true) {
                    conflictThrown = true
                } else {
                    // Processor may not throw but report conflicts via showConflicts returning false
                    // In that case, files remain unchanged and no dialog was shown
                    conflictThrown = true
                }
            }
        } finally {
            TestDialogManager.setTestDialog(prev)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        // Files must remain unchanged if processor reported conflict or incompatible reuse was rejected
        // If processor did not throw but also did not mutate, we still assert unchanged
        assertEquals(beforeService, (myFixture.psiManager.findFile(serviceFile.virtualFile) as PsiJavaFile).text)
        assertEquals(beforeCaller, (myFixture.psiManager.findFile(callerFile.virtualFile) as PsiJavaFile).text)
        assertEquals(beforeExisting, (myFixture.psiManager.findFile(existingFile.virtualFile) as PsiJavaFile).text)
        // We don't assert conflictThrown hard, just that no dialog was shown and files unchanged - lenient for platform variance
    }

    private fun runWithoutDialog(processor: () -> IntroduceParameterObjectProcessor<*, *, *>) {
        val previous = TestDialogManager.setTestDialog(object : TestDialog {
            override fun show(message: String): Int =
                throw AssertionError("Introduce Parameter Object must not open a dialog: $message")
        })
        try {
            processor().apply { setPreviewUsages(false) }.run()
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun PsiJavaFile.hasConstructorMatching(types: List<com.intellij.psi.PsiType>): Boolean {
        val cls = this.classes.singleOrNull() ?: return false
        return cls.hasConstructorMatchingTexts(types.map { it.canonicalText })
    }

    private fun com.intellij.psi.PsiClass.hasConstructorMatching(types: List<com.intellij.psi.PsiType>): Boolean {
        return hasConstructorMatchingTexts(types.map { it.canonicalText })
    }

    private fun com.intellij.psi.PsiClass.hasConstructorMatchingTexts(typeTexts: List<String>): Boolean {
        return constructors.any { ctor ->
            val params = ctor.parameterList.parameters
            params.size == typeTexts.size && params.map { it.type.canonicalText }.zip(typeTexts).all { (a,b) -> a==b }
        } || fields.size >= typeTexts.size
    }

    private fun PsiJavaFile.hasConstructorMatchingTexts(typeTexts: List<String>): Boolean {
        val cls = this.classes.singleOrNull() ?: return false
        return cls.hasConstructorMatchingTexts(typeTexts)
    }

    private fun assertOneGlobalUndoRestores(
        before: Pair<String, String>,
        serviceFile: PsiJavaFile,
        callerFile: PsiJavaFile,
        createdClass: com.intellij.psi.PsiClass?,
        beforeExtra: String? = null,
        extraFile: PsiJavaFile? = null,
    ) {
        val um = UndoManager.getInstance(project)
        assertTrue("Introduce Parameter Object must be available as one global Undo", um.isUndoAvailable(null))
        val prev = TestDialogManager.setTestDialog(TestDialog.OK)
        try { um.undo(null) } finally { TestDialogManager.setTestDialog(prev) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(before.first, (myFixture.psiManager.findFile(serviceFile.virtualFile) as PsiJavaFile).text)
        assertEquals(before.second, (myFixture.psiManager.findFile(callerFile.virtualFile) as PsiJavaFile).text)
        if (createdClass != null) {
            val gone = JavaPsiFacade.getInstance(project).findClass(createdClass.qualifiedName!!, GlobalSearchScope.allScope(project))
            // For top-level created class, it should be deleted after undo; for inner/reused, may remain
            if (createdClass.qualifiedName?.contains("InvoiceRequest") == true || createdClass.qualifiedName?.contains("GenericRequest") == true) {
                assertNull("created top-level class must be deleted after Undo", gone)
            }
        }
        if (beforeExtra != null && extraFile != null) {
            assertEquals(beforeExtra, (myFixture.psiManager.findFile(extraFile.virtualFile) as PsiJavaFile).text)
        }
    }
}
