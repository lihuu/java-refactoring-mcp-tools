package com.example.airefactoring.refactoring.extractinterface

import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersistence
import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersister
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.util.DocCommentPolicy
import com.intellij.refactoring.util.classMembers.MemberInfo
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IntellijExtractInterfaceExecutor internal constructor(
    private val documentPersistence: NativeRefactoringDocumentPersister =
        NativeRefactoringDocumentPersistence(),
) : ExtractInterfaceExecutor {

    override suspend fun extract(
        project: Project,
        preparation: ExtractInterfacePreparation,
    ): ExtractInterfaceExecutionResult {
        val sourceClass = withContext(Dispatchers.EDT) { requireCurrentSourceClass(preparation) }
        val members = withContext(Dispatchers.Default) {
            ReadAction.compute<List<PsiMember>, RuntimeException> {
                requireCurrentMembers(preparation, sourceClass)
            }
        }

        // Build memberInfos (reads only)
        val memberInfos = withContext(Dispatchers.Default) {
            ReadAction.compute<Array<MemberInfo>, RuntimeException> {
                members.map { member ->
                    MemberInfo(member).apply {
                        isChecked = true
                        if (member is com.intellij.psi.PsiMethod) isToAbstract = true
                    }
                }.toTypedArray()
            }
        }

        // Find usages before mutation (for affected files & count)
        val usageFactsBefore = withContext(Dispatchers.Default) {
            ReadAction.computeBlocking<PreUsageFacts, RuntimeException> {
                val refs = com.intellij.psi.search.searches.ReferencesSearch.search(
                    sourceClass,
                    GlobalSearchScope.projectScope(project),
                    false,
                ).toArray(com.intellij.psi.PsiReference.EMPTY_ARRAY)
                PreUsageFacts(refs.size)
            }
        }

        return withContext(Dispatchers.EDT) {
            // Revalidate freshness inside write
            requireCurrentSourceClass(preparation)
            requireCurrentMembers(preparation, sourceClass)

            // Resolve target directory (may create package) before conflict checks
            val targetDirectory = resolveTargetDirectory(project, sourceClass, preparation.targetPackage)

            // Conflict checks before mutation (needs read)
            val qualified = preparation.effectiveQualifiedNameSnapshot
            val existingClass = ReadAction.compute<PsiClass?, RuntimeException> {
                JavaPsiFacade.getInstance(project).findClass(qualified, GlobalSearchScope.allScope(project))
            }
            if (existingClass != null) {
                throw ExtractInterfaceConflictException("Interface '$qualified' already exists.")
            }
            val canCreate = ReadAction.compute<String?, RuntimeException> {
                com.intellij.refactoring.util.RefactoringMessageUtil.checkCanCreateClass(targetDirectory, preparation.interfaceName)
            }
            if (canCreate != null) {
                throw ExtractInterfaceConflictException(canCreate)
            }
            val existingFile = ReadAction.compute<PsiJavaFile?, RuntimeException> {
                targetDirectory.findFile(preparation.interfaceName + ".java") as? PsiJavaFile
            }
            if (existingFile != null) {
                throw ExtractInterfaceConflictException("File '${preparation.interfaceName}.java' already exists in target package.")
            }

            // Perform native extraction via handler's package-private method
            val docPolicy = DocCommentPolicy(DocCommentPolicy.ASIS)
            val method = com.intellij.refactoring.extractInterface.ExtractInterfaceHandler::class.java.getDeclaredMethod(
                "extractInterface",
                PsiDirectory::class.java,
                PsiClass::class.java,
                String::class.java,
                Array<MemberInfo>::class.java,
                DocCommentPolicy::class.java,
            )
            method.isAccessible = true
            // Use WriteCommandAction to ensure single Undo
            WriteCommandAction.runWriteCommandAction(project) {
                method.invoke(null, targetDirectory, sourceClass, preparation.interfaceName, memberInfos, docPolicy)
            }

            // After mutation, find new interface file (read)
            val newInterface = ReadAction.compute<PsiClass?, RuntimeException> {
                JavaPsiFacade.getInstance(project).findClass(qualified, GlobalSearchScope.allScope(project))
            } ?: throw IllegalStateException("New interface '$qualified' not found after extraction.")
            val newFile = ReadAction.compute<VirtualFile?, RuntimeException> { newInterface.containingFile?.virtualFile }
                ?: throw IllegalStateException("New interface file not found.")

            val sourceFile = ReadAction.compute<VirtualFile?, RuntimeException> { sourceClass.containingFile?.virtualFile }
                ?: throw IllegalStateException("Source file not found.")

            val filesToPersist = setOf(sourceFile, newFile)
            documentPersistence.persist(project, filesToPersist)

            val affected = projectRelativeAffectedFiles(project, sourceFile, newFile)
            ExtractInterfaceExecutionResult(
                sourceClassQualifiedName = preparation.sourceClassQualifiedNameSnapshot,
                interfaceName = preparation.interfaceName,
                qualifiedInterfaceName = qualified,
                memberNames = preparation.memberNameSnapshots,
                targetPackage = preparation.targetPackage,
                nativeUsageCount = usageFactsBefore.count,
                affectedFiles = affected,
                summary = "Extracted interface $qualified from ${preparation.sourceClassQualifiedNameSnapshot} with ${preparation.memberNameSnapshots.size} member(s).",
            )
        }
    }

    private fun requireCurrentSourceClass(preparation: ExtractInterfacePreparation): PsiClass {
        val cls = preparation.sourceClassPointer.element?.takeIf { it.isValid }
            ?: throw ExtractInterfacePreparationException("The extract interface source class changed before the native refactoring could run.")
        if (cls.qualifiedName != preparation.sourceClassQualifiedNameSnapshot) {
            throw ExtractInterfacePreparationException("The extract interface source class changed before the native refactoring could run.")
        }
        val pkg = (cls.containingFile as? PsiJavaFile)?.packageName ?: ""
        if (pkg != preparation.sourceClassPackageSnapshot) {
            throw ExtractInterfacePreparationException("The extract interface source class changed before the native refactoring could run.")
        }
        return cls
    }

    private fun requireCurrentMembers(
        preparation: ExtractInterfacePreparation,
        sourceClass: PsiClass,
    ): List<PsiMember> {
        if (preparation.memberPointers.size != preparation.memberNameSnapshots.size ||
            preparation.memberPointers.size != preparation.memberSignatureSnapshots.size
        ) {
            throw ExtractInterfacePreparationException("The extract interface target changed before the native refactoring could run.")
        }
        return preparation.memberPointers.mapIndexed { index, pointer ->
            val member = pointer.element?.takeIf { it.isValid }
                ?: throw ExtractInterfacePreparationException("The extract interface target changed before the native refactoring could run.")
            if (member.name != preparation.memberNameSnapshots[index]) {
                throw ExtractInterfacePreparationException("The extract interface target changed before the native refactoring could run.")
            }
            val sig = when (member) {
                is com.intellij.psi.PsiMethod -> member.name + member.parameterList.parameters.joinToString(",", "(", ")") { it.type.canonicalText } + ":" + member.returnType?.canonicalText
                is com.intellij.psi.PsiField -> member.name + ":" + member.type.canonicalText
                else -> member.name ?: ""
            }
            if (sig != preparation.memberSignatureSnapshots[index]) {
                throw ExtractInterfacePreparationException("The extract interface target changed before the native refactoring could run.")
            }
            if (member.containingClass == null || !PsiManager.getInstance(member.project).areElementsEquivalent(member.containingClass, sourceClass)) {
                throw ExtractInterfacePreparationException("The extract interface target changed before the native refactoring could run.")
            }
            member
        }
    }

    private fun resolveTargetDirectory(project: Project, sourceClass: PsiClass, targetPackage: String?): PsiDirectory {
        if (targetPackage == null) {
            return sourceClass.containingFile?.containingDirectory
                ?: throw ExtractInterfacePreparationException("Unable to resolve source directory.")
        }
        // Find existing package
        val psiPackage = JavaPsiFacade.getInstance(project).findPackage(targetPackage)
        if (psiPackage != null) {
            val dirs = psiPackage.getDirectories(GlobalSearchScope.projectScope(project))
            if (dirs.isNotEmpty()) {
                // Prefer directory under same source root as source class
                val sourceFile = sourceClass.containingFile?.virtualFile
                val sourceRoot = sourceFile?.let { com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).getSourceRootForFile(it) }
                if (sourceRoot != null) {
                    for (dir in dirs) {
                        val dirRoot = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).getSourceRootForFile(dir.virtualFile)
                        if (dirRoot != null && dirRoot == sourceRoot) return dir
                    }
                }
                return dirs[0]
            }
        }
        // Package does not exist or has no directory — create it under same source root as source
        val sourceFile = sourceClass.containingFile?.virtualFile
            ?: throw ExtractInterfacePreparationException("Unable to resolve source file for package creation.")
        val sourceRoot = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).getSourceRootForFile(sourceFile)
            ?: throw ExtractInterfacePreparationException("Unable to resolve source root for package creation.")
        // Create directories via VFS (must be inside write)
        return com.intellij.openapi.application.WriteAction.compute<PsiDirectory, RuntimeException> {
            var currentDir = PsiManager.getInstance(project).findDirectory(sourceRoot)
                ?: throw ExtractInterfacePreparationException("Unable to find source root directory.")
            for (segment in targetPackage.split(".")) {
                var next = currentDir.findSubdirectory(segment)
                if (next == null) {
                    next = currentDir.createSubdirectory(segment)
                }
                currentDir = next
            }
            currentDir
        }
    }

    private fun projectRelativeAffectedFiles(
        project: Project,
        sourceFile: VirtualFile,
        newFile: VirtualFile,
    ): List<String>? {
        val base = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() } ?: return null
        val sourceRel = relativeProjectPath(base, sourceFile.path) ?: return null
        val newRel = relativeProjectPath(base, newFile.path) ?: return null
        return listOf(sourceRel, newRel).sorted()
    }

    private fun relativeProjectPath(base: Path, absolutePath: String): String? {
        val absolute = Path.of(absolutePath).toAbsolutePath().normalize()
        if (!absolute.startsWith(base)) return null
        return base.relativize(absolute).toString()
    }

    private data class PreUsageFacts(val count: Int)
}
