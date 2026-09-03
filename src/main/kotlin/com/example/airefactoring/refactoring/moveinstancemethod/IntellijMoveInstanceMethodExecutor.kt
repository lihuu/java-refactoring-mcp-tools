package com.example.airefactoring.refactoring.moveinstancemethod

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiVariable
import com.intellij.refactoring.move.moveInstanceMethod.MethodCallUsageInfo
import com.intellij.refactoring.move.moveInstanceMethod.MoveInstanceMethodHandler
import com.intellij.refactoring.move.moveInstanceMethod.MoveInstanceMethodProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersistence
import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersister
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Executes one fully prepared Move Instance Method request through IntelliJ's native
 * [MoveInstanceMethodProcessor], headlessly and with no dialog.
 *
 * The processor's normal preprocessing can show a conflict dialog. A private
 * [HeadlessMoveInstanceMethodProcessor] subclass keeps the installed processor's own `preprocessUsages`
 * conflict collection byte-for-byte identical and replaces only the UI conflict presentation
 * ([com.intellij.refactoring.BaseRefactoringProcessor.showConflicts]) with
 * [MoveInstanceMethodConflictException], so a conflict aborts before mutation and never opens a UI.
 *
 * The native method-call usage count and the complete native usage-file set are captured from
 * `findUsages()` BEFORE the single `run()` call, so the result carries only native facts. The single
 * native command is the only write command and therefore the only Undo entry.
 */
class IntellijMoveInstanceMethodExecutor internal constructor(
    private val documentPersistence: NativeRefactoringDocumentPersister =
        NativeRefactoringDocumentPersistence(),
) : MoveInstanceMethodExecutor {

    override suspend fun move(
        project: Project,
        preparation: MoveInstanceMethodPreparation,
    ): MoveInstanceMethodExecutionResult {
        // suggestParameterNames walks stub indexes via JavaCodeStyleManager.suggestVariableName,
        // which trips SlowOperations assertions on EDT. Off-EDT read actions are exempt by design.
        val suggestedParameterNames = withContext(Dispatchers.Default) {
            readAction {
                val method = requireCurrentMethod(project, preparation)
                val target = requireCurrentTarget(project, preparation)
                MoveInstanceMethodHandler.suggestParameterNames(method, target)
            }
        }
        val prepared = withContext(Dispatchers.EDT) {
            val method = requireCurrentMethod(project, preparation)
            val target = requireCurrentTarget(project, preparation)
            PreparedNativeExecution(
                method,
                target,
                HeadlessMoveInstanceMethodProcessor(
                    project, method, target, nativeVisibility(preparation.newVisibility),
                    suggestedParameterNames,
                ),
            )
        }
        val usageFacts = withContext(Dispatchers.Default) {
            ReadAction.computeBlocking<NativeUsageFacts, RuntimeException> {
                val usages = prepared.processor.findUsagesNative()
                NativeUsageFacts(
                    usages.count { it is MethodCallUsageInfo && it.methodCallExpression is PsiMethodCallExpression },
                    projectRelativeAffectedFiles(project, usages, prepared.target, preparation),
                    affectedVirtualFiles(prepared.method, prepared.target, usages),
                )
            }
        }
        return withContext(Dispatchers.EDT) {
            requireCurrentMethod(project, preparation)
            requireCurrentTarget(project, preparation)
            prepared.processor.setPreviewUsages(false)
            prepared.processor.run()
            documentPersistence.persist(project, usageFacts.filesToPersist)
            MoveInstanceMethodExecutionResult(
                preparation.methodName, preparation.targetDescription, preparation.targetClassQualifiedName,
                preparation.newVisibility, usageFacts.updatedCallSiteCount, usageFacts.affectedFiles,
                "Moved ${preparation.methodName} to ${preparation.targetClassQualifiedName} and updated ${usageFacts.updatedCallSiteCount} call sites.",
            )
        }
    }

    /**
     * De-references the resolver's smart pointer only on the EDT and proves the method is still
     * valid and still described exactly as it was when resolved.
     */
    private fun requireCurrentMethod(
        project: Project,
        preparation: MoveInstanceMethodPreparation,
    ): PsiMethod {
        val method = preparation.methodPointer.element
            ?.takeIf { it.isValid }
            ?: throw MoveInstanceMethodPreparationException(
                "The move-instance-method target changed before the native refactoring could run.",
            )
        if (method.text != preparation.methodTextSnapshot) {
            throw MoveInstanceMethodPreparationException(
                "The move-instance-method target changed before the native refactoring could run.",
            )
        }
        return method
    }

    /** De-references the target parameter and proves its declared type still matches the snapshot. */
    private fun requireCurrentTarget(
        project: Project,
        preparation: MoveInstanceMethodPreparation,
    ): PsiVariable {
        val target = preparation.targetPointer.element
            ?.takeIf { it.isValid }
            ?: throw MoveInstanceMethodPreparationException(
                "The move-instance-method target changed before the native refactoring could run.",
            )
        if (target.type.canonicalText != preparation.targetTypeSnapshot) {
            throw MoveInstanceMethodPreparationException(
                "The move-instance-method target changed before the native refactoring could run.",
            )
        }
        return target
    }

    /**
     * The preparation carries the MCP-facing visibility ('public'/'protected'/'private'/''); the
     * native processor consumes the visibility-panel strings, where package-local is "packageLocal".
     */
    private fun nativeVisibility(newVisibility: String): String =
        if (newVisibility.isEmpty()) "packageLocal" else newVisibility

    /**
     * Builds a provably complete affected-file set. Beyond every native usage file, the move always
     * modifies the destination target-class file (the method is added there) and the source file
     * (the method is removed from it); the destination target-class file may not appear among the
     * usages when it is a top-level class in a separate file. Returns null (and omits the
     * affected-file output) when any element cannot be resolved to a project-relative path, because
     * then a complete inventory cannot be proven.
     */
    private fun projectRelativeAffectedFiles(
        project: Project,
        usages: Array<UsageInfo>,
        target: PsiVariable,
        preparation: MoveInstanceMethodPreparation,
    ): List<String>? {
        val basePath = project.basePath ?: return null
        val base = Path.of(basePath).toAbsolutePath().normalize()

        // Destination target-class file: resolve the parameter's declared type to its class.
        val targetClass = (target.type as? PsiClassType)?.resolve() ?: return null
        val destinationVirtualFile = targetClass.containingFile?.virtualFile ?: return null
        val destinationRel = relativeProjectPath(base, destinationVirtualFile.path) ?: return null

        // Source file: the method is always removed from its current file.
        val sourceRel = preparation.pathInProject

        val files = mutableSetOf(destinationRel, sourceRel)
        for (usage in usages) {
            val element = usage.element ?: return null
            val virtualFile = element.containingFile?.virtualFile ?: return null
            val rel = relativeProjectPath(base, virtualFile.path) ?: return null
            files.add(rel)
        }
        return files.sorted()
    }

    private fun affectedVirtualFiles(
        method: PsiMethod,
        target: PsiVariable,
        usages: Array<UsageInfo>,
    ): Set<VirtualFile> = buildSet {
        method.containingFile.virtualFile?.let(::add)
        ((target.type as? PsiClassType)?.resolve()?.containingFile?.virtualFile)?.let(::add)
        usages.mapNotNullTo(this) { it.element?.containingFile?.virtualFile }
    }

    /** Maps one absolute file path to a project-relative path, or null when it is not inside the project. */
    private fun relativeProjectPath(base: Path, absolutePath: String): String? {
        val absolute = Path.of(absolutePath).toAbsolutePath().normalize()
        if (!absolute.startsWith(base)) return null
        return base.relativize(absolute).toString()
    }

    private data class PreparedNativeExecution(
        val method: PsiMethod,
        val target: PsiVariable,
        val processor: HeadlessMoveInstanceMethodProcessor,
    )

    private data class NativeUsageFacts(
        val updatedCallSiteCount: Int,
        val affectedFiles: List<String>?,
        val filesToPersist: Set<VirtualFile>,
    )

    /**
     * A [MoveInstanceMethodProcessor] whose only change is to replace the base class's conflict UI
     * presentation ([showConflicts]) with [MoveInstanceMethodConflictException]. The inherited
     * `preprocessUsages` — the installed 261 processor's native conflict collection — runs unchanged.
     */
    private class HeadlessMoveInstanceMethodProcessor(
        project: Project,
        method: PsiMethod,
        targetVariable: PsiVariable,
        newVisibility: String,
        oldClassParameterNames: Map<PsiClass, String>,
    ) : MoveInstanceMethodProcessor(
        project,
        method,
        targetVariable,
        newVisibility,
        false,
        oldClassParameterNames,
    ) {
        override fun showConflicts(
            conflicts: MultiMap<PsiElement, String>,
            usages: Array<out UsageInfo>?,
        ): Boolean {
            if (conflicts.isEmpty()) {
                return super.showConflicts(conflicts, usages)
            }
            throw MoveInstanceMethodConflictException(
                conflicts.values().distinct().joinToString(separator = "; "),
            )
        }

        /** Exposes the processor's protected native usage search for pre-run fact capture. */
        fun findUsagesNative(): Array<UsageInfo> = findUsages()
    }
}
