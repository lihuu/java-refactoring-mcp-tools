package com.example.airefactoring.refactoring.makestatic

import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.dispatchAllEventsInIdeEventQueue
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IntellijJavaMakeStaticExecutorTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = JavaMakeStaticSelectionResolver()
    private val executor = IntellijJavaMakeStaticExecutor()

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testMakesParameterizedMethodStaticWithFieldOrdering() {
        val orderFile = mirrorFixture(
            "example/Order.java",
            """
                package example;

                public class Order {
                    private int amount;
                    private int rate;

                    public Order(int amount, int rate) {
                        this.amount = amount;
                        this.rate = rate;
                    }

                    public int applyDiscount() {
                        return amount - rate;
                    }
                }
            """.trimIndent(),
        )
        val preparation = resolvePreparation(
            "example/Order.java",
            "applyDiscount",
            classParameterName = null,
            fieldParameters = listOf(
                fieldParameter("example/Order.java", "amount", "a"),
                fieldParameter("example/Order.java", "rate", "r"),
            ),
            replaceUsages = true,
        )

        val result = runExecutor { executor.makeStatic(project, preparation) }

        assertEquals("applyDiscount", result.memberName)
        assertEquals(JavaMakeStaticMemberKind.METHOD, result.memberKind)
        assertTrue(result.replaceUsages)
        assertNull(result.classParameterName)
        assertEquals(listOf("a", "r"), result.fieldParameterNames)
        assertFalse(result.generateDelegate)
        assertTrue(result.nativeUsageCount >= 0)
        assertEquals(listOf("example/Order.java"), result.affectedFiles)
        assertTrue(result.summary.contains("applyDiscount"))

        myFixture.psiManager.dropResolveCaches()
        val orderAfter = PsiManager.getInstance(project)
            .findFile(orderFile.virtualFile) as PsiJavaFile
        val converted = orderAfter.classes.single().findMethodsByName("applyDiscount", false).single()
        assertTrue(
            "method must become static:\n${converted.text}",
            converted.hasModifierProperty(PsiModifier.STATIC),
        )
        assertEquals(listOf("a", "r"), converted.parameterList.parameters.map { it.name })
        assertTrue("body must use the parameter names", converted.text.contains("a - r"))
    }

    fun testMakesMethodStaticWithClassParameterAndUpdatesCaller() {
        val invoiceFile = mirrorFixture(
            "example/Invoice.java",
            """
                package example;

                public class Invoice {
                    private int amount;

                    public Invoice(int amount) {
                        this.amount = amount;
                    }

                    public int applyDiscount() {
                        return amount;
                    }
                }
            """.trimIndent(),
        )
        val callerFile = mirrorFixture(
            "example/Checkout.java",
            """
                package example;

                public class Checkout {
                    public int charge() {
                        Invoice invoice = new Invoice(100);
                        return invoice.applyDiscount();
                    }
                }
            """.trimIndent(),
        )
        val preparation = resolvePreparation(
            "example/Invoice.java",
            "applyDiscount",
            classParameterName = "invoice",
            fieldParameters = emptyList(),
            replaceUsages = true,
        )

        val result = runExecutor { executor.makeStatic(project, preparation) }

        assertEquals("invoice", result.classParameterName)
        assertEquals(listOf<String>(), result.fieldParameterNames)
        assertTrue(result.nativeUsageCount >= 1)
        assertEquals(
            listOf("example/Checkout.java", "example/Invoice.java"),
            result.affectedFiles,
        )

        myFixture.psiManager.dropResolveCaches()
        val invoiceAfter = PsiManager.getInstance(project)
            .findFile(invoiceFile.virtualFile) as PsiJavaFile
        val converted = invoiceAfter.classes.single().findMethodsByName("applyDiscount", false).single()
        assertTrue(converted.hasModifierProperty(PsiModifier.STATIC))
        assertEquals(1, converted.parameterList.parametersCount)
        assertEquals("example.Invoice", converted.parameterList.parameters[0].type.canonicalText)

        val callerAfter = PsiManager.getInstance(project)
            .findFile(callerFile.virtualFile) as PsiJavaFile
        val call = PsiTreeUtil.findChildOfType(callerAfter, PsiMethodCallExpression::class.java)
        assertNotNull("cross-file call disappeared:\n${callerAfter.text}", call)
        assertTrue(
            "caller must now invoke statically with the receiver as argument:\n${callerAfter.text}",
            call!!.text == "Invoice.applyDiscount(invoice)",
        )

        val persistedInvoice = Files.readString(Path.of(project.basePath!!, "example/Invoice.java"))
        val persistedCaller = Files.readString(Path.of(project.basePath!!, "example/Checkout.java"))
        assertTrue("native target edits must be persisted", persistedInvoice.contains("static int applyDiscount"))
        assertTrue("native usage edits must be persisted", persistedCaller.contains("Invoice.applyDiscount(invoice)"))
    }

    fun testMakesInnerClassStatic() {
        val reportFile = mirrorFixture(
            "example/Report.java",
            """
                package example;

                public class Report {
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
        )
        val preparation = resolvePreparation(
            "example/Report.java",
            "Discount",
            classParameterName = null,
            fieldParameters = emptyList(),
            replaceUsages = false,
        )

        val result = runExecutor { executor.makeStatic(project, preparation) }

        assertEquals("Discount", result.memberName)
        assertEquals(JavaMakeStaticMemberKind.CLASS, result.memberKind)
        assertFalse(result.replaceUsages)
        assertEquals(listOf("example/Report.java"), result.affectedFiles)

        myFixture.psiManager.dropResolveCaches()
        val reportAfter = PsiManager.getInstance(project)
            .findFile(reportFile.virtualFile) as PsiJavaFile
        val converted = reportAfter.classes.single().innerClasses.single { it.name == "Discount" }
        assertTrue(
            "inner class must become static:\n${converted.text}",
            converted.hasModifierProperty(PsiModifier.STATIC),
        )
    }

    fun testNativeConflictThrowsWithoutMutationOrDialog() {
        val invoiceFile = mirrorFixture(
            "example/ConflictTarget.java",
            """
                package example;

                public class ConflictTarget {
                    private int amount;

                    public int applyDiscount() {
                        return amount;
                    }
                }
            """.trimIndent(),
        )
        val before = invoiceFile.text
        val preparation = resolvePreparation(
            "example/ConflictTarget.java",
            "applyDiscount",
            classParameterName = null,
            fieldParameters = emptyList(),
            replaceUsages = true,
        )

        try {
            runExecutor { makeStaticExecutor(project, preparation) }
            fail("expected a native conflict for an instance-field reference not passed as a parameter")
        } catch (expected: JavaMakeStaticConflictException) {
            assertTrue(expected.message!!.isNotBlank())
        }

        assertEquals("source must be unchanged on conflict", before, invoiceFile.text)
    }

    fun testOneUndoRestoresAllChangedFiles() {
        val invoiceFile = mirrorFixture(
            "example/Invoice.java",
            """
                package example;

                public class Invoice {
                    private int amount;

                    public Invoice(int amount) {
                        this.amount = amount;
                    }

                    public int applyDiscount() {
                        return amount;
                    }
                }
            """.trimIndent(),
        )
        val callerFile = mirrorFixture(
            "example/Checkout.java",
            """
                package example;

                public class Checkout {
                    public int charge() {
                        Invoice invoice = new Invoice(100);
                        return invoice.applyDiscount();
                    }
                }
            """.trimIndent(),
        )
        val invoiceText = invoiceFile.text
        val callerText = callerFile.text
        val preparation = resolvePreparation(
            "example/Invoice.java",
            "applyDiscount",
            classParameterName = "invoice",
            fieldParameters = emptyList(),
            replaceUsages = true,
        )

        runExecutor { makeStaticExecutor(project, preparation) }

        val undoManager = UndoManager.getInstance(project)
        assertTrue("Make Static must be one global Undo", undoManager.isUndoAvailable(null))
        val previous = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undoManager.undo(null)
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals("member file must be restored byte-for-byte", invoiceText, invoiceFile.text)
        assertEquals("caller file must be restored byte-for-byte", callerText, callerFile.text)
    }

    fun testStaleMemberThrowsPreparationExceptionWithoutMutation() {
        val staleMemberFile = mirrorFixture(
            "example/StaleMember.java",
            """
                package example;

                public class StaleMember {
                    private int amount;

                    public StaleMember(int amount) {
                        this.amount = amount;
                    }

                    public int applyDiscount() {
                        return amount;
                    }
                }
            """.trimIndent(),
        )
        val preparation = resolvePreparation(
            "example/StaleMember.java",
            "applyDiscount",
            classParameterName = "stale",
            fieldParameters = emptyList(),
            replaceUsages = true,
        )
        val document = documentOf(staleMemberFile)
        WriteCommandAction.runWriteCommandAction(project) {
            val start = document.text.indexOf("applyDiscount")
            document.replaceString(start, start + "applyDiscount".length, "renamedDiscount")
        }
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val afterEditText = staleMemberFile.text

        try {
            runExecutor { makeStaticExecutor(project, preparation) }
            fail("expected a stale-preparation rejection")
        } catch (expected: JavaMakeStaticPreparationException) {
            assertTrue(expected.message!!.isNotBlank())
        }

        assertEquals("member file must be unchanged by the executor", afterEditText, staleMemberFile.text)
    }

    fun testStaleFieldThrowsPreparationExceptionWithoutMutation() {
        val staleFieldFile = mirrorFixture(
            "example/StaleField.java",
            """
                package example;

                public class StaleField {
                    private int amount;
                    private int rate;

                    public StaleField(int amount, int rate) {
                        this.amount = amount;
                        this.rate = rate;
                    }

                    public int applyDiscount() {
                        return amount - rate;
                    }
                }
            """.trimIndent(),
        )
        val preparation = resolvePreparation(
            "example/StaleField.java",
            "applyDiscount",
            classParameterName = null,
            fieldParameters = listOf(fieldParameter("example/StaleField.java", "amount", "a")),
            replaceUsages = true,
        )
        val document = documentOf(staleFieldFile)
        WriteCommandAction.runWriteCommandAction(project) {
            val start = document.text.indexOf("private int amount")
            document.replaceString(start, start + "private int amount".length, "private int renamed")
        }
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val afterEditText = staleFieldFile.text

        try {
            runExecutor { makeStaticExecutor(project, preparation) }
            fail("expected a stale field-preparation rejection")
        } catch (expected: JavaMakeStaticPreparationException) {
            assertTrue(expected.message!!.isNotBlank())
        }

        assertEquals("member file must be unchanged by the executor", afterEditText, staleFieldFile.text)
    }

    fun testRejectsFieldWithDifferentOwnerEvenWhenTextAndTypeMatch() {
        val file = mirrorFixture(
            "example/FieldOwner.java",
            """
                package example;

                public class FieldOwner {
                    private int amount;

                    public int applyDiscount() {
                        return amount;
                    }
                }

                class OtherOwner {
                    private int amount;
                }
            """.trimIndent(),
        )
        val preparation = resolvePreparation(
            "example/FieldOwner.java",
            "applyDiscount",
            classParameterName = null,
            fieldParameters = listOf(fieldParameter("example/FieldOwner.java", "amount", "a")),
            replaceUsages = true,
        )
        val otherField = file.classes.single { it.name == "OtherOwner" }.fields.single()
        val mismatchedPreparation = preparation.copy(
            fieldPointers = listOf(SmartPointerManager.getInstance(project).createSmartPsiElementPointer(otherField)),
            fieldTextSnapshots = listOf(otherField.text),
            fieldTypeSnapshots = listOf(otherField.type.canonicalText),
        )
        val before = file.text

        assertPreparationRejected(mismatchedPreparation)

        assertEquals("executor must not mutate a field-owner mismatch", before, file.text)
    }

    fun testRejectsSelectedFieldThatBecameStaticEvenWhenSnapshotsMatch() {
        val file = mirrorFixture(
            "example/StaticField.java",
            """
                package example;

                public class StaticField {
                    private int selected;
                    private static int amount;

                    public int applyDiscount() {
                        return selected;
                    }
                }
            """.trimIndent(),
        )
        val preparation = resolvePreparation(
            "example/StaticField.java",
            "applyDiscount",
            classParameterName = null,
            fieldParameters = listOf(fieldParameter("example/StaticField.java", "selected", "selected")),
            replaceUsages = true,
        )
        val staticField = file.classes.single().findFieldByName("amount", false)!!
        val mismatchedPreparation = preparation.copy(
            fieldPointers = listOf(SmartPointerManager.getInstance(project).createSmartPsiElementPointer(staticField)),
            fieldTextSnapshots = listOf(staticField.text),
            fieldTypeSnapshots = listOf(staticField.type.canonicalText),
        )
        val before = file.text

        assertPreparationRejected(mismatchedPreparation)

        assertEquals("executor must not mutate a static-field mismatch", before, file.text)
    }

    fun testRejectsFieldWhoseResolvedTypeChangedWithoutDeclarationTextChange() {
        mirrorFixture(
            "example/first/Value.java",
            """
                package example.first;

                public final class Value {
                }
            """.trimIndent(),
        )
        mirrorFixture(
            "example/second/Value.java",
            """
                package example.second;

                public final class Value {
                }
            """.trimIndent(),
        )
        val file = mirrorFixture(
            "example/TypeDrift.java",
            """
                package example;

                import example.first.Value;

                public class TypeDrift {
                    private Value amount;

                    public int applyDiscount() {
                        return 0;
                    }
                }
            """.trimIndent(),
        )
        val preparation = resolvePreparation(
            "example/TypeDrift.java",
            "applyDiscount",
            classParameterName = null,
            fieldParameters = listOf(fieldParameter("example/TypeDrift.java", "amount", "amount")),
            replaceUsages = true,
        )
        val document = documentOf(file)
        WriteCommandAction.runWriteCommandAction(project) {
            val oldImport = "import example.first.Value;"
            val start = document.text.indexOf(oldImport)
            document.replaceString(start, start + oldImport.length, "import example.second.Value;")
        }
        PsiDocumentManager.getInstance(project).commitDocument(document)
        myFixture.psiManager.dropResolveCaches()
        val afterImportChange = file.text

        assertPreparationRejected(preparation)

        assertEquals("executor must not mutate a type-drift mismatch", afterImportChange, file.text)
    }

    // --- helpers ---

    private fun assertPreparationRejected(preparation: JavaMakeStaticPreparation) {
        try {
            runExecutor { makeStaticExecutor(project, preparation) }
            fail("expected a stale-preparation rejection")
        } catch (expected: JavaMakeStaticPreparationException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }

    private fun resolvePreparation(
        path: String,
        memberNeedle: String,
        classParameterName: String?,
        fieldParameters: List<JavaMakeStaticFieldParameter>,
        replaceUsages: Boolean,
    ): JavaMakeStaticPreparation {
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val result = resolver.resolve(
            project = project,
            pathInProject = path,
            memberRange = rangeOf(path, memberNeedle),
            replaceUsages = replaceUsages,
            classParameterName = classParameterName,
            fieldParameters = fieldParameters,
            generateDelegate = false,
        )
        assertTrue(
            "expected successful preparation but was $result",
            result is JavaMakeStaticSelectionResolution.Success,
        )
        return (result as JavaMakeStaticSelectionResolution.Success).preparation
    }

    private fun fieldParameter(path: String, needle: String, name: String): JavaMakeStaticFieldParameter {
        val range = rangeOf(path, needle)
        return JavaMakeStaticFieldParameter(
            startLine = range.startLine,
            startColumn = range.startColumn,
            endLine = range.endLine,
            endColumn = range.endColumn,
            parameterName = name,
        )
    }

    private fun rangeOf(path: String, needle: String): SourceRange {
        val virtualFile = LocalFileSystem.getInstance()
            .findFileByPath(Path.of(project.basePath!!, path).toString())!!
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val offset = document.text.indexOf(needle)
        assertTrue("'$needle' missing from $path", offset >= 0)
        val endOffset = offset + needle.length
        val startLine = document.getLineNumber(offset)
        val endLine = document.getLineNumber(endOffset - 1)
        return SourceRange(
            startLine = startLine + 1,
            startColumn = offset - document.getLineStartOffset(startLine) + 1,
            endLine = endLine + 1,
            endColumn = endOffset - document.getLineStartOffset(endLine) + 1,
        )
    }

    private fun documentOf(file: PsiJavaFile): Document =
        PsiDocumentManager.getInstance(project).getDocument(file)!!

    private fun mirrorFixture(path: String, text: String): PsiJavaFile {
        val target = Path.of(project.basePath!!, path)
        Files.createDirectories(target.parent)
        if (!Files.exists(target)) Files.createFile(target)
        val virtualFile = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(target.toString())!!
        WriteAction.run<RuntimeException> { VfsUtil.saveText(virtualFile, text) }
        return PsiManager.getInstance(project).findFile(virtualFile) as PsiJavaFile
    }

    /** Runs the executor on the EDT and asserts no dialog is ever opened. */
    private suspend fun makeStaticExecutor(
        project: com.intellij.openapi.project.Project,
        preparation: JavaMakeStaticPreparation,
    ): JavaMakeStaticExecutionResult {
        val throwingDialog = object : TestDialog {
            override fun show(message: String): Int =
                throw AssertionError("Java Make Static must not open a dialog: $message")
        }
        val previousDialog = TestDialogManager.setTestDialog(throwingDialog)
        try {
            return executor.makeStatic(project, preparation)
        } finally {
            TestDialogManager.setTestDialog(previousDialog)
        }
    }

    private fun <T> runExecutor(block: suspend () -> T): T {
        val pool = Executors.newSingleThreadExecutor()
        return try {
            val future = pool.submit<T> { runBlocking { block() } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (System.nanoTime() < deadline && !future.isDone) {
                dispatchAllEventsInIdeEventQueue()
                Thread.sleep(1)
            }
            try {
                future.get(1, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
