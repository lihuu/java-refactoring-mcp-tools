package com.example.airefactoring.refactoring.extractmethod

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.project.Project
import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersistence
import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersister
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.extractMethod.ExtractMethodHandler as PlatformExtractMethodHandler
import com.intellij.refactoring.extractMethod.PrepareFailedException

class IntellijExtractMethodExecutor internal constructor(
    private val documentPersistence: NativeRefactoringDocumentPersister =
        NativeRefactoringDocumentPersistence(),
) : ExtractMethodExecutor {

    override fun extract(
        project: Project,
        file: PsiFile,
        elements: Array<PsiElement>,
        methodName: String,
    ): String {
        val command = Runnable {
            val processor = PlatformExtractMethodHandler.getProcessor(project, elements, file, false)
                ?: throw ExtractMethodPreparationException(
                    "The selected code cannot be extracted into a method."
                )
            try {
                processor.prepare()
            } catch (e: PrepareFailedException) {
                throw ExtractMethodPreparationException(
                    "The selected code cannot be extracted: ${e.message}",
                    e,
                )
            }
            processor.setMethodName(methodName)
            // IDEA 2026.1.3: the headless path no longer initializes the parameter datum
            // (prepareVariablesAndName became @TestOnly and the dialog's apply() is skipped), so
            // populate it explicitly or generateEmptyMethod() NPEs on a null array.
            processor.setDataFromInputVariables()
            PlatformExtractMethodHandler.extractMethod(project, processor)
            documentPersistence.persist(project, setOf(file.virtualFile))
        }

        val wrapped = Runnable {
            CommandProcessor.getInstance().executeCommand(
                project,
                command,
                "MCP Extract Method",
                null,
            )
        }
        if (ApplicationManager.getApplication().isDispatchThread) wrapped.run()
        else ApplicationManager.getApplication().invokeAndWait(wrapped)
        return "Extracted method '$methodName'."
    }
}
