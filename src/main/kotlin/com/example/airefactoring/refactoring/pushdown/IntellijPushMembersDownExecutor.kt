package com.example.airefactoring.refactoring.pushdown

import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersistence
import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersister
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.memberPushDown.PushDownProcessor
import com.intellij.refactoring.util.DocCommentPolicy
import com.intellij.refactoring.util.classMembers.MemberInfo
import com.intellij.util.containers.MultiMap
import com.intellij.usageView.UsageInfo
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IntellijPushMembersDownExecutor internal constructor(
    private val documentPersistence: NativeRefactoringDocumentPersister = NativeRefactoringDocumentPersistence(),
) : PushMembersDownExecutor {
    override suspend fun push(
        project: Project,
        preparation: PushMembersDownPreparation,
    ): PushMembersDownExecutionResult {
        val sourceSuper = withContext(Dispatchers.EDT) { requireCurrentSource(preparation) }
        val targetSubs = withContext(Dispatchers.EDT) { requireCurrentTargets(preparation) }
        val members = withContext(Dispatchers.Default) {
            ReadAction.compute<List<PsiMember>, RuntimeException> { requireCurrentMembers(preparation, sourceSuper) }
        }
        val memberInfos = withContext(Dispatchers.Default) {
            ReadAction.compute<List<MemberInfo>, RuntimeException> {
                members.map { m -> MemberInfo(m).apply { isChecked = true; if (m is com.intellij.psi.PsiMethod) isToAbstract = true } }
            }
        }
        val usageFacts = withContext(Dispatchers.Default) {
            ReadAction.computeBlocking<PreUsageFacts, RuntimeException> {
                val refs = com.intellij.psi.search.searches.ReferencesSearch.search(sourceSuper, GlobalSearchScope.projectScope(project), false).toArray(com.intellij.psi.PsiReference.EMPTY_ARRAY)
                PreUsageFacts(refs.size)
            }
        }
        return withContext(Dispatchers.EDT) {
            requireCurrentSource(preparation)
            requireCurrentTargets(preparation)
            requireCurrentMembers(preparation, sourceSuper)
            val docPolicy = DocCommentPolicy(DocCommentPolicy.ASIS)
            // Headless processor that restricts pushes to selected subclass Fqns
            val processor = object : PushDownProcessor<MemberInfo, PsiMember, PsiClass>(sourceSuper, memberInfos, docPolicy) {
                override fun showConflicts(conflicts: MultiMap<com.intellij.psi.PsiElement, String>, usages: Array<UsageInfo>?): Boolean {
                    if (!conflicts.isEmpty) throw PushMembersDownConflictException(conflicts.values().joinToString("; "))
                    return true
                }
                override fun findUsages(): Array<UsageInfo> {
                    // Only selected target subclasses, not all inheritors
                    return targetSubs.map { UsageInfo(it) }.toTypedArray()
                }
            }
            processor.setPreviewUsages(false)
            processor.run()
            val sourceFile = ReadAction.compute<VirtualFile?, RuntimeException> { sourceSuper.containingFile?.virtualFile } ?: throw IllegalStateException("Source file not found")
            val targetFiles = targetSubs.mapNotNull { ReadAction.compute<VirtualFile?, RuntimeException> { it.containingFile?.virtualFile } }
            val filesToPersist = (listOf(sourceFile) + targetFiles).toSet()
            documentPersistence.persist(project, filesToPersist)
            val affected = projectRelativeAffectedFiles(project, sourceFile, targetFiles)
            PushMembersDownExecutionResult(
                sourceClassQualifiedName = preparation.sourceQualifiedNameSnapshot,
                targetSubclassFqns = preparation.targetSubclassFqns,
                memberNames = preparation.memberNameSnapshots,
                nativeUsageCount = usageFacts.count,
                affectedFiles = affected,
                summary = "Pushed ${preparation.memberNameSnapshots.size} member(s) from ${preparation.sourceQualifiedNameSnapshot} to ${preparation.targetSubclassFqns.size} subclass(es).",
            )
        }
    }

    private fun requireCurrentSource(preparation: PushMembersDownPreparation): PsiClass {
        val cls = preparation.sourceSuperclassPointer.element?.takeIf { it.isValid } ?: throw PushMembersDownPreparationException("Push Down source changed.")
        if (cls.qualifiedName != preparation.sourceQualifiedNameSnapshot) throw PushMembersDownPreparationException("Push Down source changed.")
        return cls
    }
    private fun requireCurrentTargets(preparation: PushMembersDownPreparation): List<PsiClass> {
        if (preparation.targetSubclassPointers.size != preparation.targetSubclassFqnsSnapshot.size) throw PushMembersDownPreparationException("Push Down targets changed.")
        return preparation.targetSubclassPointers.mapIndexed { idx, ptr ->
            val cls = ptr.element?.takeIf { it.isValid } ?: throw PushMembersDownPreparationException("Push Down target changed.")
            if (cls.qualifiedName != preparation.targetSubclassFqnsSnapshot[idx]) throw PushMembersDownPreparationException("Push Down target changed.")
            cls
        }
    }
    private fun requireCurrentMembers(preparation: PushMembersDownPreparation, sourceSuper: PsiClass): List<PsiMember> {
        if (preparation.memberPointers.size != preparation.memberNameSnapshots.size) throw PushMembersDownPreparationException("Push Down members changed.")
        return preparation.memberPointers.mapIndexed { idx, ptr ->
            val m = ptr.element?.takeIf { it.isValid } ?: throw PushMembersDownPreparationException("Push Down member changed.")
            if (m.name != preparation.memberNameSnapshots[idx]) throw PushMembersDownPreparationException("Push Down member changed.")
            if (m.containingClass == null || !PsiManager.getInstance(m.project).areElementsEquivalent(m.containingClass, sourceSuper)) throw PushMembersDownPreparationException("Push Down member changed.")
            m
        }
    }
    private fun projectRelativeAffectedFiles(project: Project, sourceFile: VirtualFile, targetFiles: List<VirtualFile>): List<String>? {
        val base = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() } ?: return null
        val s = relativeProjectPath(base, sourceFile.path) ?: return null
        val t = targetFiles.mapNotNull { relativeProjectPath(base, it.path) }
        if (t.size != targetFiles.size) return null
        return (listOf(s) + t).sorted()
    }
    private fun relativeProjectPath(base: Path, absolutePath: String): String? {
        val abs = Path.of(absolutePath).toAbsolutePath().normalize()
        if (!abs.startsWith(base)) return null
        return base.relativize(abs).toString()
    }
    private data class PreUsageFacts(val count: Int)
}
