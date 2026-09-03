package com.example.airefactoring.refactoring.inlinemethod

import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersistence
import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersister
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.inline.InlineMethodProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** Executes only IntelliJ's native Java Inline Method processor. */
class IntellijInlineMethodExecutor internal constructor(
    private val documentPersistence: NativeRefactoringDocumentPersister = NativeRefactoringDocumentPersistence(),
) : InlineMethodExecutor {
    override suspend fun inline(
        project: Project,
        preparation: InlineMethodPreparation,
    ): InlineMethodExecutionResult {
        // Freshness validation reads references and PSI on a background read action: on the EDT
        // the ReferencesSearch would raise slow-operation assertions, and the
        // SlowOperations.allowSlowOperations escape hatch it previously relied on is deprecated
        // and scheduled for removal.
        val method = withContext(Dispatchers.Default) {
            readAction { validateFreshPreparation(project, preparation) }
        }
        return withContext(Dispatchers.EDT) {
            val usage = preparation.usagePointers.firstOrNull()?.element
                ?.takeIf { it.isValid }
                ?: throw stale()
            try {
                InlineMethodProcessor(project, method, usage, null, false, false, false)
                    .apply { setPreviewUsages(false) }
                    .run()
            } catch (exception: BaseRefactoringProcessor.ConflictsInTestsException) {
                throw InlineMethodConflictException(exception.messages.distinct().joinToString("; "))
            }
            documentPersistence.persist(project, preparation.affectedVirtualFiles)
            InlineMethodExecutionResult(
                methodName = preparation.methodName,
                inlinedOccurrenceCount = preparation.usagePointers.size,
                affectedFiles = projectRelativePaths(project, preparation),
                summary = "Inlined ${preparation.usagePointers.size} Java calls to '${preparation.methodName}' and removed its declaration.",
            )
        }
    }

    internal fun validateFreshPreparation(project: Project, preparation: InlineMethodPreparation): PsiMethod {
        val method = preparation.methodPointer.element?.takeIf { it.isValid } ?: throw stale()
        if (
            method.text != preparation.methodTextSnapshot ||
            method.name != preparation.methodName ||
            method.containingClass?.qualifiedName != preparation.ownerQualifiedNameSnapshot ||
            method.containingFile?.virtualFile != preparation.sourceVirtualFile ||
            !preparation.sourceVirtualFile.isValid || !preparation.sourceVirtualFile.isWritable
        ) throw stale()

        val current = ReferencesSearch.search(method, GlobalSearchScope.projectScope(project)).findAll()
                .map { reference ->
                    val expression = reference.element as? PsiReferenceExpression ?: throw stale()
                    if (expression is PsiMethodReferenceExpression || expression.resolve() !== method) throw stale()
                    val call = expression.parent as? PsiMethodCallExpression
                    if (call?.methodExpression !== expression) throw stale()
                    val file = expression.containingFile?.virtualFile
                        ?.takeIf { it.isValid && it.isWritable } ?: throw stale()
                    InlineMethodUsageSnapshot(file.path, expression.textRange.startOffset)
                }
                .sortedWith(compareBy({ it.filePath }, { it.startOffset }))
        if (current != preparation.usageSnapshots) throw stale()
        val pointerSnapshots = preparation.usagePointers.map { pointer ->
            val expression = pointer.element?.takeIf { it.isValid } ?: throw stale()
            val file = expression.containingFile?.virtualFile?.takeIf { it.isValid } ?: throw stale()
            InlineMethodUsageSnapshot(file.path, expression.textRange.startOffset)
        }.sortedWith(compareBy({ it.filePath }, { it.startOffset }))
        if (pointerSnapshots != preparation.usageSnapshots) throw stale()
        return method
    }

    private fun projectRelativePaths(project: Project, preparation: InlineMethodPreparation): List<String> {
        val base = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() } ?: throw stale()
        return preparation.affectedVirtualFiles.map { file ->
            val absolute = Path.of(file.path).toAbsolutePath().normalize()
            if (!absolute.startsWith(base)) throw stale()
            base.relativize(absolute).toString()
        }.sorted()
    }

    private fun stale() = InlineMethodPreparationException(
        "The Inline Method target or its Java usages changed before native execution.",
    )
}
