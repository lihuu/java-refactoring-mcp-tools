package com.example.airefactoring.refactoring.introduceparameter

import com.example.airefactoring.refactoring.SourceRange
import com.example.airefactoring.refactoring.RecordingNativeRefactoringDocumentPersister
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
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

class IntellijIntroduceParameterExecutorTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = IntroduceParameterSelectionResolver()

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    // --- Step 1: expression executor ---

    fun testIntroducesParameterFromExpressionAndUpdatesTwoCrossFileCallers() {
        val fixture = priceServiceFixture()
        val selection = resolveExpression(fixture, "rate * 2", "doubledRate")

        val result = runWithThrowingDialog {
            runExecutor {
                IntellijIntroduceParameterExecutor()
                    .introduceParameter(project, selection, "doubledRate")
            }
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertEquals("priceFor", result.methodName)
        assertEquals("doubledRate", result.parameterName)
        assertEquals("int", result.parameterType)
        assertEquals(2, result.parameterPosition)
        assertEquals(IntroduceParameterSourceKind.EXPRESSION, result.sourceKind)
        assertEquals(2, result.updatedCallSiteCount)
        assertEquals(fixture.sortedAffectedFiles, result.affectedFiles)

        val declaration = documentText(fixture.declarationPath)
        assertTrue(declaration.contains("int priceFor(int rate, final int doubledRate)"))
        assertTrue(declaration.contains("return doubledRate;"))
        assertFalse("the selected expression must be replaced in the body", declaration.contains("rate * 2"))
        assertTrue(
            "caller one must receive the caller-context argument",
            documentText(fixture.callerOnePath).contains("priceFor(3, 3 * 2)"),
        )
        assertTrue(
            "caller two must receive the caller-context argument",
            documentText(fixture.callerTwoPath).contains("priceFor(5, 5 * 2)"),
        )
    }

    fun testSuccessfulIntroduceParameterPersistsDeclarationAndAllCallers() {
        val fixture = priceServiceFixture()
        val selection = resolveExpression(fixture, "rate * 2", "doubledRate")
        val persister = RecordingNativeRefactoringDocumentPersister()

        runWithThrowingDialog {
            runExecutor {
                IntellijIntroduceParameterExecutor(documentPersistence = persister)
                    .introduceParameter(project, selection, "doubledRate")
            }
        }

        persister.assertPersistedExactly(
            "ParamService.java",
            "ParamCallerOne.java",
            "ParamCallerTwo.java",
        )
    }

    fun testExpressionChangesOnlyTheSelectedOccurrenceInsideTheTargetMethod() {
        val fixture = priceServiceFixture(body = "return rate * 2 + rate * 2;")
        val selection = resolveExpression(fixture, "rate * 2", "firstDoubled")

        runWithThrowingDialog {
            runExecutor {
                IntellijIntroduceParameterExecutor()
                    .introduceParameter(project, selection, "firstDoubled")
            }
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val declaration = documentText(fixture.declarationPath)
        assertTrue(declaration.contains("int priceFor(int rate, final int firstDoubled)"))
        assertTrue(
            "replace choice NO must leave the second same-method occurrence unchanged",
            declaration.contains("return firstDoubled + rate * 2;"),
        )
    }

    // --- Step 2: local executor ---

    fun testIntroducesParameterFromLocalVariableRemovesDeclarationAndUpdatesCallers() {
        val fixture = priceServiceFixture(body = "int doubled = rate * 2; return doubled + doubled;")
        val selection = resolveLocalVariable(fixture, "doubled")

        val result = runWithThrowingDialog {
            runExecutor {
                IntellijIntroduceParameterExecutor()
                    .introduceParameter(project, selection, "doubled")
            }
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertEquals("priceFor", result.methodName)
        assertEquals("doubled", result.parameterName)
        assertEquals("int", result.parameterType)
        assertEquals(2, result.parameterPosition)
        assertEquals(IntroduceParameterSourceKind.LOCAL_VARIABLE, result.sourceKind)
        assertEquals(2, result.updatedCallSiteCount)
        assertEquals(fixture.sortedAffectedFiles, result.affectedFiles)

        val declaration = documentText(fixture.declarationPath)
        assertTrue(declaration.contains("int priceFor(int rate, final int doubled)"))
        assertFalse("the local declaration must be removed", declaration.contains("int doubled = rate * 2"))
        assertTrue(
            "every local read must become the parameter",
            declaration.contains("return doubled + doubled;"),
        )
        assertTrue(
            "caller one must receive the caller-context argument",
            documentText(fixture.callerOnePath).contains("priceFor(3, 3 * 2)"),
        )
        assertTrue(
            "caller two must receive the caller-context argument",
            documentText(fixture.callerTwoPath).contains("priceFor(5, 5 * 2)"),
        )
    }

    // --- Step 6: one global Undo ---

    fun testOneGlobalUndoRestoresDeclarationAndEveryCallerExactly() {
        val fixture = priceServiceFixture()
        val selection = resolveExpression(fixture, "rate * 2", "doubledRate")
        val originals = fixture.affectedFiles.associateWith(::documentText)

        runExecutor {
            IntellijIntroduceParameterExecutor()
                .introduceParameter(project, selection, "doubledRate")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val undoManager = UndoManager.getInstance(project)
        assertTrue(
            "Introduce Parameter must be available as one global Undo",
            undoManager.isUndoAvailable(null),
        )
        val previousDialog = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undoManager.undo(null)
        } finally {
            TestDialogManager.setTestDialog(previousDialog)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        originals.forEach { (path, original) ->
            assertEquals(path, original, documentText(path))
        }
    }

    fun testOneUndoRestoresLocalSourceDeclarationAndCallersExactly() {
        val fixture = priceServiceFixture(body = "int doubled = rate * 2; return doubled;")
        val selection = resolveLocalVariable(fixture, "doubled")
        val originals = fixture.affectedFiles.associateWith(::documentText)

        runExecutor {
            IntellijIntroduceParameterExecutor()
                .introduceParameter(project, selection, "doubled")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val undoManager = UndoManager.getInstance(project)
        assertTrue("Introduce Parameter must be available as one global Undo", undoManager.isUndoAvailable(null))
        val previousDialog = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undoManager.undo(null)
        } finally {
            TestDialogManager.setTestDialog(previousDialog)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        originals.forEach { (path, original) ->
            assertEquals(path, original, documentText(path))
        }
    }

    fun testSelectedMethodDoesNotRewriteSameFileDuplicateMethodAndOneUndoRestoresEverything() {
        val fixture = priceServiceFixture(
            duplicateMethod = "public int duplicatePriceFor(int rate) { return rate * 2; }",
        )
        val selection = resolveExpression(fixture, "rate * 2", "doubledRate")
        val originals = fixture.affectedFiles.associateWith(::documentText)

        runWithThrowingDialog {
            runExecutor {
                IntellijIntroduceParameterExecutor()
                    .introduceParameter(project, selection, "doubledRate")
            }
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val declaration = documentText(fixture.declarationPath)
        assertTrue(declaration.contains("int priceFor(int rate, final int doubledRate)"))
        assertTrue(
            "only the selected method may change; an IDEA method duplicate must remain untouched",
            declaration.contains("int duplicatePriceFor(int rate) { return rate * 2; }"),
        )

        val undoManager = UndoManager.getInstance(project)
        assertTrue("Introduce Parameter must be available as one global Undo", undoManager.isUndoAvailable(null))
        val previousDialog = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undoManager.undo(null)
        } finally {
            TestDialogManager.setTestDialog(previousDialog)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        originals.forEach { (path, original) ->
            assertEquals(path, original, documentText(path))
        }
    }

    fun testStaleSelectionFailsBeforeNativeMutation() {
        val fixture = priceServiceFixture()
        val selection = resolveExpression(fixture, "rate * 2", "doubledRate")
        val declaration = document(fixture.declarationPath)
        val sourceStart = declaration.text.indexOf("rate * 2")
        WriteCommandAction.runWriteCommandAction(project) {
            declaration.replaceString(sourceStart, sourceStart + "rate * 2".length, "rate * 3")
        }
        val beforeExecution = fixture.affectedFiles.associateWith(::documentText)

        try {
            runExecutor {
                IntellijIntroduceParameterExecutor()
                    .introduceParameter(project, selection, "doubledRate")
            }
            fail("expected stale selection to be rejected before the native processor runs")
        } catch (e: IntroduceParameterPreparationException) {
            assertTrue(e.message.orEmpty().contains("changed"))
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        beforeExecution.forEach { (path, expected) ->
            assertEquals(path, expected, documentText(path))
        }
    }

    // --- Step 6: injected post-mutation inspection failure ---

    fun testInjectedPostMutationFailureRollsBackEveryDocumentViaNativeUndo() {
        val fixture = priceServiceFixture()
        val selection = resolveExpression(fixture, "rate * 2", "doubledRate")
        val originals = fixture.affectedFiles.associateWith(::documentText)
        val failingExecutor = IntellijIntroduceParameterExecutor(
            resultInspector = IntroduceParameterResultInspector { _, _, _, _, _, _, _ ->
                throw IllegalStateException("injected post-mutation failure")
            },
        )

        try {
            runExecutor {
                failingExecutor.introduceParameter(project, selection, "doubledRate")
            }
            fail("expected the injected post-mutation failure")
        } catch (e: IllegalStateException) {
            assertEquals("injected post-mutation failure", e.message)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        originals.forEach { (path, original) ->
            assertEquals(path, original, documentText(path))
        }
    }

    fun testInjectedPostMutationFailureRollsBackLocalSourceDocuments() {
        val fixture = priceServiceFixture(body = "int doubled = rate * 2; return doubled;")
        val selection = resolveLocalVariable(fixture, "doubled")
        val originals = fixture.affectedFiles.associateWith(::documentText)
        val failingExecutor = IntellijIntroduceParameterExecutor(
            resultInspector = IntroduceParameterResultInspector { _, _, _, _, _, _, _ ->
                throw IllegalStateException("injected post-mutation failure")
            },
        )

        try {
            runExecutor {
                failingExecutor.introduceParameter(project, selection, "doubled")
            }
            fail("expected the injected post-mutation failure")
        } catch (e: IllegalStateException) {
            assertEquals("injected post-mutation failure", e.message)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        originals.forEach { (path, original) ->
            assertEquals(path, original, documentText(path))
        }
    }

    // --- Step: genuine native conflict surfaces as IntroduceParameterConflictException ---

    fun testNativeNameConflictThrowsConflictExceptionAndMutatesNothing() {
        val fixture = priceServiceFixture()
        // Resolve with an accepted name, then hand the executor a name that clashes with the
        // existing parameter `rate` to reach the native processor's conflict detection (the
        // resolver rejects such a name up front, so it is only reachable at the executor boundary).
        val selection = resolveExpression(fixture, "rate * 2", "doubledRate")
        val originals = fixture.affectedFiles.associateWith(::documentText)

        try {
            runWithThrowingDialog {
                runExecutor {
                    IntellijIntroduceParameterExecutor()
                        .introduceParameter(project, selection, "rate")
                }
            }
            fail("expected IntroduceParameterConflictException for the native name conflict")
        } catch (e: IntroduceParameterConflictException) {
            assertTrue(
                "conflict message must name the clashing symbol",
                e.message.orEmpty().contains("rate"),
            )
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        originals.forEach { (path, original) ->
            assertEquals(path, original, documentText(path))
        }
    }

    // --- helpers ---

    private class ExecutorFixture(
        val declarationPath: String,
        val callerOnePath: String,
        val callerTwoPath: String,
        val affectedFiles: List<String>,
    ) {
        val sortedAffectedFiles: List<String> get() = affectedFiles.sorted()
    }

    /**
     * Creates the declaration and caller files with stable, distinctly-prefixed names so the shared
     * light-project index always holds one coherent set (there is exactly one ParamService/priceFor
     * in the project) and so these files never collide with the resolver suite's CallerOne/Two.
     */
    private fun priceServiceFixture(
        body: String = "return rate * 2;",
        duplicateMethod: String? = null,
    ): ExecutorFixture {
        val declarationPath = "ParamService.java"
        val callerOnePath = "ParamCallerOne.java"
        val callerTwoPath = "ParamCallerTwo.java"
        mirrorRealFile(
            declarationPath,
            """
                public class ParamService {
                    public int priceFor(int rate) { $body }
                    ${duplicateMethod.orEmpty()}
                }
            """.trimIndent(),
        )
        mirrorRealFile(
            callerOnePath,
            "class ParamCallerOne { int call() { return new ParamService().priceFor(3); } }",
        )
        mirrorRealFile(
            callerTwoPath,
            "class ParamCallerTwo { int also() { return new ParamService().priceFor(5); } }",
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return ExecutorFixture(
            declarationPath = declarationPath,
            callerOnePath = callerOnePath,
            callerTwoPath = callerTwoPath,
            affectedFiles = listOf(callerOnePath, callerTwoPath, declarationPath),
        )
    }

    private fun resolveExpression(
        fixture: ExecutorFixture,
        expressionText: String,
        parameterName: String,
    ): IntroduceParameterSelection {
        val document = document(fixture.declarationPath)
        val startOffset = document.text.indexOf(expressionText)
        require(startOffset >= 0) { "expression '$expressionText' missing" }
        val result = resolver.resolve(
            project,
            fixture.declarationPath,
            range(document, startOffset, startOffset + expressionText.length),
            parameterName,
        )
        assertTrue(
            "expected successful resolution but was $result",
            result is IntroduceParameterSelectionResolution.Success,
        )
        val selection = (result as IntroduceParameterSelectionResolution.Success).selection
        assertEquals(IntroduceParameterSourceKind.EXPRESSION, selection.sourceKind)
        return selection
    }

    private fun resolveLocalVariable(
        fixture: ExecutorFixture,
        variableName: String,
    ): IntroduceParameterSelection {
        val document = document(fixture.declarationPath)
        val needle = "int $variableName = "
        val nameStart = document.text.indexOf(needle) + "int ".length
        require(nameStart >= "int ".length) { "local variable '$variableName' missing" }
        val result = resolver.resolve(
            project,
            fixture.declarationPath,
            range(document, nameStart, nameStart + variableName.length),
            variableName,
        )
        assertTrue(
            "expected successful resolution but was $result",
            result is IntroduceParameterSelectionResolution.Success,
        )
        val selection = (result as IntroduceParameterSelectionResolution.Success).selection
        assertEquals(IntroduceParameterSourceKind.LOCAL_VARIABLE, selection.sourceKind)
        return selection
    }

    private fun mirrorRealFile(fileName: String, text: String): VirtualFile {
        val target = Path.of(project.basePath!!, fileName)
        Files.createDirectories(target.parent)
        if (!Files.exists(target)) Files.createFile(target)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(target.toString())!!
        // Write through the VFS so any cached document/PSI is reloaded across tests; raw disk
        // writes leave stale in-memory documents that trigger MemoryDiskConflict in later runs.
        WriteAction.run<RuntimeException> { VfsUtil.saveText(virtualFile, text) }
        return virtualFile
    }

    /** Runs [block] with a [TestDialog] installed that throws, proving no UI was requested. */
    private fun <T> runWithThrowingDialog(block: () -> T): T {
        val throwingDialog = object : TestDialog {
            override fun show(message: String): Int =
                throw AssertionError("Introduce Parameter must not open a dialog: $message")
        }
        val previous = TestDialogManager.setTestDialog(throwingDialog)
        try {
            return block()
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
    }

    private fun range(document: Document, startOffset: Int, endOffset: Int): SourceRange {
        fun position(offset: Int): Pair<Int, Int> {
            val line = document.getLineNumber(offset)
            return (line + 1) to (offset - document.getLineStartOffset(line) + 1)
        }

        val (startLine, startColumn) = position(startOffset)
        val (endLine, endColumn) = position(endOffset)
        return SourceRange(startLine, startColumn, endLine, endColumn)
    }

    private fun documentText(path: String): String = document(path).text

    private fun document(path: String): Document =
        FileDocumentManager.getInstance().getDocument(virtualFile(path))!!

    private fun virtualFile(path: String): VirtualFile = LocalFileSystem.getInstance()
        .findFileByPath(Path.of(project.basePath!!, path).toString())!!

    private fun <T> runExecutor(block: suspend () -> T): T {
        val pool = Executors.newSingleThreadExecutor()
        return try {
            val future = pool.submit<T> { runBlocking { block() } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
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
