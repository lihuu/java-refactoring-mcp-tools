package com.example.airefactoring.refactoring.moveinstancemethod

import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
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

class IntellijMoveInstanceMethodExecutorTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = MoveInstanceMethodSelectionResolver()
    private val executor = IntellijMoveInstanceMethodExecutor()

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testMovesMethodToParameterTargetUpdatesCallerAndReportsNativeFacts() {
        val (invoiceFile, callerFile) = parameterFixture()
        val preparation = prepareMove(invoiceFile, "applyDiscount", "customer", "public")

        val result = runExecutor { executor.move(project, preparation) }

        assertEquals("applyDiscount", result.methodName)
        assertEquals("parameter customer of type example.Invoice.Customer", result.targetDescription)
        assertEquals("example.Invoice.Customer", result.targetClassQualifiedName)
        assertEquals("public", result.newVisibility)
        assertEquals(1, result.updatedCallSiteCount)
        assertEquals(listOf("example/Checkout.java", "example/Invoice.java"), result.affectedFiles)
        assertTrue(result.summary.contains("applyDiscount"))
        assertTrue(result.summary.contains("1"))

        // The native move rebuilds the affected PSI trees; re-fetch them after mutation.
        myFixture.psiManager.dropResolveCaches()
        val invoiceAfter = PsiManager.getInstance(project)
            .findFile(invoiceFile.virtualFile) as PsiJavaFile
        val callerAfter = PsiManager.getInstance(project)
            .findFile(callerFile.virtualFile) as PsiJavaFile

        val invoiceClass = invoiceAfter.classes.single()
        assertTrue(
            "source Invoice must lose applyDiscount:\n${invoiceAfter.text}",
            invoiceClass.findMethodsByName("applyDiscount", false).isEmpty(),
        )
        val customerClass = invoiceClass.innerClasses.single { it.name == "Customer" }
        val moved = customerClass.findMethodsByName("applyDiscount", false).single()
        assertEquals(1, moved.parameterList.parametersCount)
        assertEquals("example.Invoice", moved.parameterList.parameters[0].type.canonicalText)
        assertTrue("moved method must read the old owner's amount", moved.text.contains("amount"))

        val call = PsiTreeUtil.findChildOfType(callerAfter, PsiMethodCallExpression::class.java)
        assertNotNull("cross-file call disappeared:\n${callerAfter.text}", call)
        assertTrue(
            "caller must invoke on customer with invoice as old-owner argument:\n${callerAfter.text}",
            call!!.text == "customer.applyDiscount(invoice)",
        )
    }

    fun testAffectedFilesIncludesSeparateDestinationClassFile() {
        val (invoiceFile, customerFile, callerFile) = crossFileTargetFixture()
        val preparation = prepareMove(invoiceFile, "applyDiscount", "customer", "public")

        val result = runExecutor { executor.move(project, preparation) }

        assertEquals(1, result.updatedCallSiteCount)
        assertEquals(
            listOf("example/Checkout.java", "example/Customer.java", "example/Invoice.java"),
            result.affectedFiles,
        )
    }

    fun testOneUndoRestoresBothFiles() {
        val (invoiceFile, callerFile) = parameterFixture()
        val preparation = prepareMove(invoiceFile, "applyDiscount", "customer", "public")
        val invoiceText = invoiceFile.text
        val callerText = callerFile.text

        runExecutor { moveExecutor(project, preparation) }

        val undoManager = UndoManager.getInstance(project)
        assertTrue("Move Instance Method must be one global Undo", undoManager.isUndoAvailable(null))
        val previous = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undoManager.undo(null)
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals("source file must be restored byte-for-byte", invoiceText, invoiceFile.text)
        assertEquals("caller file must be restored byte-for-byte", callerText, callerFile.text)
    }

    fun testCollisionConflictThrowsWithoutMutationOrDialog() {
        val (invoiceFile, callerFile) = collisionFixture()
        val invoiceText = invoiceFile.text
        val callerText = callerFile.text
        val preparation = prepareMove(invoiceFile, "applyDiscount", "customer", "public")

        try {
            runExecutor { moveExecutor(project, preparation) }
            fail("expected a native conflict for a method-name collision in the target class")
        } catch (expected: MoveInstanceMethodConflictException) {
            assertTrue(expected.message!!.isNotBlank())
        }

        assertEquals(invoiceText, invoiceFile.text)
        assertEquals(callerText, callerFile.text)
    }

    fun testStaleMethodThrowsPreparationExceptionWithoutMutation() {
        val (invoiceFile, callerFile) = parameterFixture()
        val preparation = prepareMove(invoiceFile, "applyDiscount", "customer", "public")
        val callerText = callerFile.text
        val document = documentOf(invoiceFile)
        WriteCommandAction.runWriteCommandAction(project) {
            val start = document.text.indexOf("applyDiscount(Customer customer)")
            document.replaceString(
                start,
                start + "applyDiscount(Customer customer)".length,
                "applyDiscount(Customer changedTarget)",
            )
        }
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val afterEditText = invoiceFile.text

        try {
            runExecutor { moveExecutor(project, preparation) }
            fail("expected a stale-preparation rejection")
        } catch (expected: MoveInstanceMethodPreparationException) {
            assertTrue(expected.message!!.isNotBlank())
        }

        assertEquals("method file must be unchanged by the executor", afterEditText, invoiceFile.text)
        assertEquals("caller must be unchanged", callerText, callerFile.text)
    }

    private fun parameterFixture(): Pair<PsiJavaFile, PsiJavaFile> = mirrorFixture(
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
    )

    private fun collisionFixture(): Pair<PsiJavaFile, PsiJavaFile> = mirrorFixture(
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

                    public int applyDiscount(Invoice invoice) {
                        return discountRate;
                    }
                }
            }
        """.trimIndent(),
    )

    /**
     * Builds a fixture whose move destination is a top-level class declared in a separate file
     * ([Customer.java]), so the destination target-class file is distinct from the source and is not
     * a usage file.
     */
    private fun crossFileTargetFixture(): Triple<PsiJavaFile, PsiJavaFile, PsiJavaFile> {
        val invoice = mirrorRealFile(
            "example/Invoice.java",
            """
                package example;

                public class Invoice {
                    private final int amount;

                    public Invoice(int amount) {
                        this.amount = amount;
                    }

                    public int getAmount() {
                        return amount;
                    }

                    public int applyDiscount(Customer customer) {
                        return getAmount() - customer.discount();
                    }
                }
            """.trimIndent(),
        )
        val customer = mirrorRealFile(
            "example/Customer.java",
            """
                package example;

                public class Customer {
                    private final int discountRate;

                    public Customer(int discountRate) {
                        this.discountRate = discountRate;
                    }

                    public int discount() {
                        return discountRate;
                    }
                }
            """.trimIndent(),
        )
        val caller = mirrorRealFile(
            "example/Checkout.java",
            """
                package example;

                public class Checkout {
                    public int charge() {
                        Invoice invoice = new Invoice(100);
                        Customer customer = new Customer(10);
                        return invoice.applyDiscount(customer);
                    }
                }
            """.trimIndent(),
        )
        return Triple(
            PsiManager.getInstance(project).findFile(invoice) as PsiJavaFile,
            PsiManager.getInstance(project).findFile(customer) as PsiJavaFile,
            PsiManager.getInstance(project).findFile(caller) as PsiJavaFile,
        )
    }

    private fun mirrorFixture(invoiceText: String): Pair<PsiJavaFile, PsiJavaFile> {
        val invoice = mirrorRealFile("example/Invoice.java", invoiceText)
        val caller = mirrorRealFile(
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
        )
        return PsiManager.getInstance(project).findFile(invoice) as PsiJavaFile to
            (PsiManager.getInstance(project).findFile(caller) as PsiJavaFile)
    }

    private fun prepareMove(
        invoiceFile: PsiJavaFile,
        methodNeedle: String,
        targetNeedle: String,
        visibility: String,
    ): MoveInstanceMethodPreparation {
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val result = resolver.resolve(
            project = project,
            pathInProject = "example/Invoice.java",
            methodRange = rangeOf("example/Invoice.java", methodNeedle),
            targetRange = rangeOf("example/Invoice.java", targetNeedle),
            newVisibility = visibility,
        )
        assertTrue(
            "expected successful preparation but was $result",
            result is MoveInstanceMethodSelectionResolution.Success,
        )
        return (result as MoveInstanceMethodSelectionResolution.Success).preparation
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

    private fun mirrorRealFile(path: String, text: String): com.intellij.openapi.vfs.VirtualFile {
        val target = Path.of(project.basePath!!, path)
        Files.createDirectories(target.parent)
        if (!Files.exists(target)) Files.createFile(target)
        val virtualFile = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(target.toString())!!
        com.intellij.openapi.application.WriteAction.run<RuntimeException> {
            com.intellij.openapi.vfs.VfsUtil.saveText(virtualFile, text)
        }
        return virtualFile
    }

    /** Runs the executor on the EDT and asserts no dialog is ever opened. */
    private suspend fun moveExecutor(
        project: com.intellij.openapi.project.Project,
        preparation: MoveInstanceMethodPreparation,
    ): MoveInstanceMethodExecutionResult {
        val throwingDialog = object : TestDialog {
            override fun show(message: String): Int =
                throw AssertionError("Move Instance Method must not open a dialog: $message")
        }
        val previousDialog = TestDialogManager.setTestDialog(throwingDialog)
        try {
            return executor.move(project, preparation)
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
