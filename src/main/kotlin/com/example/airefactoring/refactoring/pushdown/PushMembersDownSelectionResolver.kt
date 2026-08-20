package com.example.airefactoring.refactoring.pushdown

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.JavaSourceTarget
import com.example.airefactoring.refactoring.JavaSourceTargetResolution
import com.example.airefactoring.refactoring.JavaSourceTargetResolver
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameHelper
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Path

class PushMembersDownSelectionResolver(
    private val targetResolver: JavaSourceTargetResolver = JavaSourceTargetResolver(),
) {
    fun resolve(
        project: Project,
        pathInProject: String,
        sourceSuperclassStartLine: Int,
        sourceSuperclassStartColumn: Int,
        sourceSuperclassEndLine: Int,
        sourceSuperclassEndColumn: Int,
        memberStartLines: List<Int>,
        memberStartColumns: List<Int>,
        memberEndLines: List<Int>,
        memberEndColumns: List<Int>,
        targetSubclassFqns: List<String>,
    ): PushMembersDownSelectionResolution {
        val n = memberStartLines.size
        if (n == 0) return failure(McpRefactoringErrorCode.INVALID_RANGE, "At least one member must be selected.")
        if (listOf(memberStartColumns.size, memberEndLines.size, memberEndColumns.size).any { it != n }) {
            return failure(McpRefactoringErrorCode.INVALID_RANGE, "Member range lists must have equal lengths.")
        }
        if (targetSubclassFqns.isEmpty()) return failure(McpRefactoringErrorCode.INVALID_RANGE, "At least one target subclass must be specified.")
        val trimmedFqns = targetSubclassFqns.map { it.trim() }
        if (trimmedFqns.any { it.isEmpty() }) return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Target subclass FQN must not be empty.")
        for (fqn in trimmedFqns) {
            if (!PsiNameHelper.getInstance(project).isQualifiedName(fqn)) {
                return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Target subclass FQN '$fqn' is not a valid qualified name.")
            }
        }
        if (trimmedFqns.size != trimmedFqns.toSet().size) return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Target subclass FQNs must be unique.")
        val targetClasses = mutableListOf<PsiClass>()
        for (fqn in trimmedFqns) {
            val cls = com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))
                ?: return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Target subclass '$fqn' not found.")
            targetClasses.add(cls)
        }
        val classRange = SourceRange(sourceSuperclassStartLine, sourceSuperclassStartColumn, sourceSuperclassEndLine, sourceSuperclassEndColumn)
        val classTarget = when (val r = targetResolver.resolve(project, pathInProject, classRange)) {
            is JavaSourceTargetResolution.Failure -> return failure(r.code, r.message)
            is JavaSourceTargetResolution.Success -> r.target
        }
        val sourceClass = exactDeclaration(classTarget, PsiClass::class.java)
            ?: return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Source superclass range must exactly match a class declaration name.")
        if (sourceClass.isInterface || sourceClass.isEnum || sourceClass.isAnnotationType) {
            return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Source must be a concrete class.")
        }
        val sourceFqn = sourceClass.qualifiedName ?: return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Source class must have a qualified name.")
        // direct subclass check via target's direct super
        for ((idx, fqn) in trimmedFqns.withIndex()) {
            val target = targetClasses[idx]
            val superOfTarget = target.superClass
            val isDirect = superOfTarget != null && com.intellij.psi.PsiManager.getInstance(project).areElementsEquivalent(superOfTarget, sourceClass)
            if (!isDirect) {
                return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Target '$fqn' is not a direct subclass of '$sourceFqn'.")
            }
        }
        val members = mutableListOf<PsiMember>()
        val seen = mutableSetOf<PsiMember>()
        for (i in 0 until n) {
            val range = SourceRange(memberStartLines[i], memberStartColumns[i], memberEndLines[i], memberEndColumns[i])
            val target = when (val r = targetResolver.resolve(project, pathInProject, range)) {
                is JavaSourceTargetResolution.Failure -> return failure(r.code, r.message)
                is JavaSourceTargetResolution.Success -> r.target
            }
            val member: PsiMember? = exactDeclaration(target, PsiMethod::class.java) as PsiMember?
                ?: exactDeclaration(target, PsiField::class.java) as PsiMember?
            if (member == null) return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Each selected member must exactly match a method or field declaration name.")
            if (!seen.add(member)) return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "A member cannot be selected more than once.")
            val containing = member.containingClass ?: return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Member '${member.name}' has no containing class.")
            if (!com.intellij.psi.PsiManager.getInstance(project).areElementsEquivalent(containing, sourceClass)) {
                if (containing.qualifiedName != sourceClass.qualifiedName) return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "All members must belong to the source superclass.")
                if (!com.intellij.psi.PsiManager.getInstance(project).areElementsEquivalent(containing, sourceClass)) return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "All members must belong to the source superclass.")
            }
            when (member) {
                is PsiMethod -> {
                    if (member.isConstructor) return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Constructors cannot be moved.")
                    if (!member.hasModifierProperty("public")) return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Only public methods can be moved in V1: '${member.name}'.")
                    if (member.hasModifierProperty("static")) return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Static methods cannot be moved in V1: '${member.name}'.")
                }
                is PsiField -> {
                    if (member is com.intellij.psi.PsiEnumConstant) return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Enum constants cannot be moved.")
                    if (!member.hasModifierProperty("public") || !member.hasModifierProperty("static") || !member.hasModifierProperty("final")) {
                        return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Only public static final fields can be moved in V1: '${member.name}'.")
                    }
                }
                else -> return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Unsupported member type: '${member.name}'.")
            }
            members.add(member)
        }
        val memberNames = members.map { it.name ?: "" }
        val memberSigs = members.map { m ->
            when (m) {
                is PsiMethod -> m.name + m.parameterList.parameters.joinToString(",", "(", ")") { it.type.canonicalText } + ":" + m.returnType?.canonicalText
                is PsiField -> m.name + ":" + m.type.canonicalText
                else -> m.name ?: ""
            }
        }
        val pm = SmartPointerManager.getInstance(project)
        val sourcePtr = pm.createSmartPsiElementPointer(sourceClass)
        val targetPtrs = targetClasses.map { pm.createSmartPsiElementPointer(it) }
        val memberPtrs = members.map { pm.createSmartPsiElementPointer(it) }
        val sourceVf = sourceClass.containingFile?.virtualFile ?: return failure(McpRefactoringErrorCode.FILE_NOT_FOUND, "Unable to resolve containing file.")
        val rel = projectRelativePath(project, sourceVf.path)
        return PushMembersDownSelectionResolution.Success(
            PushMembersDownPreparation(
                sourceSuperclassPointer = sourcePtr,
                targetSubclassPointers = targetPtrs,
                memberPointers = memberPtrs,
                sourceQualifiedNameSnapshot = sourceFqn,
                targetSubclassFqnsSnapshot = trimmedFqns,
                memberNameSnapshots = memberNames,
                memberSignatureSnapshots = memberSigs,
                pathInProject = rel,
                targetSubclassFqns = trimmedFqns,
            ),
        )
    }

    private fun <T : PsiNameIdentifierOwner> exactDeclaration(target: JavaSourceTarget, type: Class<T>): T? {
        val leaf = target.file.findElementAt(target.startOffset) ?: return null
        val decl = PsiTreeUtil.getParentOfType(leaf, type, false) ?: return null
        val nameRange = decl.nameIdentifier?.textRange ?: return null
        if (nameRange.startOffset != target.startOffset || nameRange.endOffset != target.endOffset) return null
        return decl
    }

    private fun projectRelativePath(project: Project, absolutePath: String): String {
        val base = project.basePath ?: return absolutePath
        return Path.of(base).toAbsolutePath().normalize().relativize(Path.of(absolutePath).toAbsolutePath().normalize()).toString()
    }

    private fun failure(code: McpRefactoringErrorCode, message: String): PushMembersDownSelectionResolution.Failure =
        PushMembersDownSelectionResolution.Failure(code, message)
}
