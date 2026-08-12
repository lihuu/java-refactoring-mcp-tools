package com.example.airefactoring.refactoring.changesignature

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.dispatchAllEventsInIdeEventQueue
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IntellijChangeSignatureExecutorTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = ChangeSignaturePreparationResolver()
    private val executor = IntellijChangeSignatureExecutor()

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testAddsMiddleParameterAndUpdatesTwoCrossFileCalls() {
        val preparation = prepareGreetingFixture(parameterPosition = 2)

        val result = runExecutor { executor.addParameter(project, preparation) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertEquals("punctuation", result.parameterName)
        assertEquals("java.lang.String", result.parameterType)
        assertEquals(2, result.parameterPosition)
        assertEquals(2, result.updatedCallSiteCount)
        val method = preparation.methodPointer.element!!
        assertEquals(
            listOf("name", "punctuation"),
            method.parameterList.parameters.map { it.name },
        )
        val calls = preparation.affectedFiles
            .filter { it.contains("Caller") }
            .flatMap { methodCallsIn(it, "greet") }
        assertEquals(2, calls.size)
        calls.forEach { call ->
            assertEquals(2, call.argumentList.expressionCount)
            assertEquals("\"!\"", call.argumentList.expressions[1].text)
        }
    }

    fun testAddsFirstParameterAtOneBasedPositionOne() {
        val preparation = prepareTwoParameterFixture(position = 1)

        runExecutor { executor.addParameter(project, preparation) }

        assertEquals(
            listOf("enabled", "left", "right"),
            preparation.methodPointer.element!!.parameterList.parameters.map { it.name },
        )
    }

    fun testAppendsParameterAtOneBasedPositionThree() {
        val preparation = prepareTwoParameterFixture(position = 3)

        runExecutor { executor.addParameter(project, preparation) }

        assertEquals(
            listOf("left", "right", "enabled"),
            preparation.methodPointer.element!!.parameterList.parameters.map { it.name },
        )
    }

    fun testOneUndoRestoresDeclarationAndEveryCaller() {
        val preparation = prepareGreetingFixture(parameterPosition = 2)
        val originals = preparation.affectedFiles.associateWith(::documentText)

        runExecutor { executor.addParameter(project, preparation) }

        val undoManager = UndoManager.getInstance(project)
        assertTrue(undoManager.isUndoAvailable(null))
        val previous = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undoManager.undo(null)
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        originals.forEach { (path, text) -> assertEquals(text, documentText(path)) }
    }

    fun testDoesNotSaveUnrelatedDirtyDocument() {
        val preparation = prepareGreetingFixture(parameterPosition = 2)
        val other = mirrorRealFile(
            "example/Unrelated.java",
            "package example; class Unrelated { int value = 1; }",
        )
        val otherDocument = FileDocumentManager.getInstance().getDocument(other)!!
        WriteCommandAction.runWriteCommandAction(project) {
            otherDocument.insertString(otherDocument.textLength, "\n// dirty")
        }
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(otherDocument))
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        runExecutor { executor.addParameter(project, preparation) }

        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(otherDocument))
    }

    fun testRejectsStaleParameterListBeforeNativeMutation() {
        val preparation = prepareGreetingFixture(parameterPosition = 2)
        val declarationDocument = document("example/GreetingService.java")
        val before = preparation.affectedFiles.associateWith(::documentText)
        WriteCommandAction.runWriteCommandAction(project) {
            val start = declarationDocument.text.indexOf("(String name)")
            declarationDocument.replaceString(
                start,
                start + "(String name)".length,
                "(String name, int changed)",
            )
        }
        PsiDocumentManager.getInstance(project).commitDocument(declarationDocument)
        val changedDeclaration = declarationDocument.text

        try {
            runExecutor { executor.addParameter(project, preparation) }
            fail("expected stale preparation rejection")
        } catch (expected: ChangeSignaturePreparationException) {
            assertTrue(expected.message!!.contains("changed"))
        }

        assertEquals(changedDeclaration, declarationDocument.text)
        before.filterKeys { it != "example/GreetingService.java" }
            .forEach { (path, text) -> assertEquals(text, documentText(path)) }
    }

    fun testNativeConflictIsReportedWithoutMutation() {
        mirrorRealFile(
            "example/Conflict.java",
            """
                package example;
                class Conflict {
                    void run() { int enabled = 1; System.out.println(enabled); }
                }
            """.trimIndent(),
        )
        val preparation = prepare(
            path = "example/Conflict.java",
            needle = "run()",
            parameterName = "enabled",
            parameterType = "int",
            position = 1,
            defaultExpression = "0",
        )
        val original = documentText("example/Conflict.java")

        try {
            runExecutor { executor.addParameter(project, preparation) }
            fail("expected native conflict")
        } catch (expected: ChangeSignatureConflictException) {
            assertTrue(expected.message!!.isNotBlank())
        }

        assertEquals(original, documentText("example/Conflict.java"))
    }

    private fun prepareGreetingFixture(parameterPosition: Int): ChangeSignaturePreparation {
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
            "package example; class CallerOne { String call() { return new GreetingService().greet(\"Ada\"); } }",
        )
        mirrorRealFile(
            "example/CallerTwo.java",
            "package example; class CallerTwo { String call() { return new GreetingService().greet(\"Lin\"); } }",
        )
        return prepare(
            path = "example/GreetingService.java",
            needle = "greet",
            parameterName = "punctuation",
            parameterType = "java.lang.String",
            position = parameterPosition,
            defaultExpression = "\"!\"",
        )
    }

    private fun prepareTwoParameterFixture(position: Int): ChangeSignaturePreparation {
        mirrorRealFile(
            "example/Joiner.java",
            """
                package example;
                class Joiner {
                    String join(String left, String right) { return left + right; }
                    String call() { return join("a", "b"); }
                }
            """.trimIndent(),
        )
        return prepare(
            path = "example/Joiner.java",
            needle = "join(String",
            parameterName = "enabled",
            parameterType = "boolean",
            position = position,
            defaultExpression = "false",
        )
    }

    private fun prepare(
        path: String,
        needle: String,
        parameterName: String,
        parameterType: String,
        position: Int,
        defaultExpression: String,
    ): ChangeSignaturePreparation {
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val targetDocument = document(path)
        val offset = targetDocument.text.indexOf(needle)
        assertTrue("'$needle' missing from $path", offset >= 0)
        val line = targetDocument.getLineNumber(offset)
        val result = resolver.resolve(
            project,
            path,
            line + 1,
            offset - targetDocument.getLineStartOffset(line) + 1,
            parameterName,
            parameterType,
            position,
            defaultExpression,
        )
        assertTrue("expected preparation success but was $result", result is ChangeSignaturePreparationResolution.Success)
        return (result as ChangeSignaturePreparationResolution.Success).preparation
    }

    private fun methodCallsIn(path: String, methodName: String): List<PsiMethodCallExpression> {
        val file = PsiManager.getInstance(project).findFile(virtualFile(path)) as PsiJavaFile
        return PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression::class.java)
            .filter { it.methodExpression.referenceName == methodName }
    }

    private fun documentText(path: String): String = document(path).text

    private fun document(path: String): Document = FileDocumentManager.getInstance()
        .getDocument(virtualFile(path))!!

    private fun virtualFile(path: String): VirtualFile = LocalFileSystem.getInstance()
        .findFileByPath(Path.of(project.basePath!!, path).toString())!!

    private fun mirrorRealFile(path: String, text: String): VirtualFile {
        val target = Path.of(project.basePath!!, path)
        Files.createDirectories(target.parent)
        if (!Files.exists(target)) Files.createFile(target)
        val virtualFile = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(target.toString())!!
        WriteAction.run<RuntimeException> { VfsUtil.saveText(virtualFile, text) }
        return virtualFile
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
