package com.example.airefactoring.action

import com.example.airefactoring.llm.LlmClient
import com.example.airefactoring.llm.LlmException
import com.example.airefactoring.notify.Notifier
import com.example.airefactoring.refactoring.PromptEnvelope
import com.example.airefactoring.refactoring.RefactorOperation
import com.example.airefactoring.refactoring.RefactorParseException
import com.example.airefactoring.refactoring.RefactorTarget
import com.example.airefactoring.refactoring.RefactoringHandler
import com.example.airefactoring.refactoring.stringField
import com.example.airefactoring.settings.AiRefactoringSettings
import com.example.airefactoring.validator.ValidationResult
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Shared pipeline for the per-refactoring AI actions. Each subclass binds exactly one
 * [RefactoringHandler]; this base resolves at the caret/selection, prompts the LLM, then
 * decodes / validates / executes through that handler. (The multi-handler dispatch via
 * RefactoringRegistry is reserved for a future auto-analysis entry point — not used here.)
 *
 * The real entry point [actionPerformed] runs the LLM network call on a background task so the
 * EDT (UI thread) never blocks; PSI reads happen in a read action, and the refactoring is applied
 * back on the EDT. The synchronous [run] is the test seam.
 */
abstract class AbstractAiRefactorAction(
    private val handler: RefactoringHandler,
    private val llmFactory: () -> LlmClient,
) : AnAction() {

    private val json = Json { ignoreUnknownKeys = true }

    /** Everything resolved on the EDT/read side that the background call needs. */
    private data class Prepared(
        val target: RefactorTarget,
        val system: String,
        val user: String,
        val settings: AiRefactoringSettings.State,
    )

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
        val caretOffset = editor.caretModel.offset // read on EDT

        // prepare needs PSI reads → read action; we're on the EDT here.
        val prepared = ReadAction.compute<Prepared?, RuntimeException> {
            prepare(project, editor, file, caretOffset)
        } ?: return // prepare already notified

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "AI analyzing code…", true) {
            private var raw: String? = null
            private var llmError: LlmException? = null

            override fun run(indicator: ProgressIndicator) {
                // OFF the EDT, NO read lock held — this is the only network part.
                try {
                    raw = callLlm(prepared)
                } catch (e: LlmException) {
                    llmError = e
                }
            }

            override fun onSuccess() { // EDT
                val err = llmError
                if (err != null) {
                    Notifier.error(project, llmMessage(err))
                    return
                }
                try {
                    apply(prepared, raw!!, project)
                } catch (t: Throwable) {
                    if (t is ProcessCanceledException) throw t
                    Notifier.error(project, "AI refactoring failed: ${t.message ?: t.javaClass.simpleName}")
                }
            }

            override fun onThrowable(error: Throwable) { // EDT — guard for callLlm/unexpected
                if (error is ProcessCanceledException) return // cancelled: silent
                Notifier.error(project, "AI refactoring failed: ${error.message ?: error.javaClass.simpleName}")
            }
        })
    }

    /** Test seam. Synchronous, but now guarded against stray exceptions like the async path. */
    fun run(project: Project, editor: Editor, file: PsiFile) {
        try {
            val prepared = prepare(project, editor, file, editor.caretModel.offset) ?: return
            val raw = try {
                callLlm(prepared)
            } catch (e: LlmException) {
                return Notifier.error(project, llmMessage(e))
            }
            apply(prepared, raw, project)
        } catch (t: Throwable) {
            if (t is ProcessCanceledException) throw t
            Notifier.error(project, "AI refactoring failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /**
     * Steps 1–4 of the pipeline: Java guard, resolve, configuration check, prompt assembly.
     * Returns null after notifying the user on any failure. PSI reads must happen under a read
     * lock; the caller provides that (ReadAction in the async path, the test thread in [run]).
     */
    private fun prepare(project: Project, editor: Editor, file: PsiFile, caretOffset: Int): Prepared? {
        if (file !is PsiJavaFile) {
            Notifier.error(project, "AI Refactoring MVP only supports Java files.")
            return null
        }

        val target = handler.resolve(file, editor, caretOffset)
        if (target == null) {
            Notifier.error(project, handler.notApplicableMessage)
            return null
        }

        val settings = AiRefactoringSettings.getInstance().state
        if (settings.apiKey.isBlank() || settings.model.isBlank() || settings.baseUrl.isBlank()) {
            Notifier.error(
                project,
                "AI Refactoring is not configured. Set base URL, API key, and model in Settings."
            )
            return null
        }

        val (system, user) = PromptEnvelope.assemble(handler.promptContribution(target), target)
        return Prepared(target, system, user, settings)
    }

    /** The only network part. Lets [LlmException] propagate; the caller maps it. */
    private fun callLlm(prepared: Prepared): String =
        llmFactory().complete(prepared.system, prepared.user, prepared.settings)

    /** Maps the two existing LLM-failure messages; shared by the sync and async paths. */
    private fun llmMessage(e: LlmException): String = when (e) {
        is LlmException.MissingConfiguration -> e.message ?: "AI Refactoring is not configured."
        else -> "AI call failed: ${e.message}"
    }

    /** Steps 6–10: parse, action dispatch, validate, execute, notify. Same messages/order as before. */
    private fun apply(prepared: Prepared, raw: String, project: Project) {
        val target = prepared.target
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

        val summary = handler.execute(op, target, project, prepared.settings)
        Notifier.info(project, summary)
    }

    private fun parseObject(raw: String): JsonObject? =
        try {
            json.parseToJsonElement(raw.trim()) as? JsonObject
        } catch (_: Exception) {
            null
        }
}
