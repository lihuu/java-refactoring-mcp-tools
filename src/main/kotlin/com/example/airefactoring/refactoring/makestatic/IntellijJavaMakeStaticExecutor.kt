package com.example.airefactoring.refactoring.makestatic

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiTypeParameterListOwner
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.makeStatic.MakeClassStaticProcessor
import com.intellij.refactoring.makeStatic.MakeMethodStaticProcessor
import com.intellij.refactoring.makeStatic.Settings
import com.intellij.refactoring.util.VariableData
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersistence
import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersister
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Executes one fully prepared Java Make Static request through IntelliJ's native
 * [MakeMethodStaticProcessor] or [MakeClassStaticProcessor], headlessly and with no dialog.
 *
 * The processor's normal preprocessing can present native conflicts. A private subclass keeps the
 * installed processor's own conflict collection unchanged and replaces only the UI conflict
 * presentation ([BaseRefactoringProcessor.showConflicts]) with [JavaMakeStaticConflictException];
 * the executor also translates the test-mode conflict signal
 * ([BaseRefactoringProcessor.ConflictsInTestsException]) into the same exception, so a conflict
 * aborts before source mutation and never opens a UI.
 *
 * The native usage inventory and the complete native usage-file set are captured from `findUsages()`
 * BEFORE the single `run()` call, so the result carries only native facts. The single native command
 * is the only write command and therefore the only Undo entry.
 */
class IntellijJavaMakeStaticExecutor internal constructor(
    private val documentPersistence: NativeRefactoringDocumentPersister =
        NativeRefactoringDocumentPersistence(),
) : JavaMakeStaticExecutor {

    override suspend fun makeStatic(
        project: Project,
        preparation: JavaMakeStaticPreparation,
    ): JavaMakeStaticExecutionResult {
        val prepared = withContext(Dispatchers.EDT) {
            val member = requireCurrentMember(preparation)
            val memberOwner = requireCurrentMemberOwner(preparation, member)
            val fields = requireCurrentFields(preparation, memberOwner)
            val settings = buildSettings(preparation, fields)
            PreparedNativeExecution(
                member,
                when (preparation.memberKind) {
                    JavaMakeStaticMemberKind.METHOD -> HeadlessMakeMethodStaticProcessor(
                        project,
                        member as PsiMethod,
                        settings,
                    )
                    JavaMakeStaticMemberKind.CLASS -> HeadlessMakeClassStaticProcessor(
                        project,
                        member as PsiClass,
                        settings,
                    )
                },
            )
        }
        val usageFacts = withContext(Dispatchers.Default) {
            ReadAction.computeBlocking<NativeUsageFacts, RuntimeException> {
                val usages = prepared.processor.findUsagesNative()
                NativeUsageFacts(
                    nativeUsageCount = usages.size,
                    affectedFiles = projectRelativeAffectedFiles(project, usages, preparation),
                    filesToPersist = affectedVirtualFiles(prepared.member, usages),
                )
            }
        }

        return withContext(Dispatchers.EDT) {
            requireCurrentFields(preparation, requireCurrentMemberOwner(preparation, requireCurrentMember(preparation)))
            prepared.processor.setPreviewUsages(false)
            try {
                prepared.processor.run()
            } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
                throw JavaMakeStaticConflictException(
                    e.getMessages().distinct().joinToString(separator = "; "),
                )
            }
            documentPersistence.persist(project, usageFacts.filesToPersist)

            JavaMakeStaticExecutionResult(
                memberName = preparation.memberName,
                memberKind = preparation.memberKind,
                replaceUsages = preparation.replaceUsages,
                classParameterName = preparation.classParameterName,
                fieldParameterNames = preparation.fieldParameterNames,
                generateDelegate = preparation.generateDelegate,
                nativeUsageCount = usageFacts.nativeUsageCount,
                affectedFiles = usageFacts.affectedFiles,
                summary = "Made ${preparation.memberKind.name.lowercase()} " +
                    "'${preparation.memberName}' static and updated ${usageFacts.nativeUsageCount} native usages.",
            )
        }
    }

    /**
     * De-references the resolver's smart pointer only on the EDT and proves the member is still valid
     * and still described exactly as it was when resolved.
     */
    private fun requireCurrentMember(preparation: JavaMakeStaticPreparation): PsiTypeParameterListOwner {
        val member = preparation.memberPointer.element
            ?.takeIf { it.isValid }
            ?: throw JavaMakeStaticPreparationException(
                "The Java Make Static target changed before the native refactoring could run.",
            )
        if (member.text != preparation.memberTextSnapshot) {
            throw JavaMakeStaticPreparationException(
                "The Java Make Static target changed before the native refactoring could run.",
            )
        }
        when (preparation.memberKind) {
            JavaMakeStaticMemberKind.METHOD -> if (
                member !is PsiMethod || member.isConstructor || member.hasModifierProperty(com.intellij.psi.PsiModifier.STATIC)
            ) {
                throw JavaMakeStaticPreparationException(
                    "The Java Make Static target changed before the native refactoring could run.",
                )
            }
            JavaMakeStaticMemberKind.CLASS -> if (
                member !is PsiClass || member.containingClass == null || member.hasModifierProperty(com.intellij.psi.PsiModifier.STATIC)
            ) {
                throw JavaMakeStaticPreparationException(
                    "The Java Make Static target changed before the native refactoring could run.",
                )
            }
        }
        return member
    }

    /** Proves the selected member still belongs to the exact containing class resolved by the caller. */
    private fun requireCurrentMemberOwner(
        preparation: JavaMakeStaticPreparation,
        member: PsiTypeParameterListOwner,
    ): PsiClass {
        val owner = preparation.memberOwnerPointer.element
            ?.takeIf { it.isValid }
            ?: throw JavaMakeStaticPreparationException(
                "The Java Make Static target owner changed before the native refactoring could run.",
            )
        if (member.containingClass !== owner) {
            throw JavaMakeStaticPreparationException(
                "The Java Make Static target owner changed before the native refactoring could run.",
            )
        }
        return owner
    }

    /** De-references every selected-field smart pointer in caller order and proves its full identity. */
    private fun requireCurrentFields(
        preparation: JavaMakeStaticPreparation,
        memberOwner: PsiClass,
    ): List<PsiField> {
        if (
            preparation.fieldPointers.size != preparation.fieldTextSnapshots.size ||
            preparation.fieldPointers.size != preparation.fieldTypeSnapshots.size ||
            preparation.fieldPointers.size != preparation.fieldParameterNames.size
        ) {
            throw JavaMakeStaticPreparationException(
                "The Java Make Static field selections changed before the native refactoring could run.",
            )
        }
        return preparation.fieldPointers.mapIndexed { index, pointer ->
            val field = pointer.element
                ?.takeIf { it.isValid }
                ?: throw JavaMakeStaticPreparationException(
                    "The Java Make Static field selections changed before the native refactoring could run.",
                )
            if (field.text != preparation.fieldTextSnapshots[index]) {
                throw JavaMakeStaticPreparationException(
                    "The Java Make Static field selections changed before the native refactoring could run.",
                )
            }
            if (
                field.containingClass !== memberOwner ||
                field.hasModifierProperty(com.intellij.psi.PsiModifier.STATIC) ||
                field.type.canonicalText != preparation.fieldTypeSnapshots[index]
            ) {
                throw JavaMakeStaticPreparationException(
                    "The Java Make Static field selections changed before the native refactoring could run.",
                )
            }
            field
        }
    }

    /**
     * Builds the native [Settings] exactly from the MCP-supplied decisions: the AI-selected field
     * parameters are passed in the exact requested order with `passAsParameter` true and the chosen
     * parameter names. The plugin never infers fields, reorders parameters, or changes the delegate
     * or replace-usages decisions.
     */
    private fun buildSettings(
        preparation: JavaMakeStaticPreparation,
        fields: List<PsiField>,
    ): Settings {
        val variableData = fields.mapIndexed { index, field ->
            VariableData(field).apply {
                name = preparation.fieldParameterNames[index]
                passAsParameter = true
                type = field.type
            }
        }.toTypedArray()
        return Settings(
            preparation.replaceUsages,
            preparation.classParameterName,
            variableData,
            preparation.generateDelegate,
        )
    }

    /**
     * Builds a provably complete affected-file set: the target member file plus every project-relative
     * native usage file. Returns null (and omits the output) when any element cannot be resolved to a
     * project-relative path, because then a complete inventory cannot be proven.
     */
    private fun projectRelativeAffectedFiles(
        project: Project,
        usages: Array<UsageInfo>,
        preparation: JavaMakeStaticPreparation,
    ): List<String>? {
        val base = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() } ?: return null
        val files = mutableSetOf(preparation.pathInProject)
        for (usage in usages) {
            val element = usage.element ?: return null
            val virtualFile = element.containingFile?.virtualFile ?: return null
            val rel = relativeProjectPath(base, virtualFile.path) ?: return null
            files.add(rel)
        }
        return files.sorted()
    }

    /** Collects only files the native processor reported as its target or usages, preserving one Undo. */
    private fun affectedVirtualFiles(member: PsiTypeParameterListOwner, usages: Array<UsageInfo>): Set<VirtualFile> =
        buildSet {
            member.containingFile?.virtualFile?.let(::add)
            usages.mapNotNullTo(this) { it.element?.containingFile?.virtualFile }
        }

    private data class PreparedNativeExecution(
        val member: PsiTypeParameterListOwner,
        val processor: HeadlessJavaMakeStaticProcessor,
    )

    private data class NativeUsageFacts(
        val nativeUsageCount: Int,
        val affectedFiles: List<String>?,
        val filesToPersist: Set<VirtualFile>,
    )

    /** Maps one absolute file path to a project-relative path, or null when it is not inside the project. */
    private fun relativeProjectPath(base: Path, absolutePath: String): String? {
        val absolute = Path.of(absolutePath).toAbsolutePath().normalize()
        if (!absolute.startsWith(base)) return null
        return base.relativize(absolute).toString()
    }

    /**
     * A headless [MakeMethodStaticProcessor] subclass that replaces the base conflict UI presentation
     * ([showConflicts]) with [JavaMakeStaticConflictException] and exposes the protected native usage
     * search for pre-run fact capture.
     */
    private class HeadlessMakeMethodStaticProcessor(
        project: Project,
        method: PsiMethod,
        settings: Settings,
    ) : MakeMethodStaticProcessor(project, method, settings), HeadlessJavaMakeStaticProcessor {
        override fun showConflicts(
            conflicts: MultiMap<PsiElement, String>,
            usages: Array<out UsageInfo>?,
        ): Boolean {
            if (conflicts.isEmpty()) {
                return super.showConflicts(conflicts, usages)
            }
            throw JavaMakeStaticConflictException(
                conflicts.values().distinct().joinToString(separator = "; "),
            )
        }

        override fun findUsagesNative(): Array<UsageInfo> = findUsages()
    }

    /**
     * A headless [MakeClassStaticProcessor] subclass that replaces the base conflict UI presentation
     * ([showConflicts]) with [JavaMakeStaticConflictException] and exposes the protected native usage
     * search for pre-run fact capture.
     */
    private class HeadlessMakeClassStaticProcessor(
        project: Project,
        clazz: PsiClass,
        settings: Settings,
    ) : MakeClassStaticProcessor(project, clazz, settings), HeadlessJavaMakeStaticProcessor {
        override fun showConflicts(
            conflicts: MultiMap<PsiElement, String>,
            usages: Array<out UsageInfo>?,
        ): Boolean {
            if (conflicts.isEmpty()) {
                return super.showConflicts(conflicts, usages)
            }
            throw JavaMakeStaticConflictException(
                conflicts.values().distinct().joinToString(separator = "; "),
            )
        }

        override fun findUsagesNative(): Array<UsageInfo> = findUsages()
    }

    /**
     * The headless surface both native Make Static subclasses expose to the executor: the protected
     * usage search plus the public run/preview controls inherited from [BaseRefactoringProcessor].
     */
    private interface HeadlessJavaMakeStaticProcessor {
        fun findUsagesNative(): Array<UsageInfo>
        fun setPreviewUsages(preview: Boolean)
        fun run()
    }
}
