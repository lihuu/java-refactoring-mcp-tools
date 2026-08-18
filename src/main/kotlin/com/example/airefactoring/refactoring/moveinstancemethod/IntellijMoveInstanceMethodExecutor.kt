package com.example.airefactoring.refactoring.moveinstancemethod

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
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
class IntellijMoveInstanceMethodExecutor : MoveInstanceMethodExecutor {

    override suspend fun move(
        project: Project,
        preparation: MoveInstanceMethodPreparation,
    ): MoveInstanceMethodExecutionResult = withContext(Dispatchers.EDT) {
        val method = requireCurrentMethod(project, preparation)
        val target = requireCurrentTarget(project, preparation)

        val processor = HeadlessMoveInstanceMethodProcessor(
            project = project,
            method = method,
            targetVariable = target,
            newVisibility = nativeVisibility(preparation.newVisibility),
            oldClassParameterNames = MoveInstanceMethodHandler.suggestParameterNames(method, target),
        )

        // Capture the complete native usage inventory before mutation: the processor-provided
        // method-call usage count and the set of files the native search will touch.
        val usages = ReadAction.computeBlocking<Array<UsageInfo>, RuntimeException> {
            processor.findUsagesNative()
        }
        val updatedCallSiteCount = usages.count {
            it is MethodCallUsageInfo && it.methodCallExpression is PsiMethodCallExpression
        }
        val affectedFiles = projectRelativeUsageFiles(project, usages)

        processor.setPreviewUsages(false)
        processor.run()
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        MoveInstanceMethodExecutionResult(
            methodName = preparation.methodName,
            targetDescription = preparation.targetDescription,
            targetClassQualifiedName = preparation.targetClassQualifiedName,
            newVisibility = preparation.newVisibility,
            updatedCallSiteCount = updatedCallSiteCount,
            affectedFiles = affectedFiles,
            summary = "Moved ${preparation.methodName} to " +
                "${preparation.targetClassQualifiedName} and updated $updatedCallSiteCount call sites.",
        )
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
     * Maps every native usage to its containing project-relative file. Returns null (and omits the
     * affected-file output) when a complete set cannot be proven, e.g. a usage element without a
     * project file.
     */
    private fun projectRelativeUsageFiles(
        project: Project,
        usages: Array<UsageInfo>,
    ): List<String>? {
        val basePath = project.basePath ?: return null
        val base = Path.of(basePath).toAbsolutePath().normalize()
        val files = mutableSetOf<String>()
        for (usage in usages) {
            val element = usage.element ?: return null
            val virtualFile = element.containingFile?.virtualFile ?: return null
            val absolute = Path.of(virtualFile.path).toAbsolutePath().normalize()
            if (!absolute.startsWith(base)) return null
            files.add(base.relativize(absolute).toString())
        }
        return files.sorted()
    }

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
