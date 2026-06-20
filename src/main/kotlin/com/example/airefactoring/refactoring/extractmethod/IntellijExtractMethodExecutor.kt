package com.example.airefactoring.refactoring.extractmethod

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.extractMethod.ExtractMethodHandler as PlatformExtractMethodHandler
import com.intellij.refactoring.extractMethod.PrepareFailedException

class IntellijExtractMethodExecutor : ExtractMethodExecutor {

    override fun extract(
        project: Project,
        file: PsiFile,
        elements: Array<PsiElement>,
        methodName: String,
    ): String {
        val app = ApplicationManager.getApplication()
        val command = Runnable {
            val processor = PlatformExtractMethodHandler.getProcessor(project, elements, file, false)
                ?: throw IllegalStateException("Cannot extract the selected code into a method.")
            try {
                processor.prepare()
            } catch (e: PrepareFailedException) {
                throw IllegalStateException("Cannot extract: ${e.message}")
            }
            processor.setMethodName(methodName)
            PlatformExtractMethodHandler.extractMethod(project, processor)
        }
        if (app.isUnitTestMode) {
            CommandProcessor.getInstance().executeCommand(project, command, "AI Extract Method", null)
        } else {
            app.invokeAndWait {
                CommandProcessor.getInstance().executeCommand(project, command, "AI Extract Method", null)
            }
        }
        return "Extracted method '$methodName'."
    }
}
