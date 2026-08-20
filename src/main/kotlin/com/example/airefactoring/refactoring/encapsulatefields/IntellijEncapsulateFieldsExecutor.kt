package com.example.airefactoring.refactoring.encapsulatefields

import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersistence
import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersister
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.encapsulateFields.EncapsulateFieldsDescriptor
import com.intellij.refactoring.encapsulateFields.EncapsulateFieldsProcessor
import com.intellij.refactoring.encapsulateFields.FieldDescriptor
import com.intellij.refactoring.encapsulateFields.FieldDescriptorImpl
import com.intellij.refactoring.encapsulateFields.JavaEncapsulateFieldHelper
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

class IntellijEncapsulateFieldsExecutor internal constructor(
    private val documentPersistence: NativeRefactoringDocumentPersister =
        NativeRefactoringDocumentPersistence(),
) : EncapsulateFieldsExecutor {

    override suspend fun encapsulate(
        project: Project,
        preparation: EncapsulateFieldsPreparation,
    ): EncapsulateFieldsExecutionResult {
        val prepared = withContext(Dispatchers.EDT) {
            val fields = requireCurrentFields(preparation)
            val containingClass = requireCurrentContainingClass(preparation, fields)
            val descriptors = buildFieldDescriptors(fields, preparation)
            val descriptor = buildDescriptor(containingClass, descriptors, preparation)
            PreparedNativeExecution(
                containingClass = containingClass,
                fields = fields,
                processor = HeadlessEncapsulateFieldsProcessor(project, descriptor),
            )
        }

        val usageFacts = withContext(Dispatchers.Default) {
            ReadAction.computeBlocking<NativeUsageFacts, RuntimeException> {
                val usages = prepared.processor.findUsagesNative()
                NativeUsageFacts(
                    nativeUsageCount = usages.size,
                    affectedFiles = projectRelativeAffectedFiles(project, usages, preparation, prepared.containingClass),
                    filesToPersist = affectedVirtualFiles(prepared.containingClass, usages),
                )
            }
        }

        return withContext(Dispatchers.EDT) {
            // Revalidate before mutation
            requireCurrentFields(preparation)
            requireCurrentContainingClass(
                preparation,
                preparation.fieldPointers.mapNotNull { it.element?.takeIf { e -> e.isValid } as? PsiField },
            )
            prepared.processor.setPreviewUsages(false)
            try {
                prepared.processor.run()
            } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
                throw EncapsulateFieldsConflictException(
                    e.getMessages().distinct().joinToString(separator = "; "),
                )
            }
            documentPersistence.persist(project, usageFacts.filesToPersist)

            EncapsulateFieldsExecutionResult(
                fieldNames = preparation.fieldNames,
                getterNames = preparation.getterNames,
                setterNames = preparation.setterNames,
                fieldsVisibility = preparation.fieldsVisibility,
                accessorsVisibility = preparation.accessorsVisibility,
                encapsulateGet = preparation.encapsulateGet,
                encapsulateSet = preparation.encapsulateSet,
                useAccessorsWhenAccessible = preparation.useAccessorsWhenAccessible,
                nativeUsageCount = usageFacts.nativeUsageCount,
                affectedFiles = usageFacts.affectedFiles,
                summary = "Encapsulated ${preparation.fieldNames.size} field(s) in ${preparation.containingClassQualifiedNameSnapshot} with ${preparation.accessorsVisibility} accessors.",
            )
        }
    }

    private fun requireCurrentFields(preparation: EncapsulateFieldsPreparation): List<PsiField> {
        if (preparation.fieldPointers.size != preparation.fieldTextSnapshots.size ||
            preparation.fieldPointers.size != preparation.fieldTypeSnapshots.size ||
            preparation.fieldPointers.size != preparation.fieldNames.size
        ) {
            throw EncapsulateFieldsPreparationException(
                "The encapsulate fields target changed before the native refactoring could run.",
            )
        }
        return preparation.fieldPointers.mapIndexed { index, pointer ->
            val field = pointer.element
                ?.takeIf { it.isValid }
                ?: throw EncapsulateFieldsPreparationException(
                    "The encapsulate fields target changed before the native refactoring could run.",
                )
            if (field.text != preparation.fieldTextSnapshots[index]) {
                throw EncapsulateFieldsPreparationException(
                    "The encapsulate fields target changed before the native refactoring could run.",
                )
            }
            if (field.type.canonicalText != preparation.fieldTypeSnapshots[index]) {
                throw EncapsulateFieldsPreparationException(
                    "The encapsulate fields target changed before the native refactoring could run.",
                )
            }
            if (field.name != preparation.fieldNames[index]) {
                throw EncapsulateFieldsPreparationException(
                    "The encapsulate fields target changed before the native refactoring could run.",
                )
            }
            field
        }
    }

    private fun requireCurrentContainingClass(
        preparation: EncapsulateFieldsPreparation,
        fields: List<PsiField>,
    ): PsiClass {
        val cls = preparation.containingClassPointer.element
            ?.takeIf { it.isValid }
            ?: throw EncapsulateFieldsPreparationException(
                "The encapsulate fields containing class changed before the native refactoring could run.",
            )
        if (cls.qualifiedName != preparation.containingClassQualifiedNameSnapshot) {
            throw EncapsulateFieldsPreparationException(
                "The encapsulate fields containing class changed before the native refactoring could run.",
            )
        }
        if (cls.text != preparation.containingClassTextSnapshot) {
            throw EncapsulateFieldsPreparationException(
                "The encapsulate fields containing class changed before the native refactoring could run.",
            )
        }
        for (field in fields) {
            if (field.containingClass !== cls) {
                throw EncapsulateFieldsPreparationException(
                    "The encapsulate fields target changed before the native refactoring could run.",
                )
            }
        }
        return cls
    }

    private fun buildFieldDescriptors(
        fields: List<PsiField>,
        preparation: EncapsulateFieldsPreparation,
    ): List<FieldDescriptor> {
        val helper = JavaEncapsulateFieldHelper()
        return fields.mapIndexed { index, field ->
            val getterName = preparation.getterNames[index]
            val setterName = preparation.setterNames[index]
            val getterProto = helper.generateMethodPrototype(field, getterName, true)
            val setterProto = helper.generateMethodPrototype(field, setterName, false)
            FieldDescriptorImpl(field, getterName, setterName, getterProto, setterProto)
        }
    }

    private fun buildDescriptor(
        containingClass: PsiClass,
        descriptors: List<FieldDescriptor>,
        preparation: EncapsulateFieldsPreparation,
    ): EncapsulateFieldsDescriptor {
        return object : EncapsulateFieldsDescriptor {
            override fun getSelectedFields(): Array<FieldDescriptor> = descriptors.toTypedArray()
            override fun getTargetClass(): PsiClass = containingClass
            override fun getFieldsVisibility(): String? = preparation.fieldsVisibility
            override fun getAccessorsVisibility(): String = preparation.accessorsVisibility
            override fun isToEncapsulateGet(): Boolean = preparation.encapsulateGet
            override fun isToEncapsulateSet(): Boolean = preparation.encapsulateSet
            override fun isToUseAccessorsWhenAccessible(): Boolean = preparation.useAccessorsWhenAccessible
            override fun getJavadocPolicy(): Int = 0
        }
    }

    private fun projectRelativeAffectedFiles(
        project: Project,
        usages: Array<UsageInfo>,
        preparation: EncapsulateFieldsPreparation,
        containingClass: PsiClass,
    ): List<String>? {
        val base = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() } ?: return null
        val files = mutableSetOf<String>()
        // containing class file
        val classFile = containingClass.containingFile?.virtualFile ?: return null
        val classRel = relativeProjectPath(base, classFile.path) ?: return null
        files.add(classRel)
        // Also add pathInProject (same as classRel but ensure)
        // files.add(preparation.pathInProject) // already classRel
        for (usage in usages) {
            val element = usage.element ?: return null
            val virtualFile = element.containingFile?.virtualFile ?: return null
            val rel = relativeProjectPath(base, virtualFile.path) ?: return null
            files.add(rel)
        }
        return files.sorted()
    }

    private fun affectedVirtualFiles(
        containingClass: PsiClass,
        usages: Array<UsageInfo>,
    ): Set<VirtualFile> = buildSet {
        containingClass.containingFile?.virtualFile?.let(::add)
        usages.mapNotNullTo(this) { it.element?.containingFile?.virtualFile }
    }

    private fun relativeProjectPath(base: Path, absolutePath: String): String? {
        val absolute = Path.of(absolutePath).toAbsolutePath().normalize()
        if (!absolute.startsWith(base)) return null
        return base.relativize(absolute).toString()
    }

    private data class PreparedNativeExecution(
        val containingClass: PsiClass,
        val fields: List<PsiField>,
        val processor: HeadlessEncapsulateFieldsProcessor,
    )

    private data class NativeUsageFacts(
        val nativeUsageCount: Int,
        val affectedFiles: List<String>?,
        val filesToPersist: Set<VirtualFile>,
    )

    private class HeadlessEncapsulateFieldsProcessor(
        project: Project,
        descriptor: EncapsulateFieldsDescriptor,
    ) : EncapsulateFieldsProcessor(project, descriptor), HeadlessEncapsulateFieldsProcessorOps {
        override fun showConflicts(
            conflicts: MultiMap<PsiElement, String>,
            usages: Array<out UsageInfo>?,
        ): Boolean {
            if (conflicts.isEmpty()) {
                return super.showConflicts(conflicts, usages)
            }
            throw EncapsulateFieldsConflictException(
                conflicts.values().distinct().joinToString(separator = "; "),
            )
        }

        override fun findUsagesNative(): Array<UsageInfo> = findUsages()
    }

    private interface HeadlessEncapsulateFieldsProcessorOps {
        fun findUsagesNative(): Array<UsageInfo>
        fun setPreviewUsages(preview: Boolean)
        fun run()
    }
}
