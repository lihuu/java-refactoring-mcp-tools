package com.example.airefactoring.refactoring.introducevariable

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAndWriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersistence
import com.example.airefactoring.refactoring.NativeRefactoringDocumentPersister
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiType
import com.intellij.psi.PsiVariable
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.PsiTypes
import com.intellij.refactoring.IntroduceVariableUtil
import com.intellij.refactoring.introduceVariable.IntroduceVariableSettings
import com.intellij.refactoring.introduceVariable.VariableExtractor
import com.intellij.util.CommonJavaRefactoringUtil

/** Executes IntelliJ's native Java Introduce Variable refactoring with every UI choice fixed. */
class IntellijIntroduceVariableExecutor internal constructor(
    private val documentPersistence: NativeRefactoringDocumentPersister =
        NativeRefactoringDocumentPersistence(),
) : IntroduceVariableExecutor {

    override suspend fun introduce(
        project: Project,
        selection: IntroduceVariableSelection,
        preferredVariableName: String,
    ): IntroduceVariableExecutionResult = constrainedReadAndWriteAction(
        ReadConstraint.inSmartMode(project),
    ) {
        if (!selection.expression.isValid) {
            throw IntroduceVariablePreparationException(
                "The selected expression changed before Introduce Variable could run.",
            )
        }
        val errorMessage = IntroduceVariableUtil.getErrorMessage(selection.expression)
        if (errorMessage != null) {
            throw IntroduceVariablePreparationException(errorMessage)
        }
        val expression = selection.expression
        val selectedType = expression.type
            ?: throw IntroduceVariablePreparationException("Cannot infer expression type.")
        if (selectedType == PsiTypes.voidType()) {
            throw IntroduceVariablePreparationException("Selected expression has void type.")
        }
        val anchor = CommonJavaRefactoringUtil.getParentStatement(expression, false)
            ?: throw IntroduceVariablePreparationException("No parent statement found for the selected expression.")
        val preparation = IntroduceVariablePreparation(
            expression = expression,
            anchor = anchor,
            selectedType = selectedType,
            variableType = selectedType.canonicalText,
            actualName = JavaCodeStyleManager.getInstance(project)
                .suggestUniqueVariableName(preferredVariableName, expression, true),
        )

        writeAction {
            ApplicationManager.getApplication().assertIsDispatchThread()
            if (!selection.expression.isValid) {
                throw IntroduceVariablePreparationException(
                    "The selected expression changed before Introduce Variable could run.",
                )
            }
            if (!preparation.expression.isValid || !preparation.anchor.isValid) {
                throw IntroduceVariablePreparationException(
                    "The selected expression changed before Introduce Variable could run.",
                )
            }
            val settings = object : IntroduceVariableSettings {
                override fun getEnteredName(): String = preparation.actualName
                override fun isReplaceAllOccurrences(): Boolean = false
                override fun isDeclareFinal(): Boolean = false
                override fun isDeclareVarType(): Boolean = false
                override fun isReplaceLValues(): Boolean = false
                override fun getSelectedType(): PsiType = preparation.selectedType
                override fun isOK(): Boolean = true
            }

            var introduced: PsiVariable? = null
            CommandProcessor.getInstance().executeCommand(
                project,
                Runnable {
                    CommandProcessor.getInstance().markCurrentCommandAsGlobal(project)
                    introduced = VariableExtractor.introduce(
                        project,
                        preparation.expression,
                        null,
                        preparation.anchor,
                        arrayOf(preparation.expression),
                        settings,
                    ) ?: throw IntroduceVariablePreparationException(
                        "Native Introduce Variable did not create a variable.",
                    )
                },
                "MCP Introduce Variable",
                null,
            )
            val virtualFile = FileDocumentManager.getInstance().getFile(selection.document)
                ?: throw IntroduceVariablePreparationException(
                    "The selected document no longer belongs to a file.",
                )
            documentPersistence.persist(project, setOf(virtualFile))

            val introducedName = introduced?.name
                ?: throw IllegalStateException("Native Introduce Variable returned an unnamed variable.")
            IntroduceVariableExecutionResult(
                actualVariableName = introducedName,
                variableType = preparation.variableType,
                summary = "Introduced local variable '$introducedName'.",
            )
        }
    }

    private data class IntroduceVariablePreparation(
        val expression: PsiExpression,
        val anchor: PsiElement,
        val selectedType: PsiType,
        val variableType: String,
        val actualName: String,
    )
}
