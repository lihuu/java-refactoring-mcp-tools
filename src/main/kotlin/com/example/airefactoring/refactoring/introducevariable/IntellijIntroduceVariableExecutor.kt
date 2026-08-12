package com.example.airefactoring.refactoring.introducevariable

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiType
import com.intellij.psi.PsiVariable
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.refactoring.introduceVariable.IntroduceVariableBase
import com.intellij.refactoring.introduceVariable.IntroduceVariableSettings
import com.intellij.refactoring.introduceVariable.VariableExtractor

/** Executes IntelliJ's native Java Introduce Variable refactoring with every UI choice fixed. */
class IntellijIntroduceVariableExecutor : IntroduceVariableExecutor {

    override fun introduce(
        project: Project,
        selection: IntroduceVariableSelection,
        preferredVariableName: String,
    ): IntroduceVariableExecutionResult {
        ApplicationManager.getApplication().assertIsDispatchThread()

        val context = when (
            val result = IntroduceVariableBase.getIntroduceVariableContext(
                project,
                selection.expression,
                null,
            )
        ) {
            is IntroduceVariableBase.IntroduceVariableResult.Error -> {
                throw IntroduceVariablePreparationException(
                    result.message?.takeIf { it.isNotBlank() }
                        ?: "Native Introduce Variable refused the expression.",
                )
            }
            is IntroduceVariableBase.IntroduceVariableResult.Context -> result
        }

        val expression = context.expression()
        val selectedType = context.originalType()
        val actualName = JavaCodeStyleManager.getInstance(project)
            .suggestUniqueVariableName(preferredVariableName, expression, true)
        val settings = object : IntroduceVariableSettings {
            override fun getEnteredName(): String = actualName
            override fun isReplaceAllOccurrences(): Boolean = false
            override fun isDeclareFinal(): Boolean = false
            override fun isDeclareVarType(): Boolean = false
            override fun isReplaceLValues(): Boolean = false
            override fun getSelectedType(): PsiType = selectedType
            override fun isOK(): Boolean = true
        }

        var introduced: PsiVariable? = null
        CommandProcessor.getInstance().executeCommand(
            project,
            Runnable {
                CommandProcessor.getInstance().markCurrentCommandAsGlobal(project)
                introduced = VariableExtractor.introduce(
                    project,
                    expression,
                    null,
                    context.anchorStatement(),
                    arrayOf(expression),
                    settings,
                ) ?: throw IntroduceVariablePreparationException(
                    "Native Introduce Variable did not create a variable.",
                )
            },
            "MCP Introduce Variable",
            null,
        )
        PsiDocumentManager.getInstance(project).commitDocument(selection.document)
        FileDocumentManager.getInstance().saveDocument(selection.document)

        val introducedName = introduced?.name
            ?: throw IllegalStateException("Native Introduce Variable returned an unnamed variable.")
        return IntroduceVariableExecutionResult(
            actualVariableName = introducedName,
            variableType = selectedType.canonicalText,
            summary = "Introduced local variable '$introducedName'.",
        )
    }
}
