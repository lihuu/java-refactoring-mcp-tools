package com.example.airefactoring.refactoring.pullup

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
import com.intellij.refactoring.memberPullUp.PullUpProcessor
import com.intellij.refactoring.util.DocCommentPolicy
import com.intellij.refactoring.util.classMembers.MemberInfo
import com.intellij.util.containers.MultiMap
import com.intellij.usageView.UsageInfo
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IntellijPullMembersUpExecutor internal constructor(
    private val documentPersistence: NativeRefactoringDocumentPersister = NativeRefactoringDocumentPersistence(),
) : PullMembersUpExecutor {
    override suspend fun pull(
        project: Project,
        preparation: PullMembersUpPreparation,
    ): PullMembersUpExecutionResult {
        val sourceSub = withContext(Dispatchers.EDT) { requireCurrentSource(preparation) }
        val targetSuper = withContext(Dispatchers.EDT) { requireCurrentTarget(preparation) }
        val members = withContext(Dispatchers.Default) {
            ReadAction.compute<List<PsiMember>, RuntimeException> { requireCurrentMembers(preparation, sourceSub) }
        }
        val memberInfos = withContext(Dispatchers.Default) {
            ReadAction.compute<Array<MemberInfo>, RuntimeException> {
                members.map { m -> MemberInfo(m).apply { isChecked = true; if (m is com.intellij.psi.PsiMethod) isToAbstract = true } }.toTypedArray()
            }
        }
        val usageFacts = withContext(Dispatchers.Default) {
            ReadAction.computeBlocking<PreUsageFacts, RuntimeException> {
                val refs = com.intellij.psi.search.searches.ReferencesSearch.search(sourceSub, GlobalSearchScope.projectScope(project), false).toArray(com.intellij.psi.PsiReference.EMPTY_ARRAY)
                PreUsageFacts(refs.size)
            }
        }
        return withContext(Dispatchers.EDT) {
            requireCurrentSource(preparation)
            requireCurrentTarget(preparation)
            requireCurrentMembers(preparation, sourceSub)
            val docPolicy = DocCommentPolicy(DocCommentPolicy.ASIS)
            val processor = object : PullUpProcessor(sourceSub, targetSuper, memberInfos, docPolicy) {
                override fun showConflicts(conflicts: MultiMap<com.intellij.psi.PsiElement, String>, usages: Array<UsageInfo>?): Boolean {
                    if (!conflicts.isEmpty) throw PullMembersUpConflictException(conflicts.values().joinToString("; "))
                    return true
                }
            }
            processor.setPreviewUsages(false)
            processor.run()
            val sourceFile = ReadAction.compute<VirtualFile?, RuntimeException> { sourceSub.containingFile?.virtualFile } ?: throw IllegalStateException("Source file not found")
            val targetFile = ReadAction.compute<VirtualFile?, RuntimeException> { targetSuper.containingFile?.virtualFile } ?: throw IllegalStateException("Target file not found")
            val filesToPersist = setOf(sourceFile, targetFile)
            documentPersistence.persist(project, filesToPersist)
            val affected = projectRelativeAffectedFiles(project, sourceFile, targetFile)
            PullMembersUpExecutionResult(
                sourceClassQualifiedName = preparation.sourceQualifiedNameSnapshot,
                targetSuperclassFqn = preparation.targetSuperclassFqn,
                memberNames = preparation.memberNameSnapshots,
                nativeUsageCount = usageFacts.count,
                affectedFiles = affected,
                summary = "Pulled ${preparation.memberNameSnapshots.size} member(s) from ${preparation.sourceQualifiedNameSnapshot} to ${preparation.targetSuperclassFqn}.",
            )
        }
    }

    private fun requireCurrentSource(preparation: PullMembersUpPreparation): PsiClass {
        val cls = preparation.sourceSubclassPointer.element?.takeIf { it.isValid } ?: throw PullMembersUpPreparationException("Pull Up source changed.")
        if (cls.qualifiedName != preparation.sourceQualifiedNameSnapshot) throw PullMembersUpPreparationException("Pull Up source changed.")
        return cls
    }
    private fun requireCurrentTarget(preparation: PullMembersUpPreparation): PsiClass {
        val cls = preparation.targetSuperclassPointer.element?.takeIf { it.isValid } ?: throw PullMembersUpPreparationException("Pull Up target changed.")
        if (cls.qualifiedName != preparation.targetSuperclassFqnSnapshot) throw PullMembersUpPreparationException("Pull Up target changed.")
        return cls
    }
    private fun requireCurrentMembers(preparation: PullMembersUpPreparation, sourceSub: PsiClass): List<PsiMember> {
        if (preparation.memberPointers.size != preparation.memberNameSnapshots.size) throw PullMembersUpPreparationException("Pull Up members changed.")
        return preparation.memberPointers.mapIndexed { idx, ptr ->
            val m = ptr.element?.takeIf { it.isValid } ?: throw PullMembersUpPreparationException("Pull Up member changed.")
            if (m.name != preparation.memberNameSnapshots[idx]) throw PullMembersUpPreparationException("Pull Up member changed.")
            if (m.containingClass == null || !PsiManager.getInstance(m.project).areElementsEquivalent(m.containingClass, sourceSub)) throw PullMembersUpPreparationException("Pull Up member changed.")
            m
        }
    }
    private fun projectRelativeAffectedFiles(project: Project, sourceFile: VirtualFile, targetFile: VirtualFile): List<String>? {
        val base = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() } ?: return null
        val s = relativeProjectPath(base, sourceFile.path) ?: return null
        val t = relativeProjectPath(base, targetFile.path) ?: return null
        return listOf(s, t).sorted()
    }
    private fun relativeProjectPath(base: Path, absolutePath: String): String? {
        val abs = Path.of(absolutePath).toAbsolutePath().normalize()
        if (!abs.startsWith(base)) return null
        return base.relativize(abs).toString()
    }
    private data class PreUsageFacts(val count: Int)
}
