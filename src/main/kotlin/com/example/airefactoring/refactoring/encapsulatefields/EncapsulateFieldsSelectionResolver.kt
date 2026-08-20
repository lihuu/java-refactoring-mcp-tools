package com.example.airefactoring.refactoring.encapsulatefields

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.JavaSourceTarget
import com.example.airefactoring.refactoring.JavaSourceTargetResolution
import com.example.airefactoring.refactoring.JavaSourceTargetResolver
import com.example.airefactoring.refactoring.SourceRange
import com.example.airefactoring.validator.NameValidator
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiField
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.encapsulateFields.JavaEncapsulateFieldHelper
import java.nio.file.Path

class EncapsulateFieldsSelectionResolver(
    private val targetResolver: JavaSourceTargetResolver = JavaSourceTargetResolver(),
    private val nameValidator: NameValidator = NameValidator(),
) {

    fun resolve(
        project: Project,
        pathInProject: String,
        fieldStartLines: List<Int>,
        fieldStartColumns: List<Int>,
        fieldEndLines: List<Int>,
        fieldEndColumns: List<Int>,
        getterNames: List<String>,
        setterNames: List<String>,
        fieldsVisibility: String?,
        accessorsVisibility: String,
        encapsulateGet: Boolean,
        encapsulateSet: Boolean,
        useAccessorsWhenAccessible: Boolean,
    ): EncapsulateFieldsSelectionResolution {
        val n = fieldStartLines.size
        if (n == 0) {
            return failure(
                McpRefactoringErrorCode.INVALID_RANGE,
                "At least one field must be selected.",
            )
        }
        if (listOf(fieldStartColumns.size, fieldEndLines.size, fieldEndColumns.size, getterNames.size, setterNames.size).any { it != n }) {
            return failure(
                McpRefactoringErrorCode.INVALID_RANGE,
                "Field lists must have equal lengths.",
            )
        }
        // Visibility validation
        val allowedFieldVis = setOf(null, "private", "protected", "packageLocal", "asIs")
        if (fieldsVisibility !in allowedFieldVis) {
            return failure(
                McpRefactoringErrorCode.INVALID_VISIBILITY,
                "fieldsVisibility must be null/asIs, 'private', 'protected', or 'packageLocal'.",
            )
        }
        val normalizedFieldsVisibility = if (fieldsVisibility == "asIs") null else fieldsVisibility
        val allowedAccessorVis = setOf("public", "protected", "packageLocal", "private")
        if (accessorsVisibility !in allowedAccessorVis) {
            return failure(
                McpRefactoringErrorCode.INVALID_VISIBILITY,
                "accessorsVisibility must be 'public', 'protected', 'packageLocal', or 'private'.",
            )
        }
        // Name validation and duplicate checks
        val allAccessorNames = mutableListOf<String>()
        for (i in 0 until n) {
            val getter = getterNames[i]
            val setter = setterNames[i]
            if (!isValidIdentifier(getter, project)) {
                return failure(
                    McpRefactoringErrorCode.INVALID_FIELD_NAME,
                    "Getter name '$getter' is not a valid Java identifier.",
                )
            }
            if (!isValidIdentifier(setter, project)) {
                return failure(
                    McpRefactoringErrorCode.INVALID_FIELD_NAME,
                    "Setter name '$setter' is not a valid Java identifier.",
                )
            }
            allAccessorNames.add(getter)
            allAccessorNames.add(setter)
        }
        if (allAccessorNames.size != allAccessorNames.toSet().size) {
            return failure(
                McpRefactoringErrorCode.INVALID_FIELD_NAME,
                "Getter/setter names must be unique within the request.",
            )
        }

        // Resolve each field
        val fields = mutableListOf<PsiField>()
        val fieldNames = mutableListOf<String>()
        val containingClasses = mutableListOf<com.intellij.psi.PsiClass>()
        val seenFields = mutableSetOf<PsiField>()
        // We need to resolve the file once to get containing class consistency check
        // Resolve each field individually via targetResolver
        for (i in 0 until n) {
            val range = SourceRange(
                startLine = fieldStartLines[i],
                startColumn = fieldStartColumns[i],
                endLine = fieldEndLines[i],
                endColumn = fieldEndColumns[i],
            )
            val target = when (val resolution = targetResolver.resolve(project, pathInProject, range)) {
                is JavaSourceTargetResolution.Failure -> return failure(resolution.code, resolution.message)
                is JavaSourceTargetResolution.Success -> resolution.target
            }
            val field = exactDeclaration(target, PsiField::class.java)
                ?: return failure(
                    McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                    "Each selected field must exactly match a field declaration name.",
                )
            if (!seenFields.add(field)) {
                return failure(
                    McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                    "A field cannot be selected more than once.",
                )
            }
            val containingClass = field.containingClass ?: return failure(
                McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                "Field '${field.name}' has no containing class.",
            )
            fields.add(field)
            fieldNames.add(field.name)
            containingClasses.add(containingClass)
        }

        // All fields must share same containing class (reference equality)
        val firstClass = containingClasses.first()
        for (cls in containingClasses) {
            if (cls !== firstClass) {
                return failure(
                    McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                    "All fields must belong to the same containing class.",
                )
            }
        }

        // Applicability check via helper: must be in getApplicableFields
        val helper = JavaEncapsulateFieldHelper()
        val applicable = helper.getApplicableFields(firstClass).toSet()
        for (field in fields) {
            if (field !in applicable) {
                return failure(
                    McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                    "Field '${field.name}' is not applicable for encapsulation.",
                )
            }
        }

        val pointerManager = SmartPointerManager.getInstance(project)
        val fieldPointers = fields.map { pointerManager.createSmartPsiElementPointer(it) }
        val fieldTextSnapshots = fields.map { it.text }
        val fieldTypeSnapshots = fields.map { it.type.canonicalText }
        val containingClassPointer = pointerManager.createSmartPsiElementPointer(firstClass)
        val qualifiedName = firstClass.qualifiedName ?: return failure(
            McpRefactoringErrorCode.UNSUPPORTED_TARGET,
            "Containing class must have a qualified name.",
        )

        // Resolve project-relative path from first field's file
        val firstFieldFile = fields.first().containingFile?.virtualFile
            ?: return failure(McpRefactoringErrorCode.FILE_NOT_FOUND, "Unable to resolve containing file.")
        val relativePath = projectRelativePath(project, firstFieldFile.path)

        return EncapsulateFieldsSelectionResolution.Success(
            EncapsulateFieldsPreparation(
                fieldPointers = fieldPointers,
                containingClassPointer = containingClassPointer,
                fieldTextSnapshots = fieldTextSnapshots,
                fieldTypeSnapshots = fieldTypeSnapshots,
                containingClassQualifiedNameSnapshot = qualifiedName,
                containingClassTextSnapshot = firstClass.text,
                pathInProject = relativePath,
                fieldNames = fieldNames,
                getterNames = getterNames,
                setterNames = setterNames,
                fieldsVisibility = normalizedFieldsVisibility,
                accessorsVisibility = accessorsVisibility,
                encapsulateGet = encapsulateGet,
                encapsulateSet = encapsulateSet,
                useAccessorsWhenAccessible = useAccessorsWhenAccessible,
            ),
        )
    }

    private fun isValidIdentifier(name: String, project: Project): Boolean =
        nameValidator.validateVariableName(name, project) is com.example.airefactoring.validator.ValidationResult.Ok

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

    private fun failure(code: McpRefactoringErrorCode, message: String): EncapsulateFieldsSelectionResolution.Failure =
        EncapsulateFieldsSelectionResolution.Failure(code, message)
}
