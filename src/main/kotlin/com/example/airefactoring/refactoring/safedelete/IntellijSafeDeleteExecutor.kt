package com.example.airefactoring.refactoring.safedelete

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.safeDelete.SafeDeleteProcessor
import com.intellij.refactoring.safeDelete.SafeDeleteProcessorDelegate
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteReferenceUsageInfo
import com.intellij.refactoring.util.RefactoringUIUtil
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewUtil
import com.intellij.util.containers.MultiMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Executes one fully prepared Safe Delete request through IntelliJ's native
 * [SafeDeleteProcessor], headlessly and with no dialog.
 *
 * [SafeDeleteProcessor] is `final`, so its protected `findUsages()` and private conflict
 * collection cannot be overridden. Instead the executor computes the native usages and conflicts
 * BEFORE mutation, mirroring the processor's own `preprocessUsages` bytecode: it calls the
 * protected `findUsages()` via reflection, then collects conflicts from every
 * [SafeDeleteProcessorDelegate] that handles the element plus every unsafe reference usage. Only
 * when the conflict map is empty does it call `run()`, which then performs the single native write
 * command (one global Undo) without ever showing a dialog.
 */
class IntellijSafeDeleteExecutor : SafeDeleteExecutor {

    override suspend fun delete(
        project: Project,
        preparation: SafeDeletePreparation,
    ): SafeDeleteExecutionResult = withContext(Dispatchers.EDT) {
        val element = requireCurrentElement(project, preparation)

        val processor = SafeDeleteProcessor.createInstance(
            project,
            Runnable {},
            arrayOf(element),
            false,
            false,
        )

        val usages = ReadAction.computeBlocking<Array<UsageInfo>, RuntimeException> {
            findUsages(processor)
        }
        val conflicts = ReadAction.computeBlocking<MultiMap<PsiElement, String>, RuntimeException> {
            collectConflicts(arrayOf(element), usages)
        }
        if (!conflicts.isEmpty) {
            throw SafeDeleteConflictException(
                conflicts.values().distinct().joinToString(separator = "; "),
            )
        }

        processor.setPreviewUsages(false)
        processor.run()
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        SafeDeleteExecutionResult(
            targetDescription = preparation.targetDescription,
            nativeUsageCount = usages.size,
            summary = "Deleted ${preparation.targetDescription}.",
        )
    }

    /**
     * De-references the resolver's smart pointer only on the EDT and proves the element is still
     * valid, still in the expected file, and still described exactly as it was when resolved.
     */
    private fun requireCurrentElement(
        project: Project,
        preparation: SafeDeletePreparation,
    ): PsiElement {
        val element = preparation.elementPointer.element
        if (element == null || !element.isValid) {
            throw SafeDeletePreparationException(
                "The safe-delete target changed before the native refactoring could run.",
            )
        }
        val expectedPath = Path.of(project.basePath ?: "")
            .resolve(preparation.sourceDocumentPath).normalize().toString()
        if (element.containingFile.virtualFile.path != expectedPath) {
            throw SafeDeletePreparationException(
                "The safe-delete target changed before the native refactoring could run.",
            )
        }
        if (UsageViewUtil.getLongName(element) != preparation.targetDescription) {
            throw SafeDeletePreparationException(
                "The safe-delete target changed before the native refactoring could run.",
            )
        }
        return element
    }

    /** Invokes the processor's protected `findUsages()` through reflection. */
    private fun findUsages(processor: SafeDeleteProcessor): Array<UsageInfo> {
        val method = SafeDeleteProcessor::class.java.getDeclaredMethod("findUsages")
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(processor) as Array<UsageInfo>
    }

    /**
     * Mirrors the processor's `preprocessUsages` conflict collection: delegate `findConflicts`
     * plus the unsafe-reference-usage scan that flags a still-referenced element.
     */
    private fun collectConflicts(
        elements: Array<PsiElement>,
        usages: Array<UsageInfo>,
    ): MultiMap<PsiElement, String> {
        val conflicts = MultiMap<PsiElement, String>()
        for (element in elements) {
            for (delegate in SafeDeleteProcessorDelegate.EP_NAME.extensionList) {
                if (delegate.handlesElement(element)) {
                    delegate.findConflicts(element, elements, usages, conflicts)
                }
            }
        }
        for (usage in usages) {
            if (usage is SafeDeleteReferenceUsageInfo && !usage.isSafeDelete) {
                val description = RefactoringUIUtil.getDescription(usage.referencedElement, true)
                conflicts.putValue(
                    usage.element,
                    RefactoringBundle.message("usage.that.is.not.safe.to.delete", description),
                )
            }
        }
        return conflicts
    }
}
