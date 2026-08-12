package com.example.airefactoring.mcp

import com.example.airefactoring.refactoring.extractmethod.ExtractMethodExecutor
import com.example.airefactoring.refactoring.extractmethod.ExtractMethodPreparationException
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.impl.ReflectionToolsProvider
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.dispatchAllEventsInIdeEventQueue
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Exercises [ExtractMethodMcpToolset.execute] against the real selection resolver with a spy
 * executor, plus the reflection/extension registrations that surface the tool to the MCP Server.
 *
 * The test JVM runs each test method on the EDT, so a `suspend` seam that internally hops to
 * `Dispatchers.EDT` cannot be driven by a blocking `runBlocking` on the test thread (that would
 * deadlock: the EDT is the blocked test thread). [runTool] therefore runs the suspend seam on a
 * pooled thread and pumps the EDT event queue from the test thread until the future completes.
 */
class ExtractMethodMcpToolsetTest : LightJavaCodeInsightFixtureTestCase() {

    // --- Step 2: dispatch tests with a spy executor ---

    fun testValidRequestInvokesExecutorOnceWithTrimmedName() {
        val range = configureMarkedFile(
            "CalcTool.java",
            "public class Calc {\n" +
                "    void print(int value) {\n" +
                "        <selection>System.out.println(value);</selection>\n" +
                "    }\n" +
                "}",
        )
        val spy = SpyExecutor()
        val toolset = ExtractMethodMcpToolset(executor = spy)

        val json = runTool { toolset.execute(project, "CalcTool.java", range, "  printValue  ") }

        assertEquals(1, spy.times)
        assertEquals("printValue", spy.lastMethodName)
        val obj = Json.parseToJsonElement(json).jsonObject
        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("printValue", obj.getValue("methodName").jsonPrimitive.content)
        assertEquals(project.basePath, obj.getValue("projectBasePath").jsonPrimitive.content)
    }

    fun testInvalidMethodNameReturnsInvalidMethodNameWithoutInvokingExecutor() {
        val spy = SpyExecutor()
        val toolset = ExtractMethodMcpToolset(executor = spy)

        // The method-name validator runs before any PSI resolution, so no fixture file is needed.
        val json = runTool { toolset.execute(project, "NoSuch.java", SourceRange(1, 1, 1, 2), "class") }

        assertEquals(0, spy.times)
        val obj = Json.parseToJsonElement(json).jsonObject
        assertFalse(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("INVALID_METHOD_NAME", obj.getValue("code").jsonPrimitive.content)
    }

    fun testResolverFailureCodesArePreservedInJson() {
        // FILE_NOT_FOUND: a project-relative path with no backing file.
        assertFailureCode("Missing.java", SourceRange(1, 1, 1, 2), McpRefactoringErrorCode.FILE_NOT_FOUND)

        // OUTSIDE_PROJECT: an absolute path is rejected before any file lookup.
        assertFailureCode("/tmp/Outside.java", SourceRange(1, 1, 1, 2), McpRefactoringErrorCode.OUTSIDE_PROJECT)

        // NOT_JAVA_FILE: a real non-Java file.
        mirrorRealFile("notes.txt", "hello world")
        assertFailureCode("notes.txt", SourceRange(1, 1, 1, 2), McpRefactoringErrorCode.NOT_JAVA_FILE)

        // READ_ONLY: a real Java file made read-only.
        val readOnly = mirrorRealFile("CalcReadOnlyTool.java", "public class Calc { }")
        WriteCommandAction.runWriteCommandAction(project) { readOnly.isWritable = false }
        try {
            assertFailureCode("CalcReadOnlyTool.java", SourceRange(1, 1, 1, 2), McpRefactoringErrorCode.READ_ONLY)
        } finally {
            WriteCommandAction.runWriteCommandAction(project) { readOnly.isWritable = true }
        }

        // INVALID_RANGE: an empty/ordered range on a real Java file.
        mirrorRealFile("CalcEmptyTool.java", "public class Calc { }")
        assertFailureCode("CalcEmptyTool.java", SourceRange(1, 1, 1, 1), McpRefactoringErrorCode.INVALID_RANGE)

        // NO_EXTRACTABLE_ELEMENTS: a range covering only a comment.
        configureMarkedFile(
            "CalcCommentTool.java",
            "public class Calc {\n" +
                "    int add(int x, int y) {\n" +
                "        <selection>// a comment</selection>\n" +
                "        return x;\n" +
                "    }\n" +
                "}",
        )
        assertFailureCode(
            "CalcCommentTool.java",
            sourceRangeFromEditor(),
            McpRefactoringErrorCode.NO_EXTRACTABLE_ELEMENTS,
        )
    }

    fun testPreparationExceptionMapsToPrepareFailed() {
        val range = configureMarkedFile(
            "CalcPrep.java",
            "public class Calc { void m() { <selection>int x = 1;</selection> } }",
        )
        val spy = SpyExecutor()
        spy.exceptionToThrow = ExtractMethodPreparationException("The selected code cannot be extracted.")
        val toolset = ExtractMethodMcpToolset(executor = spy)

        val json = runTool { toolset.execute(project, "CalcPrep.java", range, "doIt") }

        val obj = Json.parseToJsonElement(json).jsonObject
        assertFalse(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("PREPARE_FAILED", obj.getValue("code").jsonPrimitive.content)
    }

    fun testUnexpectedExceptionMapsToRefactoringFailed() {
        val range = configureMarkedFile(
            "CalcUnexp.java",
            "public class Calc { void m() { <selection>int x = 1;</selection> } }",
        )
        val spy = SpyExecutor()
        spy.exceptionToThrow = IllegalStateException("boom")
        val toolset = ExtractMethodMcpToolset(executor = spy)

        val json = runTool { toolset.execute(project, "CalcUnexp.java", range, "doIt") }

        val obj = Json.parseToJsonElement(json).jsonObject
        assertFalse(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("REFACTORING_FAILED", obj.getValue("code").jsonPrimitive.content)
    }

    fun testProcessCanceledExceptionEscapesRatherThanBecomingJson() {
        val range = configureMarkedFile(
            "CalcCancel.java",
            "public class Calc { void m() { <selection>int x = 1;</selection> } }",
        )
        val spy = SpyExecutor()
        spy.exceptionToThrow = ProcessCanceledException()
        val toolset = ExtractMethodMcpToolset(executor = spy)

        try {
            runTool { toolset.execute(project, "CalcCancel.java", range, "doIt") }
            fail("expected ProcessCanceledException to escape")
        } catch (expected: ProcessCanceledException) {
            // cancellation must propagate, not be encoded as a failure JSON.
        }
    }

    // --- Step 3: reflection and extension-registration tests ---

    fun testToolsetIsRegisteredAsExtension() {
        assertTrue(
            "ExtractMethodMcpToolset should be registered on McpToolset.EP via plugin.xml",
            McpToolset.EP.extensionList.any { it is ExtractMethodMcpToolset },
        )
    }

    fun testReflectedToolDescriptorMatchesContract() {
        val descriptor = ReflectionToolsProvider().getTools()
            .map { it.descriptor }
            .single { it.name == "java_extract_method" }

        assertTrue(
            "tool description must forbid direct text edits",
            descriptor.description.contains("Never implement Extract Method through direct text edits"),
        )
    }

    fun testInputSchemaContainsExactlySixDeclaredArguments() {
        val descriptor = ReflectionToolsProvider().getTools()
            .map { it.descriptor }
            .single { it.name == "java_extract_method" }

        val properties = descriptor.inputSchema.propertiesSchema
        // The 2026.1.3 reflection provider injects the host's project-routing metadata
        // (`projectPath`) into every tool's input schema. The tool function itself declares exactly
        // the six arguments below; `projectPath` is host-managed and not a function parameter.
        val declared = setOf("pathInProject", "startLine", "startColumn", "endLine", "endColumn", "methodName")
        assertEquals(
            "the tool schema must expose exactly the six declared arguments plus host project-routing metadata",
            declared + "projectPath",
            properties.keys,
        )
    }

    // --- helpers ---

    /**
     * Runs a `suspend` tool-seam block on a pooled thread while the test thread (the EDT) pumps the
     * event queue, so `withContext(Dispatchers.EDT)` inside the seam can complete. Unwraps
     * [ExecutionException] so expected exceptions (e.g. [ProcessCanceledException]) escape raw.
     */
    private fun runTool(block: suspend () -> String): String {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            val future = executor.submit<String> { runBlocking { block() } }
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
            executor.shutdownNow()
        }
    }

    /** Runs a failing request and asserts the resolver's failure code is preserved in the JSON. */
    private fun assertFailureCode(pathInProject: String, range: SourceRange, expected: McpRefactoringErrorCode) {
        val toolset = ExtractMethodMcpToolset(executor = SpyExecutor())
        val json = runTool { toolset.execute(project, pathInProject, range, "doIt") }
        val obj = Json.parseToJsonElement(json).jsonObject
        assertFalse("expected a failure for $pathInProject but was $json", obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals(expected.name, obj.getValue("code").jsonPrimitive.content)
    }

    /** Derives the 1-based [SourceRange] from the fixture editor's `<selection>` markers. */
    private fun sourceRangeFromEditor(): SourceRange {
        val document = myFixture.editor.document
        fun position(offset: Int): Pair<Int, Int> {
            val line = document.getLineNumber(offset)
            return (line + 1) to (offset - document.getLineStartOffset(line) + 1)
        }
        val (startLine, startColumn) = position(myFixture.editor.selectionModel.selectionStart)
        val (endLine, endColumn) = position(myFixture.editor.selectionModel.selectionEnd)
        return SourceRange(startLine, startColumn, endLine, endColumn)
    }

    /** Writes [text] to a real `{project.basePath}/{fileName}` file and registers it in the VFS. */
    private fun mirrorRealFile(fileName: String, text: String): VirtualFile {
        val target = Path.of(project.basePath!!, fileName)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(target.toString())!!
    }

    /** Configures a `<selection>`-marked fixture, mirrors the plain content, and returns its range. */
    private fun configureMarkedFile(fileName: String, markedText: String): SourceRange {
        myFixture.configureByText(fileName, markedText)
        val range = sourceRangeFromEditor()
        mirrorRealFile(fileName, myFixture.editor.document.text)
        return range
    }

    /** A controllable [ExtractMethodExecutor] spy. */
    private class SpyExecutor : ExtractMethodExecutor {
        var times = 0
        var lastMethodName: String? = null
        var exceptionToThrow: Throwable? = null

        override fun extract(
            project: Project,
            file: PsiFile,
            elements: Array<PsiElement>,
            methodName: String,
        ): String {
            times++
            lastMethodName = methodName
            exceptionToThrow?.let { throw it }
            return "Extracted method '$methodName'."
        }
    }
}
