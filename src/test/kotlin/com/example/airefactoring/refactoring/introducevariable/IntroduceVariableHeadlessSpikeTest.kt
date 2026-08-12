package com.example.airefactoring.refactoring.introducevariable

import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.PsiBinaryExpression
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.PsiType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.IntroduceVariableUtil
import com.intellij.refactoring.introduceVariable.IntroduceVariableBase
import com.intellij.refactoring.introduceVariable.IntroduceVariableSettings
import com.intellij.refactoring.introduceVariable.VariableExtractor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class IntroduceVariableHeadlessSpikeTest : LightJavaCodeInsightFixtureTestCase() {

    fun testIntroducesVariableFromExactRangeWithoutEditorOrDialogAndUndoRestoresSource() {
        val file = myFixture.addFileToProject(
            "example/Calculator.java",
            """
                package example;

                public class Calculator {
                    public int total() {
                        return 10 + 20;
                    }
                }
            """.trimIndent(),
        ) as PsiJavaFile

        val originalText = file.text
        val originalExpression = PsiTreeUtil.findChildOfType(file, PsiBinaryExpression::class.java)!!
        val resolvedExpression = IntroduceVariableUtil.findExpressionInRange(
            project,
            file,
            originalExpression.textRange.startOffset,
            originalExpression.textRange.endOffset,
        )
        assertSame("exact range must resolve the selected PSI expression", originalExpression, resolvedExpression)

        val expression = requireNotNull(resolvedExpression)
        val selectedType = requireNotNull(expression.type)
        val settings = object : IntroduceVariableSettings {
            override fun getEnteredName(): String = "sum"
            override fun isReplaceAllOccurrences(): Boolean = false
            override fun isDeclareFinal(): Boolean = false
            override fun isDeclareVarType(): Boolean = false
            override fun isReplaceLValues(): Boolean = false
            override fun getSelectedType(): PsiType = selectedType
            override fun isOK(): Boolean = true
        }
        val anchor = requireNotNull(IntroduceVariableBase.getAnchor(expression))

        CommandProcessor.getInstance().executeCommand(
            project,
            Runnable {
                CommandProcessor.getInstance().markCurrentCommandAsGlobal(project)
                val introduced = VariableExtractor.introduce(
                    project,
                    expression,
                    null,
                    anchor,
                    arrayOf(expression),
                    settings,
                )
                assertNotNull("native extractor did not return a variable", introduced)
            },
            "Headless Introduce Variable Spike",
            null,
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val variable = PsiTreeUtil.findChildrenOfType(file, PsiLocalVariable::class.java)
            .single { it.name == "sum" }
        assertEquals("int", variable.type.canonicalText)
        assertEquals("10 + 20", variable.initializer!!.text)

        val returnStatement = PsiTreeUtil.findChildOfType(file, PsiReturnStatement::class.java)!!
        val reference = returnStatement.returnValue as PsiReferenceExpression
        assertEquals("sum", reference.referenceName)
        assertSame(variable, reference.resolve())

        val undoManager = UndoManager.getInstance(project)
        assertTrue("Introduce Variable command must be available as one global Undo", undoManager.isUndoAvailable(null))
        val previousDialog = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undoManager.undo(null)
        } finally {
            TestDialogManager.setTestDialog(previousDialog)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(originalText, file.text)
    }

    fun testVoidExpressionReturnsNativePreflightErrorWithoutMutation() {
        val file = myFixture.addFileToProject(
            "example/Printer.java",
            """
                package example;

                public class Printer {
                    public void print() {
                        System.out.println("value");
                    }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val originalText = file.text
        val expression = PsiTreeUtil.findChildOfType(file, PsiMethodCallExpression::class.java)!!

        val result = IntroduceVariableBase.getIntroduceVariableContext(project, expression, null)

        assertTrue(
            "void expression must return a native preflight error but was $result",
            result is IntroduceVariableBase.IntroduceVariableResult.Error,
        )
        val error = result as IntroduceVariableBase.IntroduceVariableResult.Error
        assertTrue("native preflight error must be explainable", !error.message.isNullOrBlank())
        assertEquals(originalText, file.text)
    }
}
