package com.example.airefactoring.refactoring.locator

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.mcp.McpRefactoringResult
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path

@Serializable
private data class SymbolCandidate(
    val kind: String,
    val name: String,
    val containingClassQualifiedName: String?,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    @Transient private val rawStartOffset: Int = 0,
)

@Serializable
private data class LocatorSuccess(
    val ok: Boolean,
    val operation: String,
    val filePath: String,
    val symbolName: String,
    val candidateCount: Int,
    val candidates: List<SymbolCandidate>,
)

/**
 * Read-only locator that returns exact declaration-name ranges for every declaration of one
 * Java identifier in one file, so agents can obtain fresh coordinates before calling any
 * range-based refactoring tool.
 */
class LocateSymbolOperation {

    fun execute(
        project: Project,
        pathInProject: String,
        symbolName: String,
        kindFilter: String?,
    ): String {
        if (symbolName.isEmpty() || !symbolName[0].isJavaIdentifierStart()) {
            return McpRefactoringResult.failure(
                McpRefactoringErrorCode.INVALID_RANGE,
                "'$symbolName' is not a valid Java identifier.",
            ).toJson()
        }
        if (kindFilter != null && kindFilter !in SUPPORTED_KINDS) {
            return McpRefactoringResult.failure(
                McpRefactoringErrorCode.UNSUPPORTED_TARGET,
                "Unsupported kindFilter '$kindFilter'; expected one of ${SUPPORTED_KINDS.joinToString(", ")}.",
            ).toJson()
        }
        return ReadAction.compute<String, RuntimeException> {
            val virtualFile = LocalFileSystem.getInstance()
                .findFileByNioFile(Path.of(project.basePath!!, pathInProject))
                ?: return@compute McpRefactoringResult.failure(
                    McpRefactoringErrorCode.FILE_NOT_FOUND,
                    "File not found: $pathInProject",
                ).toJson()
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile
                ?: return@compute McpRefactoringResult.failure(
                    McpRefactoringErrorCode.NOT_JAVA_FILE,
                    "Not a Java file: $pathInProject",
                ).toJson()
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                ?: return@compute McpRefactoringResult.failure(
                    McpRefactoringErrorCode.READ_ONLY,
                    "No document available for $pathInProject",
                ).toJson()

            val located = mutableListOf<Triple<Int, String, PsiElement>>()
            psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    super.visitElement(element)
                    val kinded = when (element) {
                        is PsiClass -> kindAndIdentifier(element, KIND_CLASS, symbolName)
                        is PsiMethod -> kindAndIdentifier(element, KIND_METHOD, symbolName)
                        is PsiField -> kindAndIdentifier(element, KIND_FIELD, symbolName)
                        is PsiParameter -> kindAndIdentifier(element, KIND_PARAMETER, symbolName)
                        is PsiLocalVariable -> kindAndIdentifier(element, KIND_LOCAL, symbolName)
                        else -> null
                    } ?: return
                    val (kind, identifier) = kinded
                    if (kindFilter != null && kind != kindFilter) return
                    located += Triple(identifier.textRange.startOffset, kind, identifier)
                }
            })
            located.sortBy { it.first }

            val candidates = located.map { (offset, kind, identifier) ->
                val range = identifier.textRange
                val startLineIdx = document.getLineNumber(range.startOffset)
                val endLineIdx = document.getLineNumber(range.endOffset - 1)
                val containing = PsiTreeUtil.getParentOfType(identifier, PsiClass::class.java)
                SymbolCandidate(
                    kind = kind,
                    name = symbolName,
                    containingClassQualifiedName = containing?.qualifiedName,
                    startLine = startLineIdx + 1,
                    startColumn = range.startOffset - document.getLineStartOffset(startLineIdx) + 1,
                    endLine = endLineIdx + 1,
                    endColumn = range.endOffset - document.getLineStartOffset(endLineIdx) + 1,
                    rawStartOffset = offset,
                )
            }
            Json.encodeToString(
                LocatorSuccess(
                    ok = true,
                    operation = OPERATION_NAME,
                    filePath = pathInProject,
                    symbolName = symbolName,
                    candidateCount = candidates.size,
                    candidates = candidates,
                ),
            )
        }
    }

    private fun kindAndIdentifier(
        named: PsiNameIdentifierOwner,
        kind: String,
        symbolName: String,
    ): Pair<String, PsiElement>? {
        if (named.name != symbolName) return null
        return named.nameIdentifier?.let { kind to it }
    }

    private companion object {
        const val OPERATION_NAME = "java_locate_symbol"
        const val KIND_CLASS = "class"
        const val KIND_METHOD = "method"
        const val KIND_FIELD = "field"
        const val KIND_PARAMETER = "parameter"
        const val KIND_LOCAL = "local"
        val SUPPORTED_KINDS = listOf(KIND_CLASS, KIND_METHOD, KIND_FIELD, KIND_PARAMETER, KIND_LOCAL)
    }
}
