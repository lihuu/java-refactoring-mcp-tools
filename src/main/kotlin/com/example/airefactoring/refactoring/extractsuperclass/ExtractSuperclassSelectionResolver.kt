package com.example.airefactoring.refactoring.extractsuperclass

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
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Path

class ExtractSuperclassSelectionResolver(
    private val targetResolver: JavaSourceTargetResolver = JavaSourceTargetResolver(),
) {
    fun resolve(
        project: Project,
        pathInProject: String,
        sourceClassStartLine: Int,
        sourceClassStartColumn: Int,
        sourceClassEndLine: Int,
        sourceClassEndColumn: Int,
        memberStartLines: List<Int>,
        memberStartColumns: List<Int>,
        memberEndLines: List<Int>,
        memberEndColumns: List<Int>,
        superclassName: String,
        targetPackage: String?,
    ): ExtractSuperclassSelectionResolution {
        val n = memberStartLines.size
        if (n == 0) {
            return failure(McpRefactoringErrorCode.INVALID_RANGE, "At least one member must be selected.")
        }
        if (listOf(memberStartColumns.size, memberEndLines.size, memberEndColumns.size).any { it != n }) {
            return failure(McpRefactoringErrorCode.INVALID_RANGE, "Member range lists must have equal lengths.")
        }
        val trimmedName = superclassName.trim()
        if (trimmedName.isEmpty()) {
            return failure(McpRefactoringErrorCode.INVALID_FIELD_NAME, "Superclass name must not be empty.")
        }
        if (!PsiNameHelper.getInstance(project).isIdentifier(trimmedName)) {
            return failure(McpRefactoringErrorCode.INVALID_FIELD_NAME, "Superclass name '$trimmedName' is not a valid Java identifier.")
        }
        val normalizedPackage = targetPackage?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedPackage != null) {
            if (!PsiNameHelper.getInstance(project).isQualifiedName(normalizedPackage)) {
                return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Target package '$normalizedPackage' is not a valid qualified name.")
            }
        }

        val classRange = SourceRange(sourceClassStartLine, sourceClassStartColumn, sourceClassEndLine, sourceClassEndColumn)
        val classTarget = when (val r = targetResolver.resolve(project, pathInProject, classRange)) {
            is JavaSourceTargetResolution.Failure -> return failure(r.code, r.message)
            is JavaSourceTargetResolution.Success -> r.target
        }
        val sourceClass = exactDeclaration(classTarget, PsiClass::class.java)
            ?: return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Source class range must exactly match a class declaration name.")
        if (sourceClass.isInterface || sourceClass.isEnum || sourceClass.isAnnotationType) {
            return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Source class must be a concrete class, not an interface/enum/annotation.")
        }
        if (sourceClass is com.intellij.psi.PsiAnonymousClass) {
            return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Anonymous classes cannot be source for Extract Superclass.")
        }
        val qualifiedName = sourceClass.qualifiedName
            ?: return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Source class must have a qualified name.")
        val sourcePackage = (sourceClass.containingFile as? com.intellij.psi.PsiJavaFile)?.packageName ?: ""

        val members = mutableListOf<PsiMember>()
        val memberNames = mutableListOf<String>()
        val seenMembers = mutableSetOf<PsiMember>()
        for (i in 0 until n) {
            val range = SourceRange(memberStartLines[i], memberStartColumns[i], memberEndLines[i], memberEndColumns[i])
            val target = when (val r = targetResolver.resolve(project, pathInProject, range)) {
                is JavaSourceTargetResolution.Failure -> return failure(r.code, r.message)
                is JavaSourceTargetResolution.Success -> r.target
            }
            val member: PsiMember? = exactDeclaration(target, PsiMethod::class.java) as PsiMember?
                ?: exactDeclaration(target, PsiField::class.java) as PsiMember?
            if (member == null) {
                return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Each selected member must exactly match a method or field declaration name.")
            }
            if (!seenMembers.add(member)) {
                return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "A member cannot be selected more than once.")
            }
            val containing = member.containingClass
                ?: return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Member '${member.name}' has no containing class.")
            if (containing !== sourceClass) {
                if (containing.qualifiedName != sourceClass.qualifiedName) {
                    return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "All members must belong to the source class.")
                }
                val equivalent = com.intellij.psi.PsiManager.getInstance(project).areElementsEquivalent(containing, sourceClass)
                if (!equivalent) {
                    return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "All members must belong to the source class.")
                }
            }
            when (member) {
                is PsiMethod -> {
                    if (member.isConstructor) {
                        return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Constructors cannot be extracted to superclass.")
                    }
                    if (!member.hasModifierProperty("public")) {
                        return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Only public methods can be extracted in V1: '${member.name}'.")
                    }
                    if (member.hasModifierProperty("static")) {
                        return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Static methods cannot be extracted in V1: '${member.name}'.")
                    }
                    if (member.hasModifierProperty("private")) {
                        return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Private methods cannot be extracted in V1: '${member.name}'.")
                    }
                }
                is PsiField -> {
                    if (member is com.intellij.psi.PsiEnumConstant) {
                        return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Enum constants cannot be extracted: '${member.name}'.")
                    }
                    if (!member.hasModifierProperty("public") || !member.hasModifierProperty("static") || !member.hasModifierProperty("final")) {
                        return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Only public static final fields can be extracted in V1: '${member.name}'.")
                    }
                }
                else -> return failure(McpRefactoringErrorCode.UNSUPPORTED_TARGET, "Unsupported member type: '${member.name}'.")
            }
            members.add(member)
            memberNames.add(member.name ?: "")
        }

        val memberSignatures = members.map { member ->
            when (member) {
                is PsiMethod -> member.name + member.parameterList.parameters.joinToString(",", "(", ")") { it.type.canonicalText } + ":" + member.returnType?.canonicalText
                is PsiField -> member.name + ":" + member.type.canonicalText
                else -> member.name ?: ""
            }
        }

        val effectiveQualified = if (normalizedPackage != null) "$normalizedPackage.$trimmedName" else {
            if (sourcePackage.isEmpty()) trimmedName else "$sourcePackage.$trimmedName"
        }

        val pointerManager = SmartPointerManager.getInstance(project)
        val sourcePointer = pointerManager.createSmartPsiElementPointer(sourceClass)
        val memberPointers = members.map { pointerManager.createSmartPsiElementPointer(it) }

        val sourceFileVf = sourceClass.containingFile?.virtualFile
            ?: return failure(McpRefactoringErrorCode.FILE_NOT_FOUND, "Unable to resolve containing file.")
        val relativePath = projectRelativePath(project, sourceFileVf.path)

        return ExtractSuperclassSelectionResolution.Success(
            ExtractSuperclassPreparation(
                sourceClassPointer = sourcePointer,
                memberPointers = memberPointers,
                sourceClassQualifiedNameSnapshot = qualifiedName,
                sourceClassPackageSnapshot = sourcePackage,
                memberNameSnapshots = memberNames,
                memberSignatureSnapshots = memberSignatures,
                pathInProject = relativePath,
                superclassName = trimmedName,
                targetPackage = normalizedPackage,
                effectiveQualifiedNameSnapshot = effectiveQualified,
            ),
        )
    }

    private fun <T : PsiNameIdentifierOwner> exactDeclaration(
        target: JavaSourceTarget,
        type: Class<T>,
    ): T? {
        val leaf = target.file.findElementAt(target.startOffset) ?: return null
        val declaration = PsiTreeUtil.getParentOfType(leaf, type, false) ?: return null
        val nameRange = declaration.nameIdentifier?.textRange ?: return null
        if (nameRange.startOffset != target.startOffset || nameRange.endOffset != target.endOffset) return null
        return declaration
    }

    private fun projectRelativePath(project: Project, absolutePath: String): String {
        val base = project.basePath ?: return absolutePath
        return Path.of(base).toAbsolutePath().normalize()
            .relativize(Path.of(absolutePath).toAbsolutePath().normalize())
            .toString()
    }

    private fun failure(code: McpRefactoringErrorCode, message: String): ExtractSuperclassSelectionResolution.Failure =
        ExtractSuperclassSelectionResolution.Failure(code, message)
}
