package com.example.airefactoring.refactoring.extractdelegate

import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersistence
import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersister
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.refactoring.extractclass.ExtractClassProcessor
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IntellijExtractDelegateExecutor internal constructor(
    private val documentPersistence: NativeRefactoringDocumentPersister = NativeRefactoringDocumentPersistence(),
    private val generateAccessors: Boolean = true,
) : ExtractDelegateExecutor {

    override suspend fun extract(
        project: Project,
        preparation: ExtractDelegatePreparation,
    ): ExtractDelegateExecutionResult = withContext(Dispatchers.EDT) {
        val cls = requireCurrentClass(preparation)

        // Freshness: class text and member names must still match what was resolved.
        if (cls.text != preparation.classTextSnapshot) {
            throw ExtractDelegatePreparationException(
                "The class changed before the native refactoring could run.",
            )
        }
        val fields: List<PsiField> = withContext(Dispatchers.Default) {
            ReadAction.compute<List<PsiField>, RuntimeException> {
                preparation.extractedFields.map { name ->
                    cls.findFieldByName(name, false)
                        ?: throw ExtractDelegatePreparationException(
                            "The extracted field '$name' changed before the native refactoring could run.",
                        )
                }
            }
        }
        val methods: List<PsiMethod> = withContext(Dispatchers.Default) {
            ReadAction.compute<List<PsiMethod>, RuntimeException> {
                preparation.extractedMethods.flatMap { name ->
                    val candidates = cls.findMethodsByName(name, false)
                        .filter { !it.isConstructor && !it.hasModifierProperty(PsiModifier.ABSTRACT) }
                    if (candidates.size != 1) {
                        throw ExtractDelegatePreparationException(
                            "The extracted method '$name' changed before the native refactoring could run.",
                        )
                    }
                    candidates
                }
            }
        }

        var createdClass: PsiClass? = null
        val command = Runnable {
            try {
                // The processor constructor eagerly builds the extracted class inside its own
                // WriteCommandAction; wrapping constructor+run() in one outer command is what makes
                // the native writes a single Undo unit (spike-measured; without the wrap the restore
                // leaves a source-file residue).
                CommandProcessor.getInstance().executeCommand(project, {
                    val packageName = (cls.containingFile as PsiJavaFile).packageName ?: ""
                    val processor = ExtractClassProcessor(
                        cls,
                        fields,
                        methods,
                        /* innerClasses: */ emptyList<PsiClass>(),
                        /* newPackageName: */ packageName,
                        /* moveDestination: */ null,
                        preparation.newClassName,
                        /* newVisibility: */ null,
                        /* generateAccessors: */ generateAccessors,
                        /* enumConstants: */ emptyList(),
                        preparation.extractInnerClass,
                    )
                    processor.run()
                    createdClass = processor.createdClass
                }, "Extract Delegate", null)
            } catch (e: Exception) {
                if (isConflict(e)) {
                    throw ExtractDelegateConflictException(e.message ?: "Refactoring conflict.", e)
                }
                throw e
            }
            val createdVf = createdClass?.containingFile?.virtualFile
            val persistables = buildSet {
                addAll(preparation.affectedVirtualFiles)
                if (createdVf != null) add(createdVf)
            }
            documentPersistence.persist(project, persistables)
        }

        if (ApplicationManager.getApplication().isDispatchThread) {
            command.run()
        } else {
            ApplicationManager.getApplication().invokeAndWait(command)
        }

        buildResult(project, preparation, createdClass)
    }

    private fun requireCurrentClass(preparation: ExtractDelegatePreparation): PsiClass {
        val cls = preparation.classPointer.element?.takeIf { it.isValid }
            ?: throw ExtractDelegatePreparationException(
                "The class changed before the native refactoring could run.",
            )
        return cls
    }

    private fun buildResult(
        project: Project,
        preparation: ExtractDelegatePreparation,
        createdClass: PsiClass?,
    ): ExtractDelegateExecutionResult {
        val basePath = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() }
        val affectedRel = (preparation.affectedVirtualFiles +
            listOfNotNull(createdClass?.containingFile?.virtualFile))
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

        return ExtractDelegateExecutionResult(
            sourceClass = preparation.sourceClassFqn,
            createdClass = createdClass?.qualifiedName ?: preparation.newClassName,
            affectedFiles = affectedRel,
            summary = "Extracted ${preparation.extractedFields.size} field(s) and " +
                "${preparation.extractedMethods.size} method(s) from " +
                "'${preparation.sourceClassFqn}' into '${preparation.newClassName}'.",
        )
    }

    private fun isConflict(e: Throwable): Boolean {
        if (e.javaClass.simpleName == "ConflictsInTestsException") return true
        if (e.message?.contains("conflict", ignoreCase = true) == true) return true
        val cause = e.cause
        return cause != null && cause.javaClass.simpleName == "ConflictsInTestsException"
    }
}