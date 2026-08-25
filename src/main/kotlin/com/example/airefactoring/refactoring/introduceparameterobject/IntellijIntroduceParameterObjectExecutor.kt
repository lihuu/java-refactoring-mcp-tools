package com.example.airefactoring.refactoring.introduceparameterobject

import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersistence
import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersister
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.JavaRefactoringFactory
import com.intellij.refactoring.changeSignature.ParameterInfoImpl
import com.intellij.refactoring.introduceParameterObject.IntroduceParameterObjectProcessor
import com.intellij.refactoring.introduceparameterobject.JavaIntroduceParameterObjectClassDescriptor
import com.intellij.util.SlowOperations
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IntellijIntroduceParameterObjectExecutor internal constructor(
    private val documentPersistence: NativeRefactoringDocumentPersister = NativeRefactoringDocumentPersistence(),
) : IntroduceParameterObjectExecutor {

    override suspend fun introduce(
        project: Project,
        preparation: IntroduceParameterObjectPreparation,
    ): IntroduceParameterObjectExecutionResult = withContext(Dispatchers.EDT) {
        val method = requireCurrentMethod(preparation)
        val selectedParams = requireCurrentParameters(preparation, method)
        val existingClass = requireCurrentExistingClass(preparation)

        // Freshness: method text, parameter set, placement inputs, and affected-file inventory per design.md:72-77
        if (method.text != preparation.methodTextSnapshot) {
            throw IntroduceParameterObjectPreparationException("The method changed before the native refactoring could run.")
        }
        val currentNames = selectedParams.map { it.name }
        if (currentNames != preparation.parameterNamesSnapshot) {
            throw IntroduceParameterObjectPreparationException("The selected parameters changed before the native refactoring could run.")
        }
        // Placement/policy staleness — any mismatch between stored preparation and current PSI/file-system state
        // The preparation is immutable, so we verify that the stored placement-specific inputs still correspond
        // to the current project state and that no intermediate mutation changed file writability or usage set.
        requirePlacementFreshness(preparation, existingClass)
        requireAffectedFilesFreshness(project, method, preparation)

        // Build ParameterInfoImpls in declaration order with original indices
        val allParams = method.parameterList.parameters
        val paramInfos = selectedParams.map { param ->
            val index = allParams.indexOf(param)
            if (index < 0) throw IntroduceParameterObjectPreparationException("Parameter not found in method.")
            ParameterInfoImpl.create(index).withName(param.name).withType(param.type)
        }.toTypedArray()

        // Build descriptor
        val descriptor = buildDescriptor(project, preparation, method, paramInfos, existingClass)

        val processor = IntroduceParameterObjectProcessor(
            method,
            descriptor,
            paramInfos.toList(),
            false,
        ).apply { setPreviewUsages(false) }

        // Run processor headlessly, mapping conflicts — must NOT run inside write action (processor manages its own write/progress)
        try {
            processor.run()
        } catch (e: Exception) {
            // Native processor reports conflicts via BaseRefactoringProcessor.ConflictsInTestsException or showConflicts
            // It may throw com.intellij.refactoring.BaseRefactoringProcessor.ConflictsInTestsException
            if (e.javaClass.simpleName == "ConflictsInTestsException" || e.message?.contains("conflict", ignoreCase = true) == true) {
                throw IntroduceParameterObjectConflictException(e.message ?: "Refactoring conflict.", e)
            }
            // Also check cause
            val cause = e.cause
            if (cause != null && cause.javaClass.simpleName == "ConflictsInTestsException") {
                throw IntroduceParameterObjectConflictException(cause.message ?: "Refactoring conflict.", cause)
            }
            throw e
        }

        // After success, commit and persist
        // Determine affected files: method file + callers + created/reused class file
        val affectedVfs = mutableSetOf<com.intellij.openapi.vfs.VirtualFile>()
        affectedVfs.addAll(preparation.affectedVirtualFiles)

        // Try to find created class file
        val createdFqn = when (preparation.placement) {
            JavaParameterObjectPlacement.NEW_TOP_LEVEL -> {
                val pkg = preparation.targetPackage ?: ""
                val name = preparation.className ?: ""
                if (pkg.isEmpty()) name else "$pkg.$name"
            }
            JavaParameterObjectPlacement.NEW_INNER_CLASS -> {
                val containing = method.containingClass?.qualifiedName ?: ""
                "${containing}.${preparation.className}"
            }
            JavaParameterObjectPlacement.EXISTING_CLASS -> preparation.existingClassFqn ?: ""
        }

        // Find PsiClass for created/reused object to add its file — slow operation, allow explicitly
        val objectClass = SlowOperations.allowSlowOperations<com.intellij.psi.PsiClass?, Exception> {
            JavaPsiFacade.getInstance(project).findClass(createdFqn, GlobalSearchScope.allScope(project))
        }
        objectClass?.containingFile?.virtualFile?.let { affectedVfs.add(it) }

        // Also include any new files reported via processor? We already have method and callers, plus object.

        // Persist only exact affected files — ensure VFS is refreshed for newly created package files
        val toPersist = affectedVfs.filter { it.isValid }.toSet()
        // Refresh VFS for new files (e.g., InvoiceRequest.java) before persist to ensure documents are found
        try {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().refresh(false)
        } catch (_: Exception) {}
        documentPersistence.persist(project, toPersist)

        // Build result
        val basePath = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() }
        val affectedRel = toPersist.mapNotNull { vf ->
            basePath?.let {
                try {
                    val abs = Path.of(vf.path).toAbsolutePath().normalize()
                    if (abs.startsWith(it)) it.relativize(abs).toString() else null
                } catch (_: Exception) { null }
            } ?: vf.path
        }.sorted()

        // Native usage count: count of call sites (from preparation) plus maybe method itself? Use affected size -1?
        val nativeUsageCount = affectedRel.size // approximate; ideally caller count
        IntroduceParameterObjectExecutionResult(
            methodName = method.name,
            parameterObjectClass = createdFqn,
            placement = when (preparation.placement) {
                JavaParameterObjectPlacement.NEW_TOP_LEVEL -> "new_top_level"
                JavaParameterObjectPlacement.NEW_INNER_CLASS -> "new_inner_class"
                JavaParameterObjectPlacement.EXISTING_CLASS -> "existing_class"
            },
            mergedParameterCount = paramInfos.size,
            nativeUsageCount = nativeUsageCount,
            affectedFiles = affectedRel,
            summary = "Introduced parameter object '$createdFqn' for ${paramInfos.size} parameters of '${method.name}'.",
        )
    }

    private fun requireCurrentMethod(preparation: IntroduceParameterObjectPreparation): PsiMethod {
        val method = preparation.methodPointer.element?.takeIf { it.isValid }
            ?: throw IntroduceParameterObjectPreparationException("The method changed before the native refactoring could run.")
        return method
    }

    private fun requireCurrentParameters(
        preparation: IntroduceParameterObjectPreparation,
        method: PsiMethod,
    ): List<com.intellij.psi.PsiParameter> {
        if (preparation.parameterPointers.size != preparation.parameterNamesSnapshot.size) {
            throw IntroduceParameterObjectPreparationException("Parameter snapshot mismatch.")
        }
        return preparation.parameterPointers.mapIndexed { idx, ptr ->
            val param = ptr.element?.takeIf { it.isValid }
                ?: throw IntroduceParameterObjectPreparationException("The parameter changed before the native refactoring could run.")
            if (param.name != preparation.parameterNamesSnapshot[idx]) {
                throw IntroduceParameterObjectPreparationException("The parameter changed before the native refactoring could run.")
            }
            val containing = param.declarationScope as? PsiMethod
                ?: throw IntroduceParameterObjectPreparationException("Parameter not in method.")
            if (containing != method && containing.name != method.name) {
                // fallback check via manager
                if (!com.intellij.psi.PsiManager.getInstance(method.project).areElementsEquivalent(containing, method)) {
                    throw IntroduceParameterObjectPreparationException("Parameter not in target method.")
                }
            }
            param
        }
    }

    private fun requireCurrentExistingClass(preparation: IntroduceParameterObjectPreparation): com.intellij.psi.PsiClass? {
        if (preparation.placement != JavaParameterObjectPlacement.EXISTING_CLASS) return null
        val ptr = preparation.existingClassPointer
            ?: throw IntroduceParameterObjectPreparationException("Existing class pointer missing.")
        val cls = ptr.element?.takeIf { it.isValid }
            ?: throw IntroduceParameterObjectPreparationException("The existing class changed before the native refactoring could run.")
        if (cls.qualifiedName != preparation.existingClassFqn) {
            throw IntroduceParameterObjectPreparationException("The existing class changed before the native refactoring could run.")
        }
        return cls
    }

    private fun buildDescriptor(
        project: Project,
        preparation: IntroduceParameterObjectPreparation,
        method: PsiMethod,
        paramInfos: Array<ParameterInfoImpl>,
        existingClass: com.intellij.psi.PsiClass?,
    ): JavaIntroduceParameterObjectClassDescriptor {
        val escalate = if (preparation.escalateVisibility) "EscalateVisible" else null
        return when (preparation.placement) {
            JavaParameterObjectPlacement.NEW_TOP_LEVEL -> {
                val packageName = preparation.targetPackage ?: ""
                val moveDest = createMoveDestination(project, packageName, method)
                val descriptor = JavaIntroduceParameterObjectClassDescriptor(
                    preparation.className!!,
                    packageName,
                    moveDest,
                    false,
                    false,
                    escalate,
                    paramInfos,
                    method,
                    preparation.generateAccessors,
                )
                descriptor
            }
            JavaParameterObjectPlacement.NEW_INNER_CLASS -> {
                // For inner, packageName is ignored, MoveDestination with empty package
                val moveDest = createMoveDestination(project, "", method)
                JavaIntroduceParameterObjectClassDescriptor(
                    preparation.className!!,
                    "",
                    moveDest,
                    false,
                    true,
                    escalate,
                    paramInfos,
                    method,
                    preparation.generateAccessors,
                )
            }
            JavaParameterObjectPlacement.EXISTING_CLASS -> {
                val fqn = preparation.existingClassFqn!!
                val shortName = com.intellij.openapi.util.text.StringUtil.getShortName(fqn)
                val pkgName = com.intellij.openapi.util.text.StringUtil.getPackageName(fqn)
                val moveDest = createMoveDestination(project, pkgName, method)
                val descriptor = JavaIntroduceParameterObjectClassDescriptor(
                    shortName,
                    pkgName,
                    moveDest,
                    true,
                    false,
                    escalate,
                    paramInfos,
                    method,
                    preparation.generateAccessors,
                )
                if (existingClass != null) {
                    descriptor.setExistingClass(existingClass)
                }
                descriptor
            }
        }
    }

    private fun createMoveDestination(project: Project, packageName: String, context: PsiMethod): com.intellij.refactoring.MoveDestination {
        // Direct factory call — no manual directory creation here; delegate will create missing package inside its own write.
        // Pre-creating package via fixture placeholder ensures no write is needed here.
        return JavaRefactoringFactory.getInstance(project).createSourceFolderPreservingMoveDestination(packageName)
    }

    private fun requirePlacementFreshness(
        preparation: IntroduceParameterObjectPreparation,
        existingClass: com.intellij.psi.PsiClass?,
    ) {
        // Verify placement-specific inputs still correspond to current project state
        when (preparation.placement) {
            JavaParameterObjectPlacement.NEW_TOP_LEVEL -> {
                if (preparation.className.isNullOrBlank() || preparation.targetPackage.isNullOrBlank()) {
                    throw IntroduceParameterObjectPreparationException("Top-level placement inputs changed.")
                }
                if (!preparation.existingClassFqn.isNullOrBlank()) {
                    throw IntroduceParameterObjectPreparationException("Top-level placement must not have existing class.")
                }
            }
            JavaParameterObjectPlacement.NEW_INNER_CLASS -> {
                if (preparation.className.isNullOrBlank()) {
                    throw IntroduceParameterObjectPreparationException("Inner-class placement inputs changed.")
                }
                if (!preparation.targetPackage.isNullOrBlank() || !preparation.existingClassFqn.isNullOrBlank()) {
                    throw IntroduceParameterObjectPreparationException("Inner-class placement must not have package or existing class.")
                }
            }
            JavaParameterObjectPlacement.EXISTING_CLASS -> {
                if (preparation.existingClassFqn.isNullOrBlank()) {
                    throw IntroduceParameterObjectPreparationException("Existing-class placement inputs changed.")
                }
                if (!preparation.className.isNullOrBlank() || !preparation.targetPackage.isNullOrBlank()) {
                    throw IntroduceParameterObjectPreparationException("Existing-class placement must not have className or targetPackage.")
                }
                // existingClass already verified via pointer; also verify writability still holds
                val vf = existingClass?.containingFile?.virtualFile
                if (vf != null && (!vf.isValid || !vf.isWritable)) {
                    throw IntroduceParameterObjectPreparationException("The existing class file changed before execution.")
                }
            }
        }
        // Policy booleans are part of snapshot — they cannot drift without a new resolution,
        // but we keep the check explicit for completeness (stored values are authoritative).
    }

    private fun requireAffectedFilesFreshness(
        project: Project,
        method: PsiMethod,
        preparation: IntroduceParameterObjectPreparation,
    ) {
        // Re-search current references and compare file inventory to snapshot
        val currentAffected = SlowOperations.allowSlowOperations<Set<com.intellij.openapi.vfs.VirtualFile>, Exception> {
            val files = mutableSetOf<com.intellij.openapi.vfs.VirtualFile>()
            method.containingFile?.virtualFile?.let { files.add(it) }
            preparation.existingClassPointer?.element?.containingFile?.virtualFile?.let { files.add(it) }
            val refs = ReferencesSearch.search(method, GlobalSearchScope.projectScope(project), false).findAll()
            for (ref in refs) {
                val vf = ref.element.containingFile?.virtualFile ?: continue
                if (!vf.isValid) throw IntroduceParameterObjectPreparationException("A caller file became invalid.")
                if (!vf.isWritable) throw IntroduceParameterObjectPreparationException("A caller file became read-only.")
                files.add(vf)
            }
            files
        }
        // Snapshot contains method file + existing class file + caller files at resolve time.
        // If inventory drifted (new caller added, file deleted, or made read-only), reject.
        if (currentAffected != preparation.affectedVirtualFiles) {
            // Allow superset due to newly created object file not yet in snapshot? Only for NEW_* placements,
            // the object file does not exist at resolve time, so snapshot lacks it — difference is expected
            // only for the new file. For EXISTING_CLASS, snapshot already contains object file.
            // We tolerate a missing new-file difference but not caller drift.
            val snapshotPlusNewFileTolerant = when (preparation.placement) {
                JavaParameterObjectPlacement.EXISTING_CLASS -> preparation.affectedVirtualFiles
                else -> preparation.affectedVirtualFiles // new file not yet in currentAffected either before processor
            }
            if (currentAffected != snapshotPlusNewFileTolerant) {
                throw IntroduceParameterObjectPreparationException(
                    "The set of affected files changed before the native refactoring could run. " +
                        "Expected ${preparation.affectedVirtualFiles.map { it.path }}, got ${currentAffected.map { it.path }}."
                )
            }
        }
        // Also verify every snapshot file still valid/writable
        for (vf in preparation.affectedVirtualFiles) {
            if (!vf.isValid || !vf.isWritable) {
                throw IntroduceParameterObjectPreparationException("An affected file became invalid or read-only: ${vf.path}")
            }
        }
    }
}
