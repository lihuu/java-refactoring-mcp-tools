package com.example.airefactoring.refactoring.introduceparameter

import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.introduceParameter.IntroduceParameterProcessor
import com.intellij.refactoring.introduceVariable.IntroduceVariableBase
import it.unimi.dsi.fastutil.ints.IntArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Executes one fully prepared Introduce Parameter request through IntelliJ's native
 * [IntroduceParameterProcessor], headlessly and with every UI choice fixed by the source kind.
 *
 * The whole mutation is recorded as ONE global native command (the processor owns it), runs on the
 * EDT, and afterwards only the affected documents are saved. Every affected document is snapshotted
 * at mutation time; if post-mutation inspection or save fails, the executor rolls the mutation back
 * through native [com.intellij.openapi.command.undo.UndoManager] and verifies every document was
 * restored exactly before rethrowing.
 */
class IntellijIntroduceParameterExecutor internal constructor(
    private val resultInspector: IntroduceParameterResultInspector = DefaultIntroduceParameterResultInspector,
) : IntroduceParameterExecutor {

    override suspend fun introduceParameter(
        project: Project,
        selection: IntroduceParameterSelection,
        parameterName: String,
    ): IntroduceParameterExecutionResult = withContext(Dispatchers.EDT) {
        requireValidSource(selection)
        val method = selection.method

        // Compute the caller count and target parameter position from the pristine method BEFORE
        // mutation, so PSI staleness after the native rewrite cannot skew the reported result.
        val updatedCallSiteCount = countExternalCallSites(selection)
        val parameterPosition = method.parameterList.parametersCount + 1

        // Snapshot every affected document AT MUTATION TIME, on the EDT, held as smart pointers so
        // a later rollback can compare exact text.
        val documents = snapshotAffectedDocuments(project, selection.affectedFiles)

        val processor = try {
            buildProcessor(project, selection, parameterName)
        } catch (e: IntroduceParameterPreparationException) {
            throw e
        }
        processor.setPreviewUsages(false)

        try {
            processor.run()
            PsiDocumentManager.getInstance(project).commitAllDocuments()

            val result = resultInspector.inspect(
                selection,
                parameterName,
                method.name,
                selection.sourceType.canonicalText,
                parameterPosition,
                updatedCallSiteCount,
            )
            saveAffectedDocuments(project, selection.affectedFiles, documents)
            result
        } catch (e: Exception) {
            rollbackNativeMutation(project, documents, e)
            throw e
        }
    }

    private fun requireValidSource(selection: IntroduceParameterSelection) {
        val expression = selection.expression
        val localVariable = selection.localVariable
        val expressionValid = expression == null || expression.isValid
        val localValid = localVariable == null || localVariable.isValid
        if (!selection.method.isValid || !expressionValid || !localValid) {
            throw IntroduceParameterPreparationException(
                "The introduce-parameter source changed before the native refactoring could run.",
            )
        }
    }

    private fun buildProcessor(
        project: Project,
        selection: IntroduceParameterSelection,
        parameterName: String,
    ): IntroduceParameterProcessor {
        val method = selection.method
        val initializer: PsiExpression
        val expressionToSearch: PsiExpression?
        val localVariable: PsiLocalVariable?
        val removeLocalVariable: Boolean
        val replaceChoice: IntroduceVariableBase.JavaReplaceChoice

        when (selection.sourceKind) {
            IntroduceParameterSourceKind.EXPRESSION -> {
                val expression = selection.expression
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
                val local = selection.localVariable
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
            selection.sourceType,
            IntArrayList(),
        )
    }

    private fun saveAffectedDocuments(
        project: Project,
        affectedFiles: List<String>,
        documents: List<AffectedDocument>,
    ) {
        val byPath = documents.associateBy { it.path }
        val fileDocumentManager = FileDocumentManager.getInstance()
        affectedFiles.forEach { path ->
            byPath[path]?.let { affected ->
                fileDocumentManager.saveDocument(affected.document)
            } ?: syncDocumentFromPath(project, fileDocumentManager, path)
        }
    }

    private fun syncDocumentFromPath(
        project: Project,
        fileDocumentManager: FileDocumentManager,
        path: String,
    ) {
        val basePath = project.basePath ?: return
        val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath(Path.of(basePath).resolve(path).normalize().toString())
            ?: return
        fileDocumentManager.getDocument(virtualFile)?.let(fileDocumentManager::saveDocument)
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

        val mismatched = documents.filter { it.document.text != it.originalText }
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

    /** Counts the direct call sites of [selection.method] across the affected files. */
    private fun countExternalCallSites(selection: IntroduceParameterSelection): Int {
        val method = selection.method
        val psiManager = com.intellij.psi.PsiManager.getInstance(method.project)
        val fileSystem = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
        val basePath = method.project.basePath ?: return selection.affectedFiles.size
        var count = 0
        selection.affectedFiles.forEach { path ->
            val virtualFile = fileSystem.findFileByPath(Path.of(basePath).resolve(path).normalize().toString())
                ?: return@forEach
            val file = psiManager.findFile(virtualFile) as? com.intellij.psi.PsiJavaFile
                ?: return@forEach
            PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression::class.java).forEach { call ->
                val reference = call.methodExpression as? PsiReferenceExpression
                if (reference != null && psiManager.areElementsEquivalent(reference.resolve(), method)) {
                    count++
                }
            }
        }
        return count
    }

    private data class AffectedDocument(
        val path: String,
        val document: Document,
        val originalText: String,
        val originallyUnsaved: Boolean,
    )

    private fun snapshotAffectedDocuments(
        project: Project,
        affectedFiles: List<String>,
    ): List<AffectedDocument> {
        val basePath = project.basePath ?: return emptyList()
        val fileDocumentManager = FileDocumentManager.getInstance()
        val localFileSystem = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
        return affectedFiles.mapNotNull { relativePath ->
            val absolutePath = Path.of(basePath).resolve(relativePath).normalize().toString()
            val virtualFile = localFileSystem.findFileByPath(absolutePath) ?: return@mapNotNull null
            val document = fileDocumentManager.getDocument(virtualFile) ?: return@mapNotNull null
            AffectedDocument(
                path = relativePath,
                document = document,
                originalText = document.text,
                originallyUnsaved = fileDocumentManager.isDocumentUnsaved(document),
            )
        }
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
    ): IntroduceParameterExecutionResult {
        // Post-mutation verification: the native processor must have introduced the parameter into
        // the declaration document. This reads the live document (already committed after run()).
        if (!selection.document.text.contains(parameterName)) {
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
}
