package com.example.airefactoring.refactoring.extractdelegate

import com.example.airefactoring.refactoring.RecordingNativeRefactoringDocumentPersister
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path

class IntellijExtractDelegateExecutorTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = ExtractDelegateSelectionResolver()

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testExtractsSiblingPersistsExactFilesAndUndoRestores() {
        val sourcePath = "example/ExSiblingService.java"
        val callerPath = "example/ExSiblingClient.java"
        mirrorRealFile(
            sourcePath,
            """
                package example;
                public class ExSiblingService {
                    public double unitPrice = 2.5;
                    public double price(int q) { return q * unitPrice; }
                    public double total() { return price(2) - 1; }
                }
            """.trimIndent(),
        )
        mirrorRealFile(
            callerPath,
            """
                package example;
                public class ExSiblingClient {
                    void call() { new ExSiblingService().price(5); }
                }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val prep = resolve(sourcePath, "ExSiblingService", listOf("unitPrice"), listOf("price"), "ExDelegate", false)
        val before = listOf(sourcePath, callerPath).associateWith { documentText(it) }
        val persister = RecordingNativeRefactoringDocumentPersister()

        val result = runWithNoDialog {
            IntellijExtractDelegateExecutor(persister).extract(project, prep)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        LocalFileSystem.getInstance().refresh(false)

        assertEquals("example.ExSiblingService", result.sourceClass)
        assertEquals("example.ExDelegate", result.createdClass)
        assertTrue(result.affectedFiles.any { it.contains("ExSiblingService.java") })
        assertTrue(result.affectedFiles.any { it.contains("ExSiblingClient.java") })
        assertTrue("delegation call must appear on the source", documentText(sourcePath).contains("price(2"))
        assertTrue("moved implementation must leave the source", !documentText(sourcePath).contains("q * unitPrice"))
        persister.assertPersistedExactly(*result.affectedFiles.toTypedArray())

        val um = UndoManager.getInstance(project)
        assertTrue("Extract Delegate must be one global Undo", um.isUndoAvailable(null))
        val prev = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            um.undo(null)
        } finally {
            TestDialogManager.setTestDialog(prev)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        LocalFileSystem.getInstance().refresh(false)
        before.forEach { (p, txt) -> assertEquals(p, txt, documentText(p)) }
        val delegateVf = LocalFileSystem.getInstance().findFileByPath(
            Path.of(project.basePath!!, "example/ExDelegate.java").toString(),
        )
        assertTrue("one Undo must remove the generated sibling file", delegateVf == null)
    }

    fun testExtractsNestedPlacementAndUndoRestores() {
        val sourcePath = "example/ExNestedService.java"
        mirrorRealFile(
            sourcePath,
            """
                package example;
                public class ExNestedService {
                    public double price(int q, double u) { return q * u; }
                }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val prep = resolve(sourcePath, "ExNestedService", emptyList(), listOf("price"), "PriceCalculator", true)
        val before = sourcePath.let { it to documentText(it) }
        val persister = RecordingNativeRefactoringDocumentPersister()

        val result = runWithNoDialog {
            IntellijExtractDelegateExecutor(persister).extract(project, prep)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertEquals("example.ExNestedService.PriceCalculator", result.createdClass)
        val afterText = documentText(sourcePath)
        assertTrue("nested delegate must be created on the source", afterText.contains("class PriceCalculator"))
        assertTrue("kept call sites must be rewritten to the delegate", afterText.contains("PriceCalculator") && !afterText.contains("public double price(int q, double u) { return q * u; }"))
        persister.assertPersistedExactly(*result.affectedFiles.toTypedArray())

        val um = UndoManager.getInstance(project)
        assertTrue("Extract Delegate must be one global Undo", um.isUndoAvailable(null))
        val prev = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            um.undo(null)
        } finally {
            TestDialogManager.setTestDialog(prev)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(before.second, documentText(before.first))
        assertTrue("generated nested class must be gone", !documentText(before.first).contains("class PriceCalculator"))
    }

    fun testRejectsStaleClassOrMemberSnapshotBeforeMutation() {
        val sourcePath = "example/ExStaleService.java"
        mirrorRealFile(
            sourcePath,
            """
                package example;
                public class ExStaleService {
                    public double price(int q) { return q; }
                }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val prep = resolve(sourcePath, "ExStaleService", emptyList(), listOf("price"), "ExStaleDelegate", false)
        val before = documentText(sourcePath)

        // Mutate the class text after resolve.
        val newText = before.replace("public double price", "public double price2")
        val vf = LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, sourcePath).toString())!!
        WriteAction.run<RuntimeException> { VfsUtil.saveText(vf, newText) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        try {
            runWithNoDialog { IntellijExtractDelegateExecutor().extract(project, prep) }
            fail("expected stale preparation to be rejected")
        } catch (e: ExtractDelegatePreparationException) {
            assertTrue(e.message!!.isNotBlank())
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals("stale request must not mutate sources", newText, documentText(sourcePath))
    }

    fun testMapsNativeConflictsWithoutDialogAndPersistsNothing() {
        val sourcePath = "example/ExConflictService.java"
        mirrorRealFile(
            sourcePath,
            """
                package example;
                public class ExConflictService {
                    private int discount = 1;
                    public double total() { return discount; }
                }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeForClass(sourcePath, "ExConflictService")
        val res = resolver.resolve(
            project, sourcePath, range,
            extractedFields = emptyList(), extractedMethods = listOf("total"),
            newClassName = "ExConflictDelegate", extractInnerClass = false,
        )
        assertTrue("expected Success but was $res", res is ExtractDelegateSelectionResolution.Success)
        val prep = (res as ExtractDelegateSelectionResolution.Success).preparation
        val before = documentText(sourcePath)
        val persister = RecordingNativeRefactoringDocumentPersister()

        // generateAccessors=false reproduces a native getter conflict deterministically,
        // proving the executor maps it to a structured failure and persists nothing.
        try {
            runWithNoDialog {
                IntellijExtractDelegateExecutor(persister, generateAccessors = false).extract(project, prep)
            }
            fail("expected a native conflict to be mapped to ExtractDelegateConflictException")
        } catch (e: ExtractDelegateConflictException) {
            assertTrue(e.message!!.isNotBlank())
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals("conflict must not mutate sources", before, documentText(sourcePath))
        persister.assertPersistedNothing()
    }

    private fun resolve(
        path: String,
        className: String,
        fields: List<String>,
        methods: List<String>,
        newClassName: String,
        inner: Boolean,
    ): ExtractDelegatePreparation {
        val range = rangeForClass(path, className)
        val res = resolver.resolve(
            project, path, range,
            extractedFields = fields, extractedMethods = methods,
            newClassName = newClassName, extractInnerClass = inner,
        )
        assertTrue("expected Success but was $res", res is ExtractDelegateSelectionResolution.Success)
        return (res as ExtractDelegateSelectionResolution.Success).preparation
    }

    private fun rangeForClass(path: String, className: String): SourceRange {
        val doc = document(path)
        val off = doc.text.indexOf(className)
        assertTrue("class $className not found in $path", off >= 0)
        return range(doc, off, off + className.length)
    }

    private fun range(doc: com.intellij.openapi.editor.Document, startOff: Int, endOff: Int): SourceRange {
        fun pos(off: Int): Pair<Int, Int> {
            val line = doc.getLineNumber(off)
            return (line + 1) to (off - doc.getLineStartOffset(line) + 1)
        }
        val (sl, sc) = pos(startOff)
        val (el, ec) = pos(endOff)
        return SourceRange(sl, sc, el, ec)
    }

    private fun document(path: String): com.intellij.openapi.editor.Document {
        val vf = LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, path).toString())!!
        return FileDocumentManager.getInstance().getDocument(vf)!!
    }

    private fun documentText(path: String): String = document(path).text

    private fun mirrorRealFile(path: String, text: String): com.intellij.openapi.vfs.VirtualFile {
        val t = Path.of(project.basePath!!, path)
        Files.createDirectories(t.parent)
        if (!Files.exists(t)) Files.createFile(t)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(t.toString())!!
        WriteAction.run<RuntimeException> { VfsUtil.saveText(vf, text) }
        return vf
    }

    private fun <T> runExecutor(block: suspend () -> T): T =
        com.example.airefactoring.refactoring.runExecutorOffEdt(block)

    private fun <T> runWithNoDialog(block: suspend () -> T): T {
        val throwing = object : TestDialog {
            override fun show(message: String): Int =
                throw AssertionError("must not show dialog: $message")
        }
        val prev = TestDialogManager.setTestDialog(throwing)
        try {
            return runExecutor(block)
        } finally {
            TestDialogManager.setTestDialog(prev)
        }
    }
}