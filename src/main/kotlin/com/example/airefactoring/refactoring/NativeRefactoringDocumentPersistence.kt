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
            val unsavedPaths = documents
                .filter { (_, document) -> isUnsaved(document) }
                .map { (file) -> file.path }
            if (unsavedPaths.isNotEmpty()) {
                throw NativeRefactoringPersistenceException(
                    "Could not confirm saving native refactoring document(s): " +
                        unsavedPaths.joinToString(),
                )
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
}

internal class NativeRefactoringPersistenceException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
