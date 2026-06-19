package com.example.airefactoring.action

import com.example.airefactoring.context.ContextCollector
import com.example.airefactoring.llm.LlmClient
import com.example.airefactoring.llm.LlmException
import com.example.airefactoring.llm.OpenAiCompatibleLlmClient
import com.example.airefactoring.notify.Notifier
import com.example.airefactoring.parser.CommandParser
import com.example.airefactoring.parser.InvalidCommandException
import com.example.airefactoring.parser.RefactorCommand
import com.example.airefactoring.prompt.PromptBuilder
import com.example.airefactoring.refactor.IntellijRenameExecutor
import com.example.airefactoring.refactor.RenameExecutor
import com.example.airefactoring.resolver.ResolvedSymbol
import com.example.airefactoring.resolver.SymbolResolver
import com.example.airefactoring.settings.AiRefactoringSettings
import com.example.airefactoring.validator.NameValidator
import com.example.airefactoring.validator.ValidationResult
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

class AiRenameSymbolAction(
    private val llmFactory: () -> LlmClient = ::OpenAiCompatibleLlmClient,
    private val executorFactory: () -> RenameExecutor = ::IntellijRenameExecutor,
) : AnAction() {

    private val resolver = SymbolResolver()
    private val collector = ContextCollector()
    private val parser = CommandParser()
    private val validator = NameValidator()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible = editor != null && file != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
        runForTest(project, editor, file)
    }

    /** Test seam used by AiRenameSymbolActionEndToEndTest. Synchronous. */
    fun runForTest(project: Project, editor: Editor, file: PsiFile) {
        val resolved = resolver.resolve(file, editor.caretModel.offset)
        when (resolved) {
            is ResolvedSymbol.NotJava -> return Notifier.error(project, "AI Refactoring MVP only supports Java files.")
            is ResolvedSymbol.NotFound -> return Notifier.error(project, "No symbol under caret.")
            is ResolvedSymbol.Unsupported -> return Notifier.error(project, resolved.reason)
            is ResolvedSymbol.Resolved -> Unit
        }
        resolved as ResolvedSymbol.Resolved

        val settings = AiRefactoringSettings.getInstance().state
        if (settings.apiKey.isBlank() || settings.model.isBlank() || settings.baseUrl.isBlank()) {
            return Notifier.error(
                project,
                "AI Refactoring is not configured. Set base URL, API key, and model in Settings."
            )
        }

        val ctx = collector.collect(file, resolved.element, resolved.kind)
        val (system, user) = PromptBuilder.build(ctx)

        val raw = try {
            llmFactory().complete(system, user, settings)
        } catch (e: LlmException.MissingConfiguration) {
            return Notifier.error(project, e.message ?: "AI Refactoring is not configured.")
        } catch (e: LlmException) {
            return Notifier.error(project, "AI call failed: ${e.message}")
        }

        val cmd = try {
            parser.parse(raw)
        } catch (e: InvalidCommandException) {
            return Notifier.error(project, e.userMessage)
        }

        when (cmd) {
            is RefactorCommand.NoAction -> Notifier.info(project, "No refactoring suggested.")
            is RefactorCommand.RenameSymbol -> {
                val current = resolved.element.name ?: ""
                when (val v = validator.validate(cmd.newName, resolved.kind, current, project)) {
                    is ValidationResult.Invalid -> Notifier.error(project, "Proposed name is invalid: ${v.message}")
                    ValidationResult.Ok -> {
                        executorFactory().rename(project, resolved.element, cmd.newName, settings.enablePreview)
                        Notifier.info(project, "Renamed '$current' to '${cmd.newName}'.")
                    }
                }
            }
        }
    }
}
