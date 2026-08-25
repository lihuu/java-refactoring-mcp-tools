package com.example.airefactoring.refactoring

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager

internal fun interface NativeRefactoringDocumentPersister {
    fun persist(project: Project, affectedFiles: Collection<VirtualFile>)
}

/**
 * Completes a native refactoring write by committing PSI and saving only the files the caller
 * identified as affected. It deliberately never calls saveAllDocuments().
 */
internal class NativeRefactoringDocumentPersistence(
    private val commitAllDocuments: (Project) -> Unit = {
        PsiDocumentManager.getInstance(it).commitAllDocuments()
    },
    private val documentFor: (VirtualFile) -> Document? = {
        FileDocumentManager.getInstance().getDocument(it)
    },
    private val save: (Document) -> Unit = {
        FileDocumentManager.getInstance().saveDocument(it)
    },
    private val isUnsaved: (Document) -> Boolean = {
        FileDocumentManager.getInstance().isDocumentUnsaved(it)
    },
) : NativeRefactoringDocumentPersister {

    override fun persist(project: Project, affectedFiles: Collection<VirtualFile>) {
        val files = affectedFiles
            .asSequence()
            .filter { it.isValid }
            .distinctBy { it.path }
            .sortedBy { it.path }
            .toList()
        try {
            commitAllDocuments(project)
            val documents = files.mapNotNull { file ->
                documentFor(file)?.let { file to it }
            }
            documents.forEach { (_, document) -> save(document) }
            var unsavedPaths = documents
                .filter { (_, document) -> isUnsaved(document) }
                .map { (file) -> file.path }
            if (unsavedPaths.isNotEmpty()) {
                // Inline Method and other processors may leave documents dirty due to
                // post-refactoring VFS refresh / dumb mode. Retry once after re-commit.
                commitAllDocuments(project)
                documents.forEach { (_, document) ->
                    if (isUnsaved(document)) save(document)
                }
                unsavedPaths = documents
                    .filter { (_, document) -> isUnsaved(document) }
                    .map { (file) -> file.path }
                // If still unsaved but files on disk already reflect the document text,
                // treat as success to avoid false REFACTORING_FAILED after a correct mutation.
                if (unsavedPaths.isNotEmpty()) {
                    val stillDirty = documents.filter { (file, document) ->
                        isUnsaved(document) && !fileContentsMatchDocument(file, document)
                    }.map { (file) -> file.path }
                    if (stillDirty.isNotEmpty()) {
                        throw NativeRefactoringPersistenceException(
                            "Could not confirm saving native refactoring document(s): " +
                                stillDirty.joinToString(),
                        )
                    }
                }
            }
        } catch (exception: NativeRefactoringPersistenceException) {
            throw exception
        } catch (exception: Exception) {
            throw NativeRefactoringPersistenceException(
                "Could not confirm saving native refactoring document(s): " +
                    files.joinToString { it.path },
                exception,
            )
        }
    }

    private fun fileContentsMatchDocument(file: VirtualFile, document: Document): Boolean {
        return try {
            val bytes = file.contentsToByteArray()
            val fileText = String(bytes, Charsets.UTF_8)
            // Normalize line endings for comparison
            fileText.replace("\r\n", "\n") == document.text.replace("\r\n", "\n")
        } catch (_: Exception) {
            false
        }
    }
}

internal class NativeRefactoringPersistenceException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
