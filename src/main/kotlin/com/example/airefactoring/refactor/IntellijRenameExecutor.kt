package com.example.airefactoring.refactor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.RefactoringFactory
import com.intellij.refactoring.rename.RenameProcessor

class IntellijRenameExecutor : RenameExecutor {

    override fun rename(project: Project, element: PsiNamedElement, newName: String, preview: Boolean) {
        val app = ApplicationManager.getApplication()
        val command = Runnable {
            if (preview) {
                RenameProcessor(
                    project,
                    element,
                    newName,
                    /*searchInComments*/ false,
                    /*searchTextOccurrences*/ false,
                ).also { it.setPreviewUsages(false) }.run()
            } else {
                RefactoringFactory.getInstance(project)
                    .createRename(element, newName, false, false)
                    .run()
            }
        }
        if (app.isUnitTestMode) {
            // RefactoringFactory#run handles its own write action; CommandProcessor groups undo.
            CommandProcessor.getInstance().executeCommand(project, command, "AI Rename Symbol", null)
        } else {
            app.invokeAndWait {
                CommandProcessor.getInstance().executeCommand(project, command, "AI Rename Symbol", null)
            }
        }
    }
}
