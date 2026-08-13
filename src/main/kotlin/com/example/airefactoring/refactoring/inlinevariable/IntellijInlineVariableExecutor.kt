package com.example.airefactoring.refactoring.inlinevariable

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandExecutor
import com.intellij.modcommand.ModDisplayMessage
import com.intellij.modcommand.ModHighlight
import com.intellij.modcommand.ModUpdateFileText
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAndWriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.refactoring.inline.InlineLocalHandler
import com.intellij.refactoring.util.InlineUtil

/** Executes IntelliJ's native Java Inline Variable refactoring with every UI choice fixed. */
class IntellijInlineVariableExecutor : InlineVariableExecutor {

    override suspend fun inline(
        project: Project,
        selection: InlineVariableSelection,
    ): InlineVariableExecutionResult = constrainedReadAndWriteAction(
        ReadConstraint.inSmartMode(project),
    ) {
        if (!selection.variable.isValid || selection.references.any { !it.isValid }) {
            throw InlineVariablePreparationException(
                "The local variable changed before Inline Variable could run.",
            )
        }
        val variable = selection.variable
        val initializer = variable.initializer
            ?: throw InlineVariablePreparationException(
                "The local variable no longer has an initializer.",
            )
        val changedDependencies = InlineUtil.getChangedBeforeLastAccessMap(initializer, variable)
        if (changedDependencies.isNotEmpty()) {
            throw InlineVariableConflictException(
                "Inlining '${variable.name}' would observe a changed initializer dependency.",
            )
        }

        val context = ActionContext(
            project,
            selection.file,
            selection.targetOffset,
            TextRange(selection.targetOffset, selection.targetOffset),
            variable,
        )
        val command = InlineLocalHandler.doInline(
            context,
            variable,
            null,
            InlineLocalHandler.InlineMode.INLINE_ALL_AND_DELETE,
        )
        val parts = command.unpack()
        val error = parts.filterIsInstance<ModDisplayMessage>()
            .firstOrNull { it.kind() == ModDisplayMessage.MessageKind.ERROR }
        if (error != null) {
            throw InlineVariablePreparationException(error.messageText())
        }
        if (
            parts.any {
                it !is ModUpdateFileText &&
                    it !is ModHighlight &&
                    !(it is ModDisplayMessage &&
                        it.kind() == ModDisplayMessage.MessageKind.INFORMATION)
            }
        ) {
            throw InlineVariablePreparationException(
                "Native Inline Variable requires an unsupported interactive command.",
            )
        }
        val updates = parts.filterIsInstance<ModUpdateFileText>()
        if (updates.isEmpty()) {
            throw InlineVariablePreparationException(
                "Native Inline Variable did not produce a source update.",
            )
        }
        val sourceCommand = updates.reduce(ModCommand::andThen)
        if (sourceCommand.modifiedFiles() != setOf(selection.file.virtualFile)) {
            throw InlineVariablePreparationException(
                "Native Inline Variable attempted to modify an unexpected file.",
            )
        }
        val preparation = Preparation(
            context = context,
            command = sourceCommand,
            variableName = variable.name,
            occurrenceCount = selection.references.size,
        )

        writeAction {
            if (!selection.variable.isValid) {
                throw InlineVariablePreparationException(
                    "The local variable changed before Inline Variable could run.",
                )
            }
            var batchResult: ModCommandExecutor.BatchExecutionResult? = null
            CommandProcessor.getInstance().executeCommand(
                project,
                Runnable {
                    CommandProcessor.getInstance().markCurrentCommandAsGlobal(project)
                    batchResult = ModCommandExecutor.getInstance()
                        .executeInBatch(preparation.context, preparation.command)
                },
                "MCP Inline Variable",
                null,
            )
            when (val result = batchResult) {
                ModCommandExecutor.Result.SUCCESS -> Unit
                ModCommandExecutor.Result.CONFLICTS -> throw InlineVariableConflictException(
                    result.message,
                )
                else -> throw InlineVariablePreparationException(
                    result?.message ?: "Native Inline Variable did not execute.",
                )
            }
            PsiDocumentManager.getInstance(project).commitDocument(selection.document)
            FileDocumentManager.getInstance().saveDocument(selection.document)
            InlineVariableExecutionResult(
                variableName = preparation.variableName,
                inlinedOccurrenceCount = preparation.occurrenceCount,
                summary = "Inlined ${preparation.occurrenceCount} occurrences of local variable " +
                    "'${preparation.variableName}' and removed its declaration.",
            )
        }
    }

    private data class Preparation(
        val context: ActionContext,
        val command: ModCommand,
        val variableName: String,
        val occurrenceCount: Int,
    )
}
