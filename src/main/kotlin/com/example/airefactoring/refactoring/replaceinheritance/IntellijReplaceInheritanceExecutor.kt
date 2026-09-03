package com.example.airefactoring.refactoring.replaceinheritance

import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersistence
import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersister
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.refactoring.inheritanceToDelegation.InheritanceToDelegationProcessor
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IntellijReplaceInheritanceExecutor internal constructor(
    private val documentPersistence: NativeRefactoringDocumentPersister = NativeRefactoringDocumentPersistence(),
) : ReplaceInheritanceWithDelegationExecutor {

    override suspend fun execute(
        project: Project,
        preparation: ReplaceInheritanceWithDelegationPreparation,
    ): ReplaceInheritanceWithDelegationExecutionResult {
        // Drain pending external VFS refreshes for the target files while documents are still clean:
        // a refresh landing after the processor dirties a document would raise the modal
        // memory-disk conflict dialog and wedge the unattended IDE.
        withContext(Dispatchers.Default) {
            LocalFileSystem.getInstance().refreshIoFiles(
                preparation.affectedVirtualFiles.map { java.io.File(it.path) },
                /* async: */ false,
                /* recursive: */ false,
                null,
            )
        }
        return executeOnEdt(project, preparation)
    }

    private suspend fun executeOnEdt(
        project: Project,
        preparation: ReplaceInheritanceWithDelegationPreparation,
    ): ReplaceInheritanceWithDelegationExecutionResult = withContext(Dispatchers.EDT) {
        val cls = requireCurrentClass(preparation)

        // Freshness: class text must still match what was resolved.
        if (cls.text != preparation.classTextSnapshot) {
            throw ReplaceInheritancePreparationException(
                "The class changed before the native refactoring could run.",
            )
        }

        val baseClass = withContext(Dispatchers.Default) {
            readAction {
                com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(
                    preparation.targetBaseClassFqn,
                    com.intellij.psi.search.GlobalSearchScope.projectScope(project)
                ) ?: throw ReplaceInheritancePreparationException(
                    "The target base class '${preparation.targetBaseClassFqn}' is no longer available."
                )
            }
        }

        val command = Runnable {
            try {
                CommandProcessor.getInstance().executeCommand(project, {
                    // The processor constructor resolves the full base-class member set through
                    // stub indexes (getAllBaseClassMembers / getOverriddenMethods / GenerateMembersUtil),
                    // which the platform classifies as slow operations on the EDT. The native UI flow
                    // runs the processor inside its own write command, where the platform allows slow
                    // operations; the headless driver replicates that by running constructor+run inside
                    // one WriteCommandAction nested in the outer undo command (single-Undo grouping
                    // stays with the outer command, as spike-verified for Extract Delegate).
                    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(
                        project,
                        {
                            InheritanceToDelegationProcessor(
                                project,
                                cls,
                                baseClass,
                                preparation.fieldName,
                                "", // innerClassName
                                emptyArray(), // delegatedInterfaces
                                emptyArray(), // delegatedMethods,
                                preparation.delegateOtherMembers,
                                preparation.generateGetter,
                            ).run()
                        },
                    )
                }, "Replace Inheritance with Delegation", null)
            } catch (e: Exception) {
                if (isConflict(e)) {
                    throw ReplaceInheritanceConflictException(e.message ?: "Refactoring conflict.", e)
                }
                throw e
            }
            val persistables = preparation.affectedVirtualFiles
            documentPersistence.persist(project, persistables)
        }

        if (ApplicationManager.getApplication().isDispatchThread) {
            command.run()
        } else {
            ApplicationManager.getApplication().invokeAndWait(command)
        }

        buildResult(project, preparation)
    }

    private fun requireCurrentClass(preparation: ReplaceInheritanceWithDelegationPreparation): PsiClass {
        val cls = preparation.classPointer.element?.takeIf { it.isValid }
            ?: throw ReplaceInheritancePreparationException(
                "The class changed before the native refactoring could run.",
            )
        return cls
    }

    private fun buildResult(
        project: Project,
        preparation: ReplaceInheritanceWithDelegationPreparation,
    ): ReplaceInheritanceWithDelegationExecutionResult {
        val basePath = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() }
        val affectedRel = preparation.affectedVirtualFiles
            .filter { it.isValid }
            .distinct()
            .mapNotNull { vf ->
                basePath?.let {
                    try {
                        val abs = Path.of(vf.path).toAbsolutePath().normalize()
                        if (abs.startsWith(it)) it.relativize(abs).toString() else null
                    } catch (_: Exception) {
                        null
                    }
                } ?: vf.path
            }
            .sorted()

        return ReplaceInheritanceWithDelegationExecutionResult(
            sourceClass = preparation.sourceClassFqn,
            affectedFiles = affectedRel,
            summary = "Successfully replaced inheritance from '${preparation.targetBaseClassFqn}' with delegation in '${preparation.sourceClassFqn}' using field '${preparation.fieldName}'."
        )
    }

    private fun isConflict(e: Throwable): Boolean {
        if (e.javaClass.simpleName == "ConflictsInTestsException") return true
        if (e.message?.contains("conflict", ignoreCase = true) == true) return true
        val cause = e.cause
        return cause != null && cause.javaClass.simpleName == "ConflictsInTestsException"
    }
}

class ReplaceInheritancePreparationException(message: String) : Exception(message)
class ReplaceInheritanceConflictException(message: String, cause: Throwable) : Exception(message, cause)
