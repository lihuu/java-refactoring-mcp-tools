package com.example.airefactoring.action

import com.example.airefactoring.llm.LlmClient
import com.example.airefactoring.llm.LlmException
import com.example.airefactoring.llm.OpenAiCompatibleLlmClient
import com.example.airefactoring.notify.Notifier
import com.example.airefactoring.refactor.IntellijRenameExecutor
import com.example.airefactoring.refactor.RenameExecutor
import com.example.airefactoring.refactoring.PromptEnvelope
import com.example.airefactoring.refactoring.RefactorOperation
import com.example.airefactoring.refactoring.RefactorParseException
import com.example.airefactoring.refactoring.RefactoringRegistry
import com.example.airefactoring.refactoring.rename.RenameSymbolHandler
import com.example.airefactoring.refactoring.stringField
import com.example.airefactoring.settings.AiRefactoringSettings
import com.example.airefactoring.validator.ValidationResult
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Generic, refactoring-agnostic orchestrator. It resolves the symbol under the caret via the
 * [RefactoringRegistry], assembles a prompt from the matching handler's contribution, calls the
 * LLM, then decodes / validates / executes the result through that handler. Adding a refactoring
 * means registering a new [com.example.airefactoring.refactoring.RefactoringHandler] — this class
 * stays unchanged.
 */
class AiRenameSymbolAction(
    private val llmFactory: () -> LlmClient = ::OpenAiCompatibleLlmClient,
    private val executorFactory: () -> RenameExecutor = ::IntellijRenameExecutor,
    private val registry: RefactoringRegistry =
        RefactoringRegistry(listOf(RenameSymbolHandler(executorFactory))),
) : AnAction() {

    private val json = Json { ignoreUnknownKeys = true }

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
        // Temporary MVP-level precondition: only Java files are supported. Lifting this later
        // would mean a per-handler language capability rather than a shared guard here.
        if (file !is PsiJavaFile) {
            return Notifier.error(project, "AI Refactoring MVP only supports Java files.")
        }

        val (handler, target) = registry.resolve(file, editor, editor.caretModel.offset)
            ?: return Notifier.error(project, "No supported refactoring for the symbol under the caret.")

        val settings = AiRefactoringSettings.getInstance().state
        if (settings.apiKey.isBlank() || settings.model.isBlank() || settings.baseUrl.isBlank()) {
            return Notifier.error(
                project,
                "AI Refactoring is not configured. Set base URL, API key, and model in Settings."
            )
        }

        val (system, user) = PromptEnvelope.assemble(handler.promptContribution(target), target)

        val raw = try {
            llmFactory().complete(system, user, settings)
        } catch (e: LlmException.MissingConfiguration) {
            return Notifier.error(project, e.message ?: "AI Refactoring is not configured.")
        } catch (e: LlmException) {
            return Notifier.error(project, "AI call failed: ${e.message}")
        }

        val obj = parseObject(raw)
            ?: return Notifier.error(project, "AI response is invalid.")
        val action = obj.stringField("action")
            ?: return Notifier.error(project, "AI response is invalid.")

        val op: RefactorOperation = when (action) {
            "no_action" -> return Notifier.info(project, "No refactoring suggested.")
            handler.id -> try {
                handler.parse(obj)
            } catch (e: RefactorParseException) {
                return Notifier.error(project, e.userMessage)
            }
            else -> return Notifier.error(project, "AI response is invalid.")
        }

        when (val v = handler.validate(op, target, project)) {
            is ValidationResult.Invalid -> return Notifier.error(project, "Proposed refactoring is invalid: ${v.message}")
            ValidationResult.Ok -> {}
        }

        val summary = handler.execute(op, target, project, settings)
        Notifier.info(project, summary)
    }

    /** Parse [raw] (trimmed) into a [JsonObject], or null if it is malformed or not an object. */
    private fun parseObject(raw: String): JsonObject? =
        try {
            json.parseToJsonElement(raw.trim()) as? JsonObject
        } catch (_: Exception) {
            null
        }
}
