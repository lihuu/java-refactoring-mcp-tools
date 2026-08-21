package com.example.airefactoring.refactoring.moveinstancemethod

import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path

class MoveInstanceMethodSelectionResolverTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = MoveInstanceMethodSelectionResolver()

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testResolvesExactParameterTargetAndProducesPointerFacts() {
        parameterFixture()
        val result = resolver.resolve(
            project = project,
            pathInProject = "example/Order.java",
            methodRange = rangeOf("example/Order.java", "applyDiscount"),
            targetRange = rangeOf("example/Order.java", "customer"),
            newVisibility = "public",
        )

        assertTrue(
            "expected successful preparation but was $result",
            result is MoveInstanceMethodSelectionResolution.Success,
        )
        val preparation = (result as MoveInstanceMethodSelectionResolution.Success).preparation
        assertEquals("applyDiscount", preparation.methodName)
        assertEquals("example/Order.java", preparation.pathInProject)
        assertEquals("example.Customer", preparation.targetClassQualifiedName)
        assertEquals("public", preparation.newVisibility)
        assertEquals("example.Customer", preparation.targetTypeSnapshot)
        assertTrue(
            "method text snapshot must carry the selected method",
            preparation.methodTextSnapshot.contains("applyDiscount"),
        )
        assertEquals("parameter customer of type example.Customer", preparation.targetDescription)
        // Both smart pointers must re-dereference to the exact declarations.
        assertEquals("applyDiscount", preparation.methodPointer.element?.name)
        assertEquals("customer", preparation.targetPointer.element?.name)
    }

    fun testAcceptsPackageLocalVisibility() {
        parameterFixture()
        val result = resolver.resolve(
            project = project,
            pathInProject = "example/Order.java",
            methodRange = rangeOf("example/Order.java", "applyDiscount"),
            targetRange = rangeOf("example/Order.java", "customer"),
            newVisibility = "",
        )

        assertTrue(
            "expected success for package-local visibility but was $result",
            result is MoveInstanceMethodSelectionResolution.Success,
        )
        assertEquals(
            "",
            (result as MoveInstanceMethodSelectionResolution.Success).preparation.newVisibility,
        )
    }

    fun testRejectsPartialMethodRange() {
        parameterFixture()
        assertFailure(
            "example/Order.java",
            "applyDiscount(Customer",
            "customer",
            "public",
            "INVALID_RANGE",
        )
    }

    fun testRejectsBodyRange() {
        parameterFixture()
        assertFailure(
            "example/Order.java",
            "return this.amount",
            "customer",
            "public",
            "INVALID_RANGE",
        )
    }

    fun testRejectsLocalVariableTarget() {
        mirrorRealFile(
            "example/Local.java",
            "class Local { void run() { int temp = 5; } }",
        )
        assertFailure(
            "example/Local.java",
            "run",
            "temp",
            "public",
            "UNSUPPORTED_TARGET",
        )
    }

    fun testRejectsStaticFieldTarget() {
        mirrorRealFile(
            "example/StaticTarget.java",
            "class StaticTarget { static Customer customer; void run() {} }",
        )
        assertFailure(
            "example/StaticTarget.java",
            "run",
            "customer",
            "public",
            "UNSUPPORTED_TARGET",
        )
    }

    fun testRejectsUnrelatedInstanceFieldTarget() {
        mirrorRealFile(
            "example/Unrelated.java",
            "class Unrelated { Customer customer; void run() {} }",
        )
        assertFailure(
            "example/Unrelated.java",
            "run",
            "customer",
            "public",
            "UNSUPPORTED_TARGET",
        )
    }

    fun testRejectsPrimitiveParameterTarget() {
        mirrorRealFile(
            "example/Primitive.java",
            "class Primitive { void apply(int count) {} }",
        )
        assertFailure(
            "example/Primitive.java",
            "apply",
            "count",
            "public",
            "UNSUPPORTED_TARGET",
        )
    }

    fun testRejectsConstructor() {
        parameterFixture()
        val result = resolver.resolve(
            project = project,
            pathInProject = "example/Order.java",
            methodRange = constructorRangeOf("example/Order.java", "Order"),
            targetRange = rangeOf("example/Order.java", "amount"),
            newVisibility = "public",
        )
        assertTrue(
            "expected UNSUPPORTED_METHOD for a constructor but was $result",
            result is MoveInstanceMethodSelectionResolution.Failure,
        )
        val failure = result as MoveInstanceMethodSelectionResolution.Failure
        assertEquals("UNSUPPORTED_METHOD", failure.code.name)
        assertTrue(failure.message.isNotBlank())
    }

    fun testRejectsStaticMethod() {
        mirrorRealFile(
            "example/StaticMethod.java",
            """
                package example;

                public class StaticMethod {
                    public static double apply(Customer customer) {
                        return customer.rate();
                    }
                }
            """.trimIndent(),
        )
        mirrorRealFile(
            "example/Customer.java",
            """
                package example;

                public class Customer {
                    public double rate() {
                        return 0;
                    }
                }
            """.trimIndent(),
        )
        assertFailure(
            "example/StaticMethod.java",
            "apply",
            "customer",
            "public",
            "UNSUPPORTED_METHOD",
        )
    }

    fun testRejectsInvalidVisibility() {
        parameterFixture()
        assertFailure(
            "example/Order.java",
            "applyDiscount",
            "customer",
            "protected-ish",
            "INVALID_VISIBILITY",
        )
    }

    fun testLocalVariableTargetFailureStatesParameterOnlyRule() {
        mirrorRealFile(
            "example/Local.java",
            "class Local { void run() { int temp = 5; } }",
        )
        val result = resolver.resolve(
            project = project,
            pathInProject = "example/Local.java",
            methodRange = rangeOf("example/Local.java", "run"),
            targetRange = rangeOf("example/Local.java", "temp"),
            newVisibility = "public",
        )
        assertTrue("expected failure but was $result", result is MoveInstanceMethodSelectionResolution.Failure)
        val failure = result as MoveInstanceMethodSelectionResolution.Failure
        assertEquals("UNSUPPORTED_TARGET", failure.code.name)
        assertTrue(
            "message must state the full parameter-only contract but was: ${failure.message}",
            failure.message.contains("fields and local variables are rejected"),
        )
    }

    fun testFieldTargetFailureCarriesFullContractGuidance() {
        mirrorRealFile(
            "example/Unrelated.java",
            "class Unrelated { Customer customer; void run() {} }",
        )
        mirrorRealFile(
            "example/Customer.java",
            "class Customer { double rate() { return 0; } }",
        )
        val result = resolver.resolve(
            project = project,
            pathInProject = "example/Unrelated.java",
            methodRange = rangeOf("example/Unrelated.java", "run"),
            targetRange = rangeOf("example/Unrelated.java", "customer"),
            newVisibility = "public",
        )
        assertTrue("expected failure but was $result", result is MoveInstanceMethodSelectionResolution.Failure)
        val failure = result as MoveInstanceMethodSelectionResolution.Failure
        assertEquals("UNSUPPORTED_TARGET", failure.code.name)
        assertTrue(
            "message must reject fields and locals explicitly but was: ${failure.message}",
            failure.message.contains("fields and local variables are rejected"),
        )
    }

    private fun parameterFixture() {
        mirrorRealFile(
            "example/Order.java",
            """
                package example;

                public class Order {
                    private final double amount;

                    public Order(double amount) {
                        this.amount = amount;
                    }

                    public double applyDiscount(Customer customer) {
                        return this.amount - (1 - customer.rate());
                    }
                }
            """.trimIndent(),
        )
        mirrorRealFile(
            "example/Customer.java",
            """
                package example;

                public class Customer {
                    private final double rate;

                    public Customer(double rate) {
                        this.rate = rate;
                    }

                    public double rate() {
                        return rate;
                    }
                }
            """.trimIndent(),
        )
    }

    private fun assertFailure(
        path: String,
        methodNeedle: String,
        targetNeedle: String,
        visibility: String,
        expectedCode: String,
    ) {
        val result = resolver.resolve(
            project = project,
            pathInProject = path,
            methodRange = rangeOf(path, methodNeedle),
            targetRange = rangeOf(path, targetNeedle),
            newVisibility = visibility,
        )
        assertTrue(
            "expected $expectedCode but was $result",
            result is MoveInstanceMethodSelectionResolution.Failure,
        )
        val failure = result as MoveInstanceMethodSelectionResolution.Failure
        assertEquals(expectedCode, failure.code.name)
        assertTrue(failure.message.isNotBlank())
    }

    private fun constructorRangeOf(path: String, className: String): SourceRange {
        val virtualFile = LocalFileSystem.getInstance()
            .findFileByPath(Path.of(project.basePath!!, path).toString())!!
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as PsiJavaFile
        val javaClass = psiFile.classes.single { it.name == className }
        val constructor = javaClass.constructors.single()
        val nameRange = constructor.nameIdentifier!!.textRange
        val startLine = document.getLineNumber(nameRange.startOffset)
        val endLine = document.getLineNumber(nameRange.endOffset - 1)
        return SourceRange(
            startLine = startLine + 1,
            startColumn = nameRange.startOffset - document.getLineStartOffset(startLine) + 1,
            endLine = endLine + 1,
            endColumn = nameRange.endOffset - document.getLineStartOffset(endLine) + 1,
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

    private fun mirrorRealFile(path: String, text: String) {
        val target = Path.of(project.basePath!!, path)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
        LocalFileSystem.getInstance().refreshAndFindFileByPath(target.toString())!!
    }
}
