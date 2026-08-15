package com.example.airefactoring.refactoring.introducemember

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAndWriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiType
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.refactoring.introduceField.BaseExpressionToFieldHandler
import com.intellij.refactoring.introduceField.IntroduceConstantHandler
import com.intellij.refactoring.introduceField.IntroduceFieldHandler
import com.intellij.refactoring.util.CommonRefactoringUtil

/**
 * Executes IntelliJ's native Introduce Constant / Introduce Field refactorings with every UI
 * choice fixed by the profile. It drives the native handlers through a non-visible temporary
 * editor, records the whole extraction as one global command, and saves only the target document.
 */
class IntellijIntroduceMemberExecutor internal constructor(
    private val resultInspector: IntroduceMemberResultInspector = DefaultIntroduceMemberResultInspector,
) : IntroduceMemberExecutor {

    override suspend fun introduce(
        project: Project,
        selection: IntroduceMemberSelection,
        preferredName: String,
        profile: IntroduceMemberProfile,
    ): IntroduceMemberExecutionResult = constrainedReadAndWriteAction(
        ReadConstraint.inSmartMode(project),
    ) {
        if (!selection.expression.isValid) {
            throw IntroduceMemberPreparationException(
                "The selected expression changed before Introduce Member could run.",
            )
        }
        val actualName = JavaCodeStyleManager.getInstance(project)
            .suggestUniqueVariableName(preferredName, selection.targetClass, true)
        val settings = BaseExpressionToFieldHandler.Settings(
            actualName,
            selection.expression,
            arrayOf(selection.expression),
            false,
            profile is IntroduceMemberProfile.Constant,
            true,
            BaseExpressionToFieldHandler.InitializationPlace.IN_FIELD_DECLARATION,
            PsiModifier.PRIVATE,
            null,
            selection.memberType,
            false,
            BaseExpressionToFieldHandler.TargetDestination(selection.targetClass),
            false,
            false,
        )

        writeAction {
            ApplicationManager.getApplication().assertIsDispatchThread()
            if (!selection.expression.isValid) {
                throw IntroduceMemberPreparationException(
                    "The selected expression changed before Introduce Member could run.",
                )
            }

            val editorFactory = EditorFactory.getInstance()
            val editor = editorFactory.createEditor(
                selection.document, project, selection.file.virtualFile, false,
            )
            val originalSource = selection.document.text
            val originallyUnsaved = FileDocumentManager.getInstance()
                .isDocumentUnsaved(selection.document)
            try {
                try {
                    editor.caretModel.moveToOffset(0)
                    CommandProcessor.getInstance().executeCommand(
                        project,
                        Runnable {
                            CommandProcessor.getInstance().markCurrentCommandAsGlobal(project)
                            runNativeHandler(
                                project,
                                editor,
                                selection.expression,
                                settings,
                                selection.targetClass,
                                profile,
                            )
                        },
                        profile.commandName,
                        null,
                        UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION,
                    )

                    PsiDocumentManager.getInstance(project).commitDocument(selection.document)
                    val result = resultInspector.inspect(
                        selection.targetClass,
                        preferredName,
                        actualName,
                        profile,
                    )
                    FileDocumentManager.getInstance().saveDocument(selection.document)
                    result
                } catch (e: Exception) {
                    rollbackNativeMutation(
                        project,
                        selection.document,
                        originalSource,
                        originallyUnsaved,
                        e,
                    )
                    throw e
                }
            } finally {
                editorFactory.releaseEditor(editor)
            }
        }
    }

    private fun runNativeHandler(
        project: Project,
        editor: Editor,
        expression: PsiExpression,
        settings: BaseExpressionToFieldHandler.Settings,
        targetClass: PsiClass,
        profile: IntroduceMemberProfile,
    ) {
        val executionParentClass = generateSequence(targetClass) { it.containingClass }.last()
        when (profile) {
            IntroduceMemberProfile.Constant -> try {
                FixedIntroduceConstantHandler(settings, executionParentClass)
                    .invoke(project, editor, expression)
            } catch (e: CommonRefactoringUtil.RefactoringErrorHintException) {
                throw preparationFrom(e, profile)
            }

            IntroduceMemberProfile.InstanceFinalField -> {
                val accepted = try {
                    FixedIntroduceFieldHandler(settings, executionParentClass)
                        .run(project, editor, expression)
                } catch (e: CommonRefactoringUtil.RefactoringErrorHintException) {
                    throw preparationFrom(e, profile)
                }
                if (!accepted) {
                    throw IntroduceMemberConflictException(
                        "Native Introduce Field declined to introduce the member.",
                    )
                }
            }
        }
    }

    private fun preparationFrom(
        e: CommonRefactoringUtil.RefactoringErrorHintException,
        profile: IntroduceMemberProfile,
    ): IntroduceMemberPreparationException = IntroduceMemberPreparationException(
        e.message?.takeIf { it.isNotBlank() }
            ?: "Native ${profile.operationName} refused the expression.",
    )

    private fun rollbackNativeMutation(
        project: Project,
        document: com.intellij.openapi.editor.Document,
        originalSource: String,
        originallyUnsaved: Boolean,
        cause: Exception,
    ) {
        if (document.text == originalSource) return

        val undoManager = UndoManager.getInstance(project)
        if (!undoManager.isUndoAvailable(null)) {
            throw rollbackFailure("the native command is not available to Undo", cause)
        }
        undoManager.undo(null)
        PsiDocumentManager.getInstance(project).commitDocument(document)
        if (document.text != originalSource) {
            throw rollbackFailure("Undo did not restore the exact original source", cause)
        }
        if (!originallyUnsaved) {
            FileDocumentManager.getInstance().saveDocument(document)
        }
    }

    private fun rollbackFailure(reason: String, cause: Exception): IllegalStateException =
        IllegalStateException(
            "Native Introduce Member failed after mutation and rollback failed because $reason.",
            cause,
        )
}

internal fun interface IntroduceMemberResultInspector {
    fun inspect(
        targetClass: PsiClass,
        requestedName: String,
        expectedName: String,
        profile: IntroduceMemberProfile,
    ): IntroduceMemberExecutionResult
}

private object DefaultIntroduceMemberResultInspector : IntroduceMemberResultInspector {
    override fun inspect(
        targetClass: PsiClass,
        requestedName: String,
        expectedName: String,
        profile: IntroduceMemberProfile,
    ): IntroduceMemberExecutionResult {
        val created = targetClass.fields.singleOrNull { it.name == expectedName }
            ?: throw IntroduceMemberPreparationException(
                "Native ${profile.operationName} did not create the member '$expectedName' " +
                    "in '${targetClass.qualifiedName ?: targetClass.name}'.",
            )
        val actualName = created.name
        return IntroduceMemberExecutionResult(
            requestedFieldName = requestedName,
            actualFieldName = actualName,
            fieldType = created.type.canonicalText,
            fieldModifiers = reportModifiers(created),
            initializationPlace = "FIELD_DECLARATION",
            summary = if (profile is IntroduceMemberProfile.Constant) {
                "Introduced constant '$actualName'."
            } else {
                "Introduced field '$actualName'."
            },
        )
    }

    private fun reportModifiers(field: PsiField): List<String> = listOf(
        PsiModifier.PUBLIC,
        PsiModifier.PROTECTED,
        PsiModifier.PRIVATE,
        PsiModifier.STATIC,
        PsiModifier.FINAL,
        PsiModifier.VOLATILE,
        PsiModifier.TRANSIENT,
    ).filter { field.hasModifierProperty(it) }
}

/**
 * Private adapters that move the proven headless dialog-override behavior into production. The
 * fixed [BaseExpressionToFieldHandler.Settings] are built once by the executor and returned from
 * [showRefactoringDialog]; no dialog, chooser, preview, or template is ever opened.
 */
private class FixedIntroduceConstantHandler(
    private val fixedSettings: BaseExpressionToFieldHandler.Settings,
    private val executionParentClass: PsiClass,
) : IntroduceConstantHandler() {

    override fun getParentClass(expression: PsiExpression): PsiClass = executionParentClass

    override fun showRefactoringDialog(
        project: Project,
        editor: Editor?,
        parentClass: PsiClass,
        expression: PsiExpression,
        type: PsiType,
        occurrences: Array<PsiExpression>,
        anchorElement: PsiElement,
        anchor: PsiElement,
    ): BaseExpressionToFieldHandler.Settings = fixedSettings
}

private class FixedIntroduceFieldHandler(
    private val fixedSettings: BaseExpressionToFieldHandler.Settings,
    private val executionParentClass: PsiClass,
) : IntroduceFieldHandler() {

    override fun getParentClass(expression: PsiExpression): PsiClass = executionParentClass

    fun run(project: Project, editor: Editor, expression: PsiExpression): Boolean =
        invokeImpl(project, expression, editor)

    override fun showRefactoringDialog(
        project: Project,
        editor: Editor?,
        parentClass: PsiClass,
        expression: PsiExpression,
        type: PsiType,
        occurrences: Array<PsiExpression>,
        anchorElement: PsiElement,
        anchor: PsiElement,
    ): BaseExpressionToFieldHandler.Settings = fixedSettings
}
