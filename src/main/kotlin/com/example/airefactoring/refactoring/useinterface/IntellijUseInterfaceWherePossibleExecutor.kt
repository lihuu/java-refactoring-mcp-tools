package com.example.airefactoring.refactoring.useinterface

import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersistence
import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersister
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.turnRefsToSuper.TurnRefsToSuperProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IntellijUseInterfaceWherePossibleExecutor internal constructor(
    private val documentPersistence: NativeRefactoringDocumentPersister = NativeRefactoringDocumentPersistence(),
) : UseInterfaceWherePossibleExecutor {
    override suspend fun useInterface(
        project: Project,
        preparation: UseInterfaceWherePossiblePreparation,
    ): UseInterfaceWherePossibleExecutionResult {
        val source = withContext(Dispatchers.EDT) { requireCurrentSource(preparation) }
        val target = withContext(Dispatchers.EDT) { requireCurrentTarget(preparation) }
        val usageFacts = withContext(Dispatchers.Default) {
            ReadAction.computeBlocking<PreUsageFacts, RuntimeException> {
                val refs = ReferencesSearch.search(source, GlobalSearchScope.projectScope(project), false)
                    .toArray(com.intellij.psi.PsiReference.EMPTY_ARRAY)
                val files = refs.mapNotNull { it.element?.containingFile?.virtualFile }.toSet()
                PreUsageFacts(refs.size, files)
            }
        }
        return withContext(Dispatchers.EDT) {
            requireCurrentSource(preparation)
            requireCurrentTarget(preparation)
            var capturedRenames: List<String> = emptyList()
            val processor = object : TurnRefsToSuperProcessor(project, source, target, false) {
                override fun preprocessUsages(usages: Ref<Array<UsageInfo>>): Boolean {
                    val ok = super.preprocessUsages(usages)
                    capturedRenames = myVariablesRenames.entries.mapNotNull { (ptr, newName) ->
                        val oldName = (ptr.element as? PsiNamedElement)?.name ?: return@mapNotNull null
                        if (oldName != newName) "$oldName -> $newName" else null
                    }
                    return ok
                }

                override fun showConflicts(conflicts: MultiMap<PsiElement, String>, usages: Array<UsageInfo>?): Boolean {
                    if (!conflicts.isEmpty) throw UseInterfaceConflictException(conflicts.values().joinToString("; "))
                    return true
                }
            }
            processor.setPreviewUsages(false)
            processor.run()
            val sourceFile = readAction {
                source.containingFile?.virtualFile
            } ?: throw IllegalStateException("Source file not found")
            val filesToPersist = usageFacts.files + sourceFile
            documentPersistence.persist(project, filesToPersist)
            val affected = projectRelativeAffectedFiles(project, filesToPersist)
            UseInterfaceWherePossibleExecutionResult(
                sourceClassQualifiedName = preparation.sourceQualifiedNameSnapshot,
                targetInterfaceFqn = preparation.targetInterfaceFqn,
                nativeUsageCount = usageFacts.count,
                affectedFiles = affected,
                renamedVariables = capturedRenames.takeIf { it.isNotEmpty() },
                summary = "Rewrote type usages of ${preparation.sourceQualifiedNameSnapshot} to interface ${preparation.targetInterfaceFqn}.",
            )
        }
    }

    private fun requireCurrentSource(preparation: UseInterfaceWherePossiblePreparation): com.intellij.psi.PsiClass {
        val cls = preparation.sourceClassPointer.element?.takeIf { it.isValid }
            ?: throw UseInterfaceWherePossiblePreparationException("Use Interface source changed.")
        if (cls.qualifiedName != preparation.sourceQualifiedNameSnapshot) {
            throw UseInterfaceWherePossiblePreparationException("Use Interface source changed.")
        }
        return cls
    }

    private fun requireCurrentTarget(preparation: UseInterfaceWherePossiblePreparation): com.intellij.psi.PsiClass {
        val cls = preparation.targetInterfacePointer.element?.takeIf { it.isValid }
            ?: throw UseInterfaceWherePossiblePreparationException("Use Interface target changed.")
        if (cls.qualifiedName != preparation.targetInterfaceFqnSnapshot) {
            throw UseInterfaceWherePossiblePreparationException("Use Interface target changed.")
        }
        return cls
    }

    private fun projectRelativeAffectedFiles(
        project: Project,
        files: Set<com.intellij.openapi.vfs.VirtualFile>,
    ): List<String>? {
        val base = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() } ?: return null
        val relative = files.mapNotNull { file ->
            val abs = Path.of(file.path).toAbsolutePath().normalize()
            if (!abs.startsWith(base)) return@mapNotNull null
            base.relativize(abs).toString()
        }
        return relative.takeIf { it.size == files.size }?.sorted()
    }

    private data class PreUsageFacts(val count: Int, val files: Set<com.intellij.openapi.vfs.VirtualFile>)
}
