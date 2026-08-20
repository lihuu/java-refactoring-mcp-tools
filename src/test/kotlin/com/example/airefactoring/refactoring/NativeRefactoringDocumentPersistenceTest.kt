package com.example.airefactoring.refactoring

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path

class NativeRefactoringDocumentPersistenceTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testPersistsOnlyExplicitAffectedDocument() {
        val affected = mirrorRealFile("Affected.java", "class A { int value = 1; }")
        val unrelated = mirrorRealFile("Unrelated.java", "class U { int value = 2; }")
        val fileDocumentManager = FileDocumentManager.getInstance()
        val affectedDocument = fileDocumentManager.getDocument(affected)!!
        val unrelatedDocument = fileDocumentManager.getDocument(unrelated)!!

        WriteCommandAction.runWriteCommandAction(project) {
            affectedDocument.insertString(affectedDocument.textLength, "\n// persisted")
            unrelatedDocument.insertString(unrelatedDocument.textLength, "\n// remains dirty")
        }

        NativeRefactoringDocumentPersistence().persist(project, setOf(affected))

        assertFalse(fileDocumentManager.isDocumentUnsaved(affectedDocument))
        assertTrue(fileDocumentManager.isDocumentUnsaved(unrelatedDocument))
        assertEquals(affectedDocument.text, Files.readString(Path.of(affected.path)))
        assertTrue(unrelatedDocument.text.contains("// remains dirty"))
    }

    fun testSaveFailureIsReportedWithAffectedPath() {
        val affected = mirrorRealFile("SaveFailure.java", "class SaveFailure {}")

        val exception = expectPersistenceFailure {
            NativeRefactoringDocumentPersistence(
                save = { throw IllegalStateException("injected save failure") },
            ).persist(project, setOf(affected))
        }

        assertTrue(exception.message.orEmpty().contains(affected.path))
        assertTrue(exception.message.orEmpty().contains("Could not confirm saving"))
        assertEquals("injected save failure", exception.cause?.message)
    }

    fun testUnsavedDocumentAfterSaveIsReportedWithAffectedPath() {
        val affected = mirrorRealFile("StillDirty.java", "class StillDirty {}")

        val exception = expectPersistenceFailure {
            NativeRefactoringDocumentPersistence(
                isUnsaved = { true },
            ).persist(project, setOf(affected))
        }

        assertTrue(exception.message.orEmpty().contains(affected.path))
        assertTrue(exception.message.orEmpty().contains("Could not confirm saving"))
    }

    private fun mirrorRealFile(relativePath: String, text: String): VirtualFile {
        val root = Path.of(project.basePath!!)
        val diskPath = root.resolve(relativePath)
        Files.createDirectories(diskPath.parent)
        Files.writeString(diskPath, text)
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(diskPath)!!
            .also { VfsUtil.markDirtyAndRefresh(false, false, false, it) }
    }

    private fun expectPersistenceFailure(action: () -> Unit): NativeRefactoringPersistenceException {
        try {
            action()
        } catch (exception: NativeRefactoringPersistenceException) {
            return exception
        }
        fail("expected NativeRefactoringPersistenceException")
        throw AssertionError("unreachable")
    }
}
