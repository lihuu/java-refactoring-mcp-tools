package com.example.airefactoring.refactoring.extractmethodobject

import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersistence
import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersister
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiMethod
import com.intellij.refactoring.extractMethod.PrepareFailedException
import com.intellij.refactoring.extractMethodObject.ExtractMethodObjectHandler
import com.intellij.refactoring.extractMethodObject.ExtractMethodObjectProcessor
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IntellijExtractMethodObjectExecutor internal constructor(
    private val documentPersistence: NativeRefactoringDocumentPersister = NativeRefactoringDocumentPersistence(),
) : ExtractMethodObjectExecutor {

    override suspend fun replace(
        project: Project,
        preparation: ExtractMethodObjectPreparation,
    ): ExtractMethodObjectExecutionResult = withContext(Dispatchers.EDT) {
        val method = requireCurrentMethod(preparation)

        // Freshness: the method text must be unchanged since resolution.
        if (method.text != preparation.methodTextSnapshot) {
            throw ExtractMethodObjectPreparationException(
                "The method changed before the native refactoring could run.",
            )
        }

        val command = Runnable {
            val processor = ExtractMethodObjectProcessor(
                project,
                null,
                method.body!!.statements,
                preparation.methodObjectClassName,
            )
            processor.setCreateInnerClass(true)
            val ep = processor.getExtractProcessor()
            ep.setShowErrorDialogs(false)
            ep.setPreviewSupported(false)
            try {
                if (!ep.prepare()) {
                    throw ExtractMethodObjectPreparationException(
                        "The method cannot be replaced with a method object.",
                    )
                }
            } catch (e: PrepareFailedException) {
                throw ExtractMethodObjectPreparationException(
                    "The method cannot be replaced with a method object: ${e.message}",
                    e,
                )
            }
            ep.setMethodName(preparation.methodObjectMethodName)
            ep.setDataFromInputVariables()
            try {
                ExtractMethodObjectHandler.extractMethodObject(project, null, processor, ep)
            } catch (e: Exception) {
                if (isConflict(e)) {
                    throw ExtractMethodObjectConflictException(
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

        buildResult(project, method, preparation)
    }

    private fun requireCurrentMethod(preparation: ExtractMethodObjectPreparation): PsiMethod {
        val method = preparation.methodPointer.element?.takeIf { it.isValid }
            ?: throw ExtractMethodObjectPreparationException(
                "The method changed before the native refactoring could run.",
            )
        return method
    }

    private fun buildResult(
        project: Project,
        method: PsiMethod,
        preparation: ExtractMethodObjectPreparation,
    ): ExtractMethodObjectExecutionResult {
        val containing = method.containingClass
        val methodObjectClass = if (containing != null) {
            "${containing.qualifiedName}.${preparation.methodObjectClassName}"
        } else {
            preparation.methodObjectClassName
        }
        val inner = containing?.innerClasses?.firstOrNull { it.name == preparation.methodObjectClassName }
        val migratedFieldCount = inner?.fields?.size ?: 0

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

        return ExtractMethodObjectExecutionResult(
            methodName = method.name,
            methodObjectClass = methodObjectClass,
            methodObjectMethodName = preparation.methodObjectMethodName,
            migratedFieldCount = migratedFieldCount,
            affectedFiles = affectedRel,
            summary = "Replaced method '${method.name}' with method object '$methodObjectClass'.",
        )
    }

    private fun isConflict(e: Throwable): Boolean {
        if (e.javaClass.simpleName == "ConflictsInTestsException") return true
        if (e.message?.contains("conflict", ignoreCase = true) == true) return true
        val cause = e.cause
        return cause != null && cause.javaClass.simpleName == "ConflictsInTestsException"
    }
}
