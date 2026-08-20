package com.example.airefactoring.refactoring.converttoinstancemethod

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.convertToInstanceMethod.ConvertToInstanceMethodProcessor
import com.intellij.usageView.UsageInfo
import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersistence
import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersister
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Headless executor for [ConvertToInstanceMethodProcessor].
 *
 * `ConvertToInstanceMethodProcessor` is `final` in platform 261, so the headless
 * `showConflicts` override / `findUsages` exposure used by older refactorings is
 * not applicable. Instead this executor:
 * - invokes the protected `findUsages` via a cached reflective lookup, and
 * - drives the processor with `ide.performance.skip.refactoring.dialogs=true`
 *   so that [BaseRefactoringProcessor.ConflictsInTestsException] is thrown rather
 *   than showing a dialog. The system property is confined to the `run()` call
 *   and restored in `finally` (no global leak), with `TestDialog` assertions in
 *   tests ensuring no dialog appears.
 */
class IntellijConvertToInstanceMethodExecutor internal constructor(
    private val documentPersistence: NativeRefactoringDocumentPersister =
        NativeRefactoringDocumentPersistence(),
) : ConvertToInstanceMethodExecutor {

    override suspend fun convert(
        project: Project,
        preparation: ConvertToInstanceMethodPreparation,
    ): ConvertToInstanceMethodExecutionResult {
        val prepared = withContext(Dispatchers.EDT) { createProcessor(project, preparation) }
        val usageFacts = withContext(Dispatchers.Default) {
            ReadAction.computeBlocking<NativeUsageFacts, RuntimeException> {
                val usages = findUsagesNative(prepared.processor)
                NativeUsageFacts(
                    nativeUsageCount = usages.size,
                    affectedFiles = projectRelativeAffectedFiles(project, usages, preparation),
                    filesToPersist = affectedVirtualFiles(prepared, usages),
                )
            }
        }
        return withContext(Dispatchers.EDT) {
            validateFreshPreparation(preparation)
            prepared.processor.setPreviewUsages(false)
            try {
                // Confined property mutation: only around processor.run() so conflicts
                // throw ConflictsInTestsException instead of opening a dialog.
                val previous = System.getProperty("ide.performance.skip.refactoring.dialogs")
                System.setProperty("ide.performance.skip.refactoring.dialogs", "true")
                try {
                    prepared.processor.run()
                } finally {
                    if (previous == null) System.clearProperty("ide.performance.skip.refactoring.dialogs")
                    else System.setProperty("ide.performance.skip.refactoring.dialogs", previous)
                }
            } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
                throw ConvertToInstanceMethodConflictException(
                    e.getMessages().distinct().joinToString(separator = "; "),
                )
            }
            documentPersistence.persist(project, usageFacts.filesToPersist)
            executionResult(preparation, usageFacts)
        }
    }

    internal fun createProcessor(
        project: Project,
        preparation: ConvertToInstanceMethodPreparation,
    ): PreparedNativeExecution {
        validateFreshPreparation(preparation)
        val method = requireCurrentMethod(preparation)
        val targetParameter = requireCurrentTargetParameter(preparation)
        val targetClass = requireCurrentTargetClass(preparation)
        val processor = ConvertToInstanceMethodProcessor(
            project,
            method,
            targetParameter,
            preparation.newVisibility,
        )
        return PreparedNativeExecution(method, targetParameter, targetClass, processor)
    }

    internal fun validateFreshPreparation(preparation: ConvertToInstanceMethodPreparation) {
        val method = preparation.methodPointer.element
            ?.takeIf { it.isValid }
            ?: throw ConvertToInstanceMethodPreparationException(
                "The convert-to-instance-method target changed before the native refactoring could run.",
            )
        if (method.text != preparation.methodTextSnapshot) {
            throw ConvertToInstanceMethodPreparationException(
                "The convert-to-instance-method target changed before the native refactoring could run.",
            )
        }
        if (method.containingClass?.qualifiedName != preparation.methodOwnerQualifiedNameSnapshot) {
            throw ConvertToInstanceMethodPreparationException(
                "The convert-to-instance-method target changed before the native refactoring could run.",
            )
        }
        if (!method.hasModifierProperty(com.intellij.psi.PsiModifier.STATIC)) {
            throw ConvertToInstanceMethodPreparationException(
                "The convert-to-instance-method target changed before the native refactoring could run.",
            )
        }

        val targetClass = preparation.targetClassPointer.element
            ?.takeIf { it.isValid }
            ?: throw ConvertToInstanceMethodPreparationException(
                "The convert-to-instance-method target changed before the native refactoring could run.",
            )
        if (targetClass.qualifiedName != preparation.targetClassQualifiedNameSnapshot) {
            throw ConvertToInstanceMethodPreparationException(
                "The convert-to-instance-method target changed before the native refactoring could run.",
            )
        }

        val pointer = preparation.targetParameterPointer
        if (pointer != null) {
            val param = pointer.element
                ?.takeIf { it.isValid }
                ?: throw ConvertToInstanceMethodPreparationException(
                    "The convert-to-instance-method target changed before the native refactoring could run.",
                )
            if (param.text != preparation.targetParameterTextSnapshot) {
                throw ConvertToInstanceMethodPreparationException(
                    "The convert-to-instance-method target changed before the native refactoring could run.",
                )
            }
            val typeText = (param.type as? PsiClassType)?.canonicalText
            if (typeText != preparation.targetParameterTypeSnapshot) {
                throw ConvertToInstanceMethodPreparationException(
                    "The convert-to-instance-method target changed before the native refactoring could run.",
                )
            }
            if (param.declarationScope !== method) {
                throw ConvertToInstanceMethodPreparationException(
                    "The convert-to-instance-method target changed before the native refactoring could run.",
                )
            }
        } else {
            if (preparation.targetParameterTextSnapshot != null || preparation.targetParameterTypeSnapshot != null) {
                throw ConvertToInstanceMethodPreparationException(
                    "The convert-to-instance-method target changed before the native refactoring could run.",
                )
            }
        }
    }

    private fun requireCurrentMethod(preparation: ConvertToInstanceMethodPreparation): PsiMethod {
        return preparation.methodPointer.element
            ?.takeIf { it.isValid }
            ?: throw ConvertToInstanceMethodPreparationException(
                "The convert-to-instance-method target changed before the native refactoring could run.",
            )
    }

    private fun requireCurrentTargetParameter(preparation: ConvertToInstanceMethodPreparation): PsiParameter? {
        val ptr = preparation.targetParameterPointer ?: return null
        return ptr.element
            ?.takeIf { it.isValid }
            ?: throw ConvertToInstanceMethodPreparationException(
                "The convert-to-instance-method target changed before the native refactoring could run.",
            )
    }

    private fun requireCurrentTargetClass(preparation: ConvertToInstanceMethodPreparation): PsiClass {
        return preparation.targetClassPointer.element
            ?.takeIf { it.isValid }
            ?: throw ConvertToInstanceMethodPreparationException(
                "The convert-to-instance-method target changed before the native refactoring could run.",
            )
    }

    internal fun executionResult(
        preparation: ConvertToInstanceMethodPreparation,
        usageFacts: NativeUsageFacts,
    ): ConvertToInstanceMethodExecutionResult {
        val kindString = when (preparation.targetKind) {
            ConvertToInstanceMethodTargetKind.PARAMETER -> "parameter"
            ConvertToInstanceMethodTargetKind.CONTAINING_CLASS -> "containing_class"
        }
        return ConvertToInstanceMethodExecutionResult(
            methodName = preparation.methodName,
            targetKind = kindString,
            targetDescription = preparation.targetDescription,
            targetClassQualifiedName = preparation.targetClassQualifiedName,
            newVisibility = preparation.newVisibility,
            nativeUsageCount = usageFacts.nativeUsageCount,
            affectedFiles = usageFacts.affectedFiles,
            summary = "Converted ${preparation.methodName} to an instance method of ${preparation.targetClassQualifiedName}.",
        )
    }

    private fun projectRelativeAffectedFiles(
        project: Project,
        usages: Array<UsageInfo>,
        preparation: ConvertToInstanceMethodPreparation,
    ): List<String>? {
        val base = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() } ?: return null
        val files = mutableSetOf<String>()
        files.add(preparation.pathInProject)
        val targetClass = preparation.targetClassPointer.element ?: return null
        val targetVirtual = targetClass.containingFile?.virtualFile ?: return null
        val targetRel = relativeProjectPath(base, targetVirtual.path) ?: return null
        files.add(targetRel)
        for (usage in usages) {
            val element = usage.element ?: return null
            val virtualFile = element.containingFile?.virtualFile ?: return null
            val rel = relativeProjectPath(base, virtualFile.path) ?: return null
            files.add(rel)
        }
        return files.sorted()
    }

    private fun relativeProjectPath(base: Path, absolutePath: String): String? {
        val absolute = Path.of(absolutePath).toAbsolutePath().normalize()
        if (!absolute.startsWith(base)) return null
        return base.relativize(absolute).toString()
    }

    private fun affectedVirtualFiles(
        prepared: PreparedNativeExecution,
        usages: Array<UsageInfo>,
    ): Set<VirtualFile> = buildSet {
        prepared.method.containingFile.virtualFile?.let(::add)
        prepared.targetClass.containingFile?.virtualFile?.let(::add)
        usages.mapNotNullTo(this) { it.element?.containingFile?.virtualFile }
    }

    internal data class PreparedNativeExecution(
        val method: PsiMethod,
        val targetParameter: PsiParameter?,
        val targetClass: PsiClass,
        val processor: ConvertToInstanceMethodProcessor,
    )

    internal data class NativeUsageFacts(
        val nativeUsageCount: Int,
        val affectedFiles: List<String>?,
        val filesToPersist: Set<VirtualFile>,
    )

    private fun findUsagesNative(processor: ConvertToInstanceMethodProcessor): Array<UsageInfo> {
        val method = cachedFindUsagesMethod ?: findUsagesMethod(processor).also { cachedFindUsagesMethod = it }
        @Suppress("UNCHECKED_CAST")
        return method.invoke(processor) as Array<UsageInfo>
    }

    private fun findUsagesMethod(processor: ConvertToInstanceMethodProcessor): java.lang.reflect.Method {
        var clazz: Class<*> = processor.javaClass
        while (clazz != Any::class.java) {
            try {
                return clazz.getDeclaredMethod("findUsages").apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                clazz = clazz.superclass ?: break
            }
        }
        throw IllegalStateException("findUsages method not found on ConvertToInstanceMethodProcessor")
    }

    companion object {
        @Volatile
        private var cachedFindUsagesMethod: java.lang.reflect.Method? = null
    }
}
