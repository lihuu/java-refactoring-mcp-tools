package com.example.airefactoring.refactoring.moveclass

import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersistence
import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersister
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.refactoring.JavaRefactoringFactory
import com.intellij.refactoring.move.moveClassesOrPackages.MoveClassesOrPackagesProcessor
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IntellijMoveClassExecutor internal constructor(
    private val documentPersistence: NativeRefactoringDocumentPersister = NativeRefactoringDocumentPersistence(),
) : MoveClassExecutor {

    override suspend fun move(
        project: Project,
        preparation: MoveClassPreparation,
    ): MoveClassExecutionResult = withContext(Dispatchers.EDT) {
        val cls = requireCurrentClass(preparation)

        // Freshness: the class text and target package must be unchanged since resolution.
        if (cls.text != preparation.classTextSnapshot) {
            throw MoveClassPreparationException(
                "The class changed before the native refactoring could run.",
            )
        }

        // MoveDestination creation and processor construction touch the file system / indexes,
        // which are slow operations; run them off-EDT in a read action. The native mutation stays on EDT.
        val processor: MoveClassesOrPackagesProcessor = withContext(Dispatchers.Default) {
            ReadAction.compute<MoveClassesOrPackagesProcessor, RuntimeException> {
                val moveDest = JavaRefactoringFactory.getInstance(project)
                    .createSourceFolderPreservingMoveDestination(preparation.targetPackage)
                MoveClassesOrPackagesProcessor(
                    project,
                    arrayOf(cls),
                    moveDest,
                    false,
                    false,
                    null,
                ).apply {
                    setSearchInComments(false)
                    setSearchInNonJavaFiles(false)
                }
            }
        }

        val command = Runnable {
            try {
                processor.run()
            } catch (e: Exception) {
                if (isConflict(e)) {
                    throw MoveClassConflictException(
                        e.message ?: "Refactoring conflict.",
                        e,
                    )
                }
                throw e
            }
            documentPersistence.persist(project, preparation.affectedVirtualFiles)
        }

        if (ApplicationManager.getApplication().isDispatchThread) {
            command.run()
        } else {
            ApplicationManager.getApplication().invokeAndWait(command)
        }

        buildResult(project, preparation)
    }

    private fun requireCurrentClass(preparation: MoveClassPreparation): PsiClass {
        val cls = preparation.classPointer.element?.takeIf { it.isValid }
            ?: throw MoveClassPreparationException(
                "The class changed before the native refactoring could run.",
            )
        return cls
    }

    private fun buildResult(
        project: Project,
        preparation: MoveClassPreparation,
    ): MoveClassExecutionResult {
        val basePath = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() }
        val affectedRel = preparation.affectedVirtualFiles
            .filter { it.isValid }
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

        return MoveClassExecutionResult(
            sourceClass = preparation.sourceClassFqn,
            targetPackage = preparation.targetPackage,
            affectedFiles = affectedRel,
            summary = "Moved class '${preparation.sourceClassFqn}' to package '${preparation.targetPackage}'.",
        )
    }

    private fun isConflict(e: Throwable): Boolean {
        if (e.javaClass.simpleName == "ConflictsInTestsException") return true
        if (e.message?.contains("conflict", ignoreCase = true) == true) return true
        val cause = e.cause
        return cause != null && cause.javaClass.simpleName == "ConflictsInTestsException"
    }
}
