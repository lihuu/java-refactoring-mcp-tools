package com.example.airefactoring.refactoring.inlinemethod

import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path

class InlineMethodSelectionResolverTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = InlineMethodSelectionResolver()

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testResolvesExactMethodDeclarationNameAndAllCrossFileDirectUsages() {
        pricingFixture()
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)

        val pricingFile = PsiManager.getInstance(project).findFile(virtualFile("example/PricingRules.java")) as PsiJavaFile
        val method = pricingFile.classes.single().findMethodsByName("addTax", false).single()
        val callerFile = PsiManager.getInstance(project).findFile(virtualFile("example/Checkout.java")) as PsiJavaFile
        val calls = PsiTreeUtil.collectElementsOfType(callerFile, PsiMethodCallExpression::class.java)
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.resolveMethod() === method })
        val result = resolver.resolve(project, "example/PricingRules.java", rangeOf("example/PricingRules.java", "addTax"))

        assertTrue("expected success but was $result", result is InlineMethodSelectionResolution.Success)
        val preparation = (result as InlineMethodSelectionResolution.Success).preparation
        assertEquals("addTax", preparation.methodName)
        assertEquals("example/PricingRules.java", preparation.pathInProject)
        assertEquals(2, preparation.usagePointers.size)
        val checkoutText = FileDocumentManager.getInstance().getDocument(virtualFile("example/Checkout.java"))!!.text
        val firstUsageOffset = checkoutText.indexOf("PricingRules.addTax")
        val secondUsageOffset = checkoutText.indexOf("PricingRules.addTax", firstUsageOffset + 1)
        assertEquals(listOf(firstUsageOffset, secondUsageOffset), preparation.usageOffsetsSnapshot)
        assertEquals(
            setOf("Checkout.java", "PricingRules.java"),
            preparation.affectedVirtualFiles.map { it.name }.toSet(),
        )
        assertEquals("addTax", preparation.methodPointer.element?.name)
    }

    fun testRejectsRangeContainingMethodTypeOrParentheses() {
        pricingFixture()

        val result = resolver.resolve(
            project,
            "example/PricingRules.java",
            rangeOf("example/PricingRules.java", "int addTax(int amount)"),
        )

        assertFailure(result, "INVALID_RANGE")
    }

    fun testRejectsConstructorAbstractNativeRecursiveAndHierarchyMethods() {
        mirrorRealFile(
            "example/UnsupportedMethods.java",
            """
                package example;
                abstract class AbstractHost { abstract int abstractMethod(int value); }
                class NativeHost { native int nativeMethod(int value); }
                class RecursiveHost { int recursiveMethod(int value) { return value == 0 ? 0 : recursiveMethod(value - 1); } }
                class Parent { int inheritedMethod(int value) { return value + 1; } }
                class Child extends Parent { @Override int inheritedMethod(int value) { return value + 2; } }
                class ConstructorHost { ConstructorHost(int value) {} }
            """.trimIndent(),
        )
        mirrorRealFile(
            "example/UnsupportedCallers.java",
            """
                package example;
                class UnsupportedCallers {
                    int run() {
                        return new RecursiveHost().recursiveMethod(1) + new Child().inheritedMethod(1);
                    }
                }
            """.trimIndent(),
        )
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)

        assertFailure(
            resolver.resolve(project, "example/UnsupportedMethods.java", rangeOf("example/UnsupportedMethods.java", "AbstractHost")),
            "INVALID_RANGE",
        )
        assertFailure(
            resolver.resolve(project, "example/UnsupportedMethods.java", rangeOf("example/UnsupportedMethods.java", "abstractMethod")),
            "UNSUPPORTED_METHOD",
        )
        assertFailure(
            resolver.resolve(project, "example/UnsupportedMethods.java", rangeOf("example/UnsupportedMethods.java", "nativeMethod")),
            "UNSUPPORTED_METHOD",
        )
        assertFailure(
            resolver.resolve(project, "example/UnsupportedMethods.java", rangeOf("example/UnsupportedMethods.java", "recursiveMethod")),
            "UNSUPPORTED_METHOD",
        )
        assertFailure(
            resolver.resolve(project, "example/UnsupportedMethods.java", rangeOfLast("example/UnsupportedMethods.java", "inheritedMethod")),
            "UNSUPPORTED_METHOD",
        )
        assertFailure(
            resolver.resolve(project, "example/UnsupportedMethods.java", rangeOfLast("example/UnsupportedMethods.java", "ConstructorHost")),
            "UNSUPPORTED_METHOD",
        )
    }

    fun testRejectsNoUsageMethodMethodReferenceAndNonCodeUsage() {
        mirrorRealFile(
            "example/References.java",
            """
                package example;
                import java.util.function.IntUnaryOperator;
                class References {
                    int noUsage(int value) { return value + 1; }
                    static int methodReference(int value) { return value + 2; }
                    /** {@link #nonCodeUsage(int)} */
                    int nonCodeUsage(int value) { return value + 3; }
                    IntUnaryOperator operator = References::methodReference;
                }
            """.trimIndent(),
        )
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)

        assertFailure(
            resolver.resolve(project, "example/References.java", rangeOf("example/References.java", "noUsage")),
            "UNSUPPORTED_METHOD",
        )
        assertFailure(
            resolver.resolve(project, "example/References.java", rangeOf("example/References.java", "methodReference")),
            "UNSUPPORTED_USAGE",
        )
        assertFailure(
            resolver.resolve(project, "example/References.java", rangeOfLast("example/References.java", "nonCodeUsage")),
            "UNSUPPORTED_USAGE",
        )
    }

    fun testRejectsNonJavaReadOnlyAndOutsideProjectTargets() {
        pricingFixture()
        mirrorRealFile("example/notes.txt", "not Java")
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val caller = virtualFile("example/Checkout.java")
        WriteCommandAction.runWriteCommandAction(project) { caller.isWritable = false }
        try {
            assertFailure(
                resolver.resolve(project, "example/PricingRules.java", rangeOf("example/PricingRules.java", "addTax")),
                "READ_ONLY",
            )
        } finally {
            WriteCommandAction.runWriteCommandAction(project) { caller.isWritable = true }
        }
        assertFailure(
            resolver.resolve(project, "example/notes.txt", SourceRange(1, 1, 1, 2)),
            "NOT_JAVA_FILE",
        )
        assertFailure(
            resolver.resolve(project, "../outside.java", SourceRange(1, 1, 1, 2)),
            "OUTSIDE_PROJECT",
        )
    }

    private fun pricingFixture() {
        mirrorRealFile(
            "example/PricingRules.java",
            """
                package example;
                public final class PricingRules {
                    public static int addTax(int amount) { return amount + 5; }
                }
            """.trimIndent(),
        )
        mirrorRealFile(
            "example/Checkout.java",
            """
                package example;
                public final class Checkout {
                    public int total(int amount) {
                        return PricingRules.addTax(amount) + PricingRules.addTax(10);
                    }
                }
            """.trimIndent(),
        )
    }

    private fun assertFailure(result: InlineMethodSelectionResolution, expectedCode: String) {
        assertTrue("expected $expectedCode but was $result", result is InlineMethodSelectionResolution.Failure)
        val failure = result as InlineMethodSelectionResolution.Failure
        assertEquals(expectedCode, failure.code.name)
        assertTrue(failure.message.isNotBlank())
    }

    private fun rangeOf(path: String, needle: String): SourceRange = range(path, needle, false)

    private fun rangeOfLast(path: String, needle: String): SourceRange = range(path, needle, true)

    private fun range(path: String, needle: String, last: Boolean): SourceRange {
        val document = FileDocumentManager.getInstance().getDocument(virtualFile(path))!!
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val offset = if (last) document.text.lastIndexOf(needle) else document.text.indexOf(needle)
        assertTrue("'$needle' missing in $path", offset >= 0)
        val end = offset + needle.length
        val startLine = document.getLineNumber(offset)
        val endLine = document.getLineNumber(end - 1)
        return SourceRange(
            startLine + 1,
            offset - document.getLineStartOffset(startLine) + 1,
            endLine + 1,
            end - document.getLineStartOffset(endLine) + 1,
        )
    }

    private fun virtualFile(path: String) =
        LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, path).toString())!!

    private fun mirrorRealFile(path: String, text: String) {
        val target = Path.of(project.basePath!!, path)
        Files.createDirectories(target.parent)
        if (!Files.exists(target)) Files.createFile(target)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(target.toString())!!
        WriteAction.run<RuntimeException> { VfsUtil.saveText(virtualFile, text) }
        FileDocumentManager.getInstance().getDocument(virtualFile)?.let {
            PsiDocumentManager.getInstance(project).commitDocument(it)
        }
    }
}
