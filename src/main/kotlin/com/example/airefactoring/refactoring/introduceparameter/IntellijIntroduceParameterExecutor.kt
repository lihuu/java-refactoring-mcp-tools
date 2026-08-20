package com.example.airefactoring.refactoring.introduceparameter

import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.refactoring.introduceParameter.IntroduceParameterProcessor
import com.intellij.refactoring.introduceVariable.IntroduceVariableBase
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersistence
import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersister

/**
 * Executes one fully prepared Introduce Parameter request through IntelliJ's native
 * [IntroduceParameterProcessor], headlessly and with every UI choice fixed by the source kind.
 *
 * The whole mutation is recorded as ONE global native command (the processor owns it), runs on the
 * EDT, and afterwards only the affected documents are saved. The resolver's immutable document
 * snapshots are re-validated immediately before mutation; if post-mutation inspection or save
 * fails, the executor rolls the mutation back through native
 * [com.intellij.openapi.command.undo.UndoManager] and verifies every document was restored exactly
 * before rethrowing.
 */
class IntellijIntroduceParameterExecutor internal constructor(
    private val resultInspector: IntroduceParameterResultInspector = DefaultIntroduceParameterResultInspector,
    private val documentPersistence: NativeRefactoringDocumentPersister =
        NativeRefactoringDocumentPersistence(),
) : IntroduceParameterExecutor {

    override suspend fun introduceParameter(
        project: Project,
        selection: IntroduceParameterSelection,
        parameterName: String,
    ): IntroduceParameterExecutionResult {
        return withContext(Dispatchers.EDT) {
            val preparedSource = requireCurrentSource(project, selection)
            val method = preparedSource.method

            // Compute the target parameter position from the pristine method before mutation.
            val parameterPosition = method.parameterList.parametersCount + 1

            val documents = requireCurrentDocuments(project, selection)

            val processor = try {
                buildProcessor(project, selection, preparedSource, parameterName)
            } catch (e: IntroduceParameterPreparationException) {
                throw e
            }
            processor.setPreviewUsages(false)

            try {
                processor.run()
                val result = resultInspector.inspect(
                    selection,
                    parameterName,
                    method.name,
                    selection.sourceTypeCanonicalText,
                    parameterPosition,
                    selection.updatedCallSiteCount,
                    documents.first { it.path == selection.sourceDocumentPath }.document,
                )
                documentPersistence.persist(
                    project,
                    affectedVirtualFiles(project, selection.affectedFiles, documents),
                )
                result
            } catch (e: Exception) {
                rollbackNativeMutation(project, documents, e)
                throw e
            }
        }
    }

    private data class PreparedSource(
        val method: com.intellij.psi.PsiMethod,
        val expression: PsiExpression?,
        val localVariable: PsiLocalVariable?,
        val sourceType: com.intellij.psi.PsiType,
    )

    /**
     * De-references the resolver's smart pointers only on the EDT and proves that the method,
     * selected source, and every document are exactly the version that was prepared under read.
     */
    private fun requireCurrentSource(
        project: Project,
        selection: IntroduceParameterSelection,
    ): PreparedSource {
        val method = selection.methodPointer.element
        if (method == null || !method.isValid || methodSignature(method) != selection.methodSignature) {
            throw IntroduceParameterPreparationException(
                "The introduce-parameter source changed before the native refactoring could run.",
            )
        }
        val source = when (selection.sourceKind) {
            IntroduceParameterSourceKind.EXPRESSION -> {
                val expression = selection.expressionPointer?.element
                if (expression == null || !expression.isValid) null
                else PreparedSource(method, expression, null, expression.type ?: return staleSelection())
            }

            IntroduceParameterSourceKind.LOCAL_VARIABLE -> {
                val local = selection.localVariablePointer?.element
                if (local == null || !local.isValid) null
                else PreparedSource(method, null, local, local.type)
            }
        } ?: return staleSelection()
        if (
            source.sourceType.canonicalText != selection.sourceTypeCanonicalText ||
            (source.expression?.text ?: source.localVariable?.text) != selection.sourceText ||
            source.method.containingFile.virtualFile.path != Path.of(project.basePath ?: "")
                .resolve(selection.sourceDocumentPath).normalize().toString()
        ) {
            return staleSelection()
        }
        return source
    }

    private fun staleSelection(): Nothing = throw IntroduceParameterPreparationException(
        "The introduce-parameter source changed before the native refactoring could run.",
    )

    private fun buildProcessor(
        project: Project,
        selection: IntroduceParameterSelection,
        preparedSource: PreparedSource,
        parameterName: String,
    ): IntroduceParameterProcessor {
        val method = preparedSource.method
        val initializer: PsiExpression
        val expressionToSearch: PsiExpression?
        val localVariable: PsiLocalVariable?
        val removeLocalVariable: Boolean
        val replaceChoice: IntroduceVariableBase.JavaReplaceChoice

        when (selection.sourceKind) {
            IntroduceParameterSourceKind.EXPRESSION -> {
                val expression = preparedSource.expression
                    ?: throw IntroduceParameterPreparationException(
                        "An expression source is missing its expression.",
                    )
                initializer = expression
                expressionToSearch = expression
                localVariable = null
                removeLocalVariable = false
                replaceChoice = IntroduceVariableBase.JavaReplaceChoice.NO
            }

            IntroduceParameterSourceKind.LOCAL_VARIABLE -> {
                val local = preparedSource.localVariable
                    ?: throw IntroduceParameterPreparationException(
                        "A local-variable source is missing its local variable.",
                    )
                val initializerExpression = local.initializer
                    ?: throw IntroduceParameterPreparationException(
                        "The selected local variable has no initializer to introduce.",
                    )
                initializer = initializerExpression
                expressionToSearch = null
                localVariable = local
                removeLocalVariable = true
                replaceChoice = IntroduceVariableBase.JavaReplaceChoice.ALL
            }
        }

        return HeadlessIntroduceParameterProcessor(
            project,
            method,
            method,
            initializer,
            expressionToSearch,
            localVariable,
            removeLocalVariable,
            parameterName,
            replaceChoice,
            0,
            true,
            false,
            false,
            preparedSource.sourceType,
            IntArrayList(),
        )
    }

    private fun affectedVirtualFiles(
        project: Project,
        affectedFiles: List<String>,
        documents: List<AffectedDocument>,
    ): Set<VirtualFile> {
        val byPath = documents.associateBy { it.path }
        val fileDocumentManager = FileDocumentManager.getInstance()
        return affectedFiles.mapNotNullTo(linkedSetOf()) { path ->
            byPath[path]?.let { fileDocumentManager.getFile(it.document) }
                ?: virtualFileFromPath(project, path)
        }
    }

    private fun virtualFileFromPath(
        project: Project,
        path: String,
    ): VirtualFile? {
        val basePath = project.basePath ?: return null
        return com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath(Path.of(basePath).resolve(path).normalize().toString())
    }

    private fun rollbackNativeMutation(
        project: Project,
        documents: List<AffectedDocument>,
        cause: Exception,
    ) {
        val unchanged = documents.all { it.document.text == it.originalText }
        if (unchanged) return

        val undoManager = com.intellij.openapi.command.undo.UndoManager.getInstance(project)
        if (!undoManager.isUndoAvailable(null)) {
            throw rollbackFailure("the native command is not available to Undo", cause)
        }
        undoManager.undo(null)
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val fileDocumentManager = FileDocumentManager.getInstance()
        documents.filter { !it.originallyUnsaved }.forEach { affected ->
            fileDocumentManager.saveDocument(affected.document)
        }
        val mismatched = documents.filter {
            it.document.text != it.originalText ||
                fileDocumentManager.isDocumentUnsaved(it.document) != it.originallyUnsaved
        }
        if (mismatched.isNotEmpty()) {
            throw rollbackFailure(
                "Undo did not restore every affected document exactly: " +
                    mismatched.joinToString(", ") { it.path },
                cause,
            )
        }
    }

    private fun rollbackFailure(reason: String, cause: Exception): IllegalStateException =
        IllegalStateException(
            "Native Introduce Parameter failed after mutation and rollback failed because $reason.",
            cause,
        )

    private data class AffectedDocument(
        val path: String,
        val document: Document,
        val originalText: String,
        val originallyUnsaved: Boolean,
    )

    private fun requireCurrentDocuments(
        project: Project,
        selection: IntroduceParameterSelection,
    ): List<AffectedDocument> {
        val basePath = project.basePath ?: return staleSelection()
        val fileDocumentManager = FileDocumentManager.getInstance()
        val localFileSystem = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
        if (selection.documentSnapshots.map { it.path } != selection.affectedFiles) {
            return staleSelection()
        }
        return selection.documentSnapshots.map { snapshot ->
            val relativePath = snapshot.path
            val absolutePath = Path.of(basePath).resolve(relativePath).normalize().toString()
            val virtualFile = localFileSystem.findFileByPath(absolutePath) ?: return staleSelection()
            val document = fileDocumentManager.getDocument(virtualFile) ?: return staleSelection()
            if (
                document.text != snapshot.text ||
                fileDocumentManager.isDocumentUnsaved(document) != snapshot.wasUnsaved
            ) {
                return staleSelection()
            }
            AffectedDocument(
                path = relativePath,
                document = document,
                originalText = snapshot.text,
                originallyUnsaved = snapshot.wasUnsaved,
            )
        }
    }

    private fun methodSignature(method: com.intellij.psi.PsiMethod): String = buildString {
        append(method.name)
        append('(')
        append(method.parameterList.parameters.joinToString(",") { it.type.canonicalText })
        append("):")
        append(method.returnType?.canonicalText.orEmpty())
    }
}

/** Post-mutation verifier seam; throwing here triggers the native Undo rollback. */
internal fun interface IntroduceParameterResultInspector {
    fun inspect(
        selection: IntroduceParameterSelection,
        parameterName: String,
        methodName: String,
        parameterType: String,
        parameterPosition: Int,
        updatedCallSiteCount: Int,
        sourceDocument: Document,
    ): IntroduceParameterExecutionResult
}

private object DefaultIntroduceParameterResultInspector : IntroduceParameterResultInspector {
    override fun inspect(
        selection: IntroduceParameterSelection,
        parameterName: String,
        methodName: String,
        parameterType: String,
        parameterPosition: Int,
        updatedCallSiteCount: Int,
        sourceDocument: Document,
    ): IntroduceParameterExecutionResult {
        // Post-mutation verification: the native processor must have introduced the parameter into
        // the declaration document. This reads the live document (already committed after run()).
        if (!sourceDocument.text.contains(parameterName)) {
            throw IntroduceParameterPreparationException(
                "Native Introduce Parameter did not add parameter '$parameterName' to '$methodName'.",
            )
        }
        return IntroduceParameterExecutionResult(
            methodName = methodName,
            parameterName = parameterName,
            parameterType = parameterType,
            parameterPosition = parameterPosition,
            sourceKind = selection.sourceKind,
            updatedCallSiteCount = updatedCallSiteCount,
            affectedFiles = selection.affectedFiles,
            summary = "Introduced parameter '$parameterName' to '$methodName' and updated " +
                "$updatedCallSiteCount call site(s).",
        )
    }
}

/**
 * The native processor with its undo confirmation policy pinned to "do not request" so the
 * executor can roll a failed mutation back through [com.intellij.openapi.command.undo.UndoManager]
 * without ever prompting the user. It still owns the whole mutation as one native command.
 */
private class HeadlessIntroduceParameterProcessor(
    project: Project,
    methodToReplaceIn: com.intellij.psi.PsiMethod,
    methodToSearchFor: com.intellij.psi.PsiMethod,
    parameterInitializer: PsiExpression?,
    expressionToSearch: PsiExpression?,
    localVariable: PsiLocalVariable?,
    removeLocalVariable: Boolean,
    parameterName: String,
    replaceChoice: IntroduceVariableBase.JavaReplaceChoice?,
    replaceFieldsWithGetters: Int,
    declareFinal: Boolean,
    generateDelegate: Boolean,
    replaceWithLambda: Boolean,
    forcedType: com.intellij.psi.PsiType,
    parametersToRemove: it.unimi.dsi.fastutil.ints.IntList,
) : IntroduceParameterProcessor(
    project,
    methodToReplaceIn,
    methodToSearchFor,
    parameterInitializer,
    expressionToSearch,
    localVariable,
    removeLocalVariable,
    parameterName,
    replaceChoice,
    replaceFieldsWithGetters,
    declareFinal,
    generateDelegate,
    replaceWithLambda,
    forcedType,
    parametersToRemove,
) {
    override fun getUndoConfirmationPolicy(): UndoConfirmationPolicy =
        UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION

    override fun isReplaceDuplicates(): Boolean = false

    /**
     * The native [IntroduceParameterProcessor.preprocessUsages] funnels every conflict it detects
     * (a same-name parameter/local, an initializer member inaccessible from a call site, an
     * unsupported call usage, ...) into this seam before it would show a conflicts dialog. A
     * headless MCP flow cannot answer that dialog, so a genuine native conflict is surfaced as
     * [IntroduceParameterConflictException] for the operation to map to `REFACTORING_CONFLICT`;
     * an empty conflict set proceeds exactly as the base implementation would.
     */
    override fun showConflicts(
        conflicts: MultiMap<PsiElement, String>,
        usages: Array<out UsageInfo>?,
    ): Boolean {
        if (!conflicts.isEmpty) {
            throw IntroduceParameterConflictException(
                conflicts.values().distinct().joinToString(separator = "; "),
            )
        }
        prepareSuccessful()
        return true
    }
}
