package com.example.airefactoring.refactoring.makestatic

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

class JavaMakeStaticSelectionResolverTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = JavaMakeStaticSelectionResolver()

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testResolvesExactMethodAndProducesPointerFacts() {
        methodFixture()
        val result = resolver.resolve(
            project = project,
            pathInProject = "example/Invoice.java",
            memberRange = rangeOf("example/Invoice.java", "applyDiscount"),
            replaceUsages = true,
            classParameterName = null,
            fieldParameters = emptyList(),
            generateDelegate = false,
        )

        assertSuccess(result) { preparation ->
            assertEquals(JavaMakeStaticMemberKind.METHOD, preparation.memberKind)
            assertEquals("applyDiscount", preparation.memberName)
            assertEquals("example/Invoice.java", preparation.pathInProject)
            assertTrue(preparation.replaceUsages)
            assertFalse(preparation.generateDelegate)
            assertNull(preparation.classParameterName)
            assertTrue(preparation.memberTextSnapshot.contains("applyDiscount"))
            assertTrue(preparation.fieldPointers.isEmpty())
            assertEquals("applyDiscount", preparation.memberPointer.element?.name)
        }
    }

    fun testResolvesNonStaticInnerClassAndProducesPointerFacts() {
        methodFixture()
        val result = resolver.resolve(
            project = project,
            pathInProject = "example/Invoice.java",
            memberRange = rangeOf("example/Invoice.java", "Inner"),
            replaceUsages = false,
            classParameterName = "outer",
            fieldParameters = emptyList(),
            generateDelegate = true,
        )

        assertSuccess(result) { preparation ->
            assertEquals(JavaMakeStaticMemberKind.CLASS, preparation.memberKind)
            assertEquals("Inner", preparation.memberName)
            assertFalse(preparation.replaceUsages)
            assertTrue(preparation.generateDelegate)
            assertEquals("outer", preparation.classParameterName)
            assertEquals("Inner", preparation.memberPointer.element?.name)
        }
    }

    fun testPreservesLegalSelectedFieldOrdering() {
        methodFixture()
        val result = resolver.resolve(
            project = project,
            pathInProject = "example/Invoice.java",
            memberRange = rangeOf("example/Invoice.java", "applyDiscount"),
            replaceUsages = true,
            classParameterName = null,
            fieldParameters = listOf(
                fieldParameter("example/Invoice.java", "amount", "a"),
                fieldParameter("example/Invoice.java", "rate", "r"),
            ),
            generateDelegate = false,
        )

        assertSuccess(result) { preparation ->
            assertEquals(listOf("a", "r"), preparation.fieldParameterNames)
            assertEquals(listOf("amount", "rate"), preparation.fieldPointers.map { it.element?.name })
            assertEquals(2, preparation.fieldTextSnapshots.size)
            assertTrue(preparation.fieldTextSnapshots[0].contains("amount"))
            assertTrue(preparation.fieldTextSnapshots[1].contains("rate"))
        }
    }

    fun testRejectsConstructor() {
        mirrorRealFile(
            "example/ConstructorTarget.java",
            """
                package example;

                public class ConstructorTarget {
                    private int amount;

                    public ConstructorTarget(int amount) {
                        this.amount = amount;
                    }

                    public int make() {
                        return amount;
                    }
                }
            """.trimIndent(),
        )
        val result = resolver.resolve(
            project = project,
            pathInProject = "example/ConstructorTarget.java",
            memberRange = constructorRangeOf("example/ConstructorTarget.java", "ConstructorTarget"),
            replaceUsages = true,
            classParameterName = null,
            fieldParameters = emptyList(),
            generateDelegate = false,
        )
        assertTrue(
            "expected UNSUPPORTED_METHOD for a constructor but was $result",
            result is JavaMakeStaticSelectionResolution.Failure,
        )
        val failure = result as JavaMakeStaticSelectionResolution.Failure
        assertEquals("UNSUPPORTED_METHOD", failure.code.name)
        assertTrue(failure.message.isNotBlank())
    }

    fun testRejectsStaticMethod() {
        mirrorRealFile(
            "example/StaticMethod.java",
            """
                package example;

                public class StaticMethod {
                    public static int make() {
                        return 1;
                    }
                }
            """.trimIndent(),
        )
        assertFailure(
            "example/StaticMethod.java",
            "make",
            emptyList(),
            null,
            "UNSUPPORTED_METHOD",
        )
    }

    fun testRejectsStaticInnerClass() {
        methodFixture()
        assertFailure(
            "example/Invoice.java",
            "StaticInner",
            emptyList(),
            null,
            "UNSUPPORTED_TARGET",
        )
    }

    fun testRejectsTopLevelClass() {
        methodFixture()
        assertFailure(
            "example/Invoice.java",
            "Invoice",
            emptyList(),
            null,
            "UNSUPPORTED_TARGET",
        )
    }

    fun testRejectsNonNameSelection() {
        methodFixture()
        assertFailure(
            "example/Invoice.java",
            "applyDiscount()",
            emptyList(),
            null,
            "INVALID_RANGE",
        )
    }

    fun testRejectsStaticSelectedField() {
        methodFixture()
        assertFailure(
            "example/Invoice.java",
            "applyDiscount",
            listOf(fieldParameter("example/Invoice.java", "TAX", "tax")),
            null,
            "UNSUPPORTED_TARGET",
        )
    }

    fun testRejectsUnrelatedField() {
        methodFixture()
        // innerField belongs to Inner, not to applyDiscount's containing class Invoice.
        assertFailure(
            "example/Invoice.java",
            "applyDiscount",
            listOf(fieldParameter("example/Invoice.java", "innerField", "inner")),
            null,
            "UNSUPPORTED_TARGET",
        )
    }

    fun testRejectsDuplicateField() {
        methodFixture()
        assertFailure(
            "example/Invoice.java",
            "applyDiscount",
            listOf(
                fieldParameter("example/Invoice.java", "amount", "a"),
                fieldParameter("example/Invoice.java", "amount", "a2"),
            ),
            null,
            "UNSUPPORTED_TARGET",
        )
    }

    fun testRejectsNonFieldSelection() {
        methodFixture()
        // Selecting a method declaration name as a field parameter is not a field.
        assertFailure(
            "example/Invoice.java",
            "applyDiscount",
            listOf(fieldParameter("example/Invoice.java", "inner", "inner")),
            null,
            "UNSUPPORTED_TARGET",
        )
    }

    fun testRejectsInvalidClassParameterName() {
        methodFixture()
        assertFailure(
            "example/Invoice.java",
            "applyDiscount",
            emptyList(),
            "123invalid",
            "INVALID_PARAMETER_NAME",
        )
    }

    fun testRejectsInvalidFieldParameterName() {
        methodFixture()
        assertFailure(
            "example/Invoice.java",
            "applyDiscount",
            listOf(fieldParameter("example/Invoice.java", "amount", "not a name")),
            null,
            "INVALID_PARAMETER_NAME",
        )
    }

    fun testRejectsDuplicateFieldParameterName() {
        methodFixture()
        assertFailure(
            "example/Invoice.java",
            "applyDiscount",
            listOf(
                fieldParameter("example/Invoice.java", "amount", "x"),
                fieldParameter("example/Invoice.java", "rate", "x"),
            ),
            null,
            "INVALID_PARAMETER_NAME",
        )
    }

    fun testRejectsClassParameterNameCollidingWithFieldParameterName() {
        methodFixture()
        assertFailure(
            "example/Invoice.java",
            "applyDiscount",
            listOf(fieldParameter("example/Invoice.java", "amount", "outer")),
            "outer",
            "INVALID_PARAMETER_NAME",
        )
    }

    fun testRejectsAbstractMethodViaValidateTarget() {
        mirrorRealFile(
            "example/AbstractTarget.java",
            """
                package example;

                public abstract class AbstractTarget {
                    public abstract void make();
                }
            """.trimIndent(),
        )
        assertFailure(
            "example/AbstractTarget.java",
            "make",
            emptyList(),
            null,
            "PREPARE_FAILED",
        )
    }

    private fun methodFixture() {
        mirrorRealFile(
            "example/Invoice.java",
            """
                package example;

                public class Invoice {
                    private int amount;
                    private int rate;
                    private static int TAX;

                    public int applyDiscount() {
                        return this.amount + this.rate;
                    }

                    public class Inner {
                        private int innerField;

                        public int inner() {
                            return innerField;
                        }
                    }

                    public static class StaticInner {
                        public int value;
                    }
                }
            """.trimIndent(),
        )
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

    private fun fieldParameter(path: String, needle: String, name: String): JavaMakeStaticFieldParameter {
        val (range, _) = rangeAndOffset(path, needle)
        return JavaMakeStaticFieldParameter(
            startLine = range.startLine,
            startColumn = range.startColumn,
            endLine = range.endLine,
            endColumn = range.endColumn,
            parameterName = name,
        )
    }

    private fun assertSuccess(
        result: JavaMakeStaticSelectionResolution,
        block: (JavaMakeStaticPreparation) -> Unit,
    ) {
        assertTrue(
            "expected successful preparation but was $result",
            result is JavaMakeStaticSelectionResolution.Success,
        )
        block((result as JavaMakeStaticSelectionResolution.Success).preparation)
    }

    private fun assertFailure(
        path: String,
        memberNeedle: String,
        fieldParameters: List<JavaMakeStaticFieldParameter>,
        classParameterName: String?,
        expectedCode: String,
    ) {
        val result = resolver.resolve(
            project = project,
            pathInProject = path,
            memberRange = rangeOf(path, memberNeedle),
            replaceUsages = true,
            classParameterName = classParameterName,
            fieldParameters = fieldParameters,
            generateDelegate = false,
        )
        assertTrue(
            "expected $expectedCode but was $result",
            result is JavaMakeStaticSelectionResolution.Failure,
        )
        val failure = result as JavaMakeStaticSelectionResolution.Failure
        assertEquals(expectedCode, failure.code.name)
        assertTrue(failure.message.isNotBlank())
    }

    private fun rangeOf(path: String, needle: String): SourceRange =
        rangeAndOffset(path, needle).first

    private fun rangeAndOffset(path: String, needle: String): Pair<SourceRange, Int> {
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
        ) to offset
    }

    private fun mirrorRealFile(path: String, text: String) {
        val target = Path.of(project.basePath!!, path)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
        LocalFileSystem.getInstance().refreshAndFindFileByPath(target.toString())!!
    }
}
