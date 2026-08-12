package com.example.airefactoring.refactoring.changesignature

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path

class ChangeSignaturePreparationResolverTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = ChangeSignaturePreparationResolver()

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testPreparesOrdinaryMethodAndReportsSemanticCallSites() {
        mirrorRealFile(
            "example/GreetingService.java",
            """
                package example;
                public class GreetingService {
                    public String greet(String name) { return "Hello " + name; }
                }
            """.trimIndent(),
        )
        mirrorRealFile(
            "example/CallerOne.java",
            """
                package example;
                class CallerOne {
                    String call() { return new GreetingService().greet("Ada"); }
                }
            """.trimIndent(),
        )
        mirrorRealFile(
            "example/CallerTwo.java",
            """
                package example;
                class CallerTwo {
                    String call() { return new GreetingService().greet("Lin"); }
                }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val (line, column) = positionOf("example/GreetingService.java", "greet")

        val result = resolver.resolve(
            project = project,
            pathInProject = "example/GreetingService.java",
            line = line,
            column = column,
            parameterName = "punctuation",
            parameterTypeText = "java.lang.String",
            parameterPosition = 2,
            defaultCallSiteExpression = "\"!\"",
        )

        assertTrue("expected successful preparation but was $result", result is ChangeSignaturePreparationResolution.Success)
        val preparation = (result as ChangeSignaturePreparationResolution.Success).preparation
        assertEquals("greet", preparation.methodName)
        assertEquals("java.lang.String", preparation.canonicalParameterType)
        assertEquals(2, preparation.updatedCallSiteCount)
        assertEquals(
            listOf(
                "example/CallerOne.java",
                "example/CallerTwo.java",
                "example/GreetingService.java",
            ),
            preparation.affectedFiles,
        )
    }

    fun testRejectsCoordinateOutsideMethod() {
        mirrorRealFile("NoMethod.java", "class NoMethod { int value; }")
        assertFailure(
            "NoMethod.java",
            "value",
            "next",
            "int",
            1,
            "0",
            "NO_TARGET_METHOD",
        )
    }

    fun testRejectsConstructor() {
        mirrorRealFile("Constructor.java", "class Constructor { Constructor() {} }")
        assertFailure(
            "Constructor.java",
            "Constructor()",
            "value",
            "int",
            1,
            "0",
            "UNSUPPORTED_METHOD",
        )
    }

    fun testRejectsSameNameOverloadSet() {
        mirrorRealFile(
            "Overloaded.java",
            "class Overloaded { void run() {} void run(String value) {} }",
        )
        assertFailure(
            "Overloaded.java",
            "run()",
            "enabled",
            "boolean",
            1,
            "false",
            "UNSUPPORTED_METHOD",
        )
    }

    fun testRejectsOverrideHierarchy() {
        mirrorRealFile("Base.java", "class Base { void run() {} }")
        mirrorRealFile(
            "Child.java",
            "class Child extends Base { @Override void run() {} }",
        )
        assertFailure(
            "Base.java",
            "run()",
            "enabled",
            "boolean",
            1,
            "false",
            "UNSUPPORTED_METHOD",
        )
    }

    fun testRejectsMethodReferenceUsage() {
        mirrorRealFile(
            "Refs.java",
            "class Refs { void run() {} Runnable ref() { return this::run; } }",
        )
        assertFailure(
            "Refs.java",
            "run()",
            "enabled",
            "boolean",
            1,
            "false",
            "UNSUPPORTED_USAGE",
        )
    }

    fun testRejectsReadOnlyCallSite() {
        mirrorRealFile("ReadOnlyTarget.java", "class ReadOnlyTarget { void run() {} }")
        val caller = mirrorRealFile(
            "ReadOnlyCaller.java",
            "class ReadOnlyCaller { void call() { new ReadOnlyTarget().run(); } }",
        )
        WriteCommandAction.runWriteCommandAction(project) { caller.isWritable = false }
        try {
            assertFailure(
                "ReadOnlyTarget.java",
                "run()",
                "enabled",
                "boolean",
                1,
                "false",
                "UNSUPPORTED_USAGE",
            )
        } finally {
            WriteCommandAction.runWriteCommandAction(project) { caller.isWritable = true }
        }
    }

    fun testRejectsDuplicateParameterName() {
        mirrorRealFile("Duplicate.java", "class Duplicate { void run(int value) {} }")
        assertFailure(
            "Duplicate.java",
            "run",
            "value",
            "int",
            2,
            "0",
            "INVALID_PARAMETER_NAME",
        )
    }

    fun testRejectsInvalidTypePositionAndExpression() {
        mirrorRealFile(
            "InvalidInput.java",
            "class InvalidInput { void run(int value) {} void call() { run(1); } }",
        )
        assertFailure(
            "InvalidInput.java",
            "run(int",
            "next",
            "MissingType",
            2,
            "null",
            "INVALID_PARAMETER_TYPE",
        )
        assertFailure(
            "InvalidInput.java",
            "run(int",
            "next",
            "String...",
            2,
            "null",
            "INVALID_PARAMETER_TYPE",
        )
        assertFailure(
            "InvalidInput.java",
            "run(int",
            "next",
            "int",
            0,
            "0",
            "INVALID_PARAMETER_POSITION",
        )
        assertFailure(
            "InvalidInput.java",
            "run(int",
            "next",
            "int",
            3,
            "0",
            "INVALID_PARAMETER_POSITION",
        )
        assertFailure(
            "InvalidInput.java",
            "run(int",
            "next",
            "int",
            2,
            "(",
            "INVALID_DEFAULT_VALUE",
        )
        assertFailure(
            "InvalidInput.java",
            "run(int",
            "next",
            "int",
            2,
            "\"wrong\"",
            "INVALID_DEFAULT_VALUE",
        )
    }

    private fun assertFailure(
        path: String,
        needle: String,
        parameterName: String,
        parameterTypeText: String,
        parameterPosition: Int,
        defaultCallSiteExpression: String,
        expectedCode: String,
    ) {
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val (line, column) = positionOf(path, needle)
        val result = resolver.resolve(
            project,
            path,
            line,
            column,
            parameterName,
            parameterTypeText,
            parameterPosition,
            defaultCallSiteExpression,
        )
        assertTrue(
            "expected $expectedCode but was $result",
            result is ChangeSignaturePreparationResolution.Failure,
        )
        val failure = result as ChangeSignaturePreparationResolution.Failure
        assertEquals(expectedCode, failure.code.name)
        assertTrue(failure.message.isNotBlank())
    }

    private fun positionOf(path: String, needle: String): Pair<Int, Int> {
        val virtualFile = LocalFileSystem.getInstance()
            .findFileByPath(Path.of(project.basePath!!, path).toString())!!
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val offset = document.text.indexOf(needle)
        assertTrue("'$needle' missing from $path", offset >= 0)
        val line = document.getLineNumber(offset)
        return (line + 1) to (offset - document.getLineStartOffset(line) + 1)
    }

    private fun mirrorRealFile(path: String, text: String): VirtualFile {
        val target = Path.of(project.basePath!!, path)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(target.toString())!!
    }
}
