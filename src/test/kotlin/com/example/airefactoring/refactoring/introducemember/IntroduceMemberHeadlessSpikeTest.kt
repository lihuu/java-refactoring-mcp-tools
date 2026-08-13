package com.example.airefactoring.refactoring.introducemember

import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.introduceField.BaseExpressionToFieldHandler
import com.intellij.refactoring.introduceField.IntroduceConstantHandler
import com.intellij.refactoring.introduceField.IntroduceFieldHandler
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class IntroduceMemberHeadlessSpikeTest : LightJavaCodeInsightFixtureTestCase() {

    private class FixedConstantHandler(private val fieldName: String) : IntroduceConstantHandler() {
        override fun showRefactoringDialog(
            project: Project,
            editor: Editor?,
            parentClass: PsiClass,
            expression: PsiExpression,
            type: PsiType,
            occurrences: Array<PsiExpression>,
            anchorElement: PsiElement,
            anchor: PsiElement,
        ): BaseExpressionToFieldHandler.Settings = settings(
            fieldName, expression, type, parentClass, declareStatic = true,
        )
    }

    private class FixedFieldHandler(private val fieldName: String) : IntroduceFieldHandler() {
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
        ): BaseExpressionToFieldHandler.Settings = settings(
            fieldName, expression, type, parentClass, declareStatic = false,
        )
    }

    fun testIntroduceConstantHeadlessSelectedOnlyAndOneUndo() {
        val file = myFixture.addFileToProject(
            "example/ConstantSpike.java",
            """
                package example;

                class ConstantSpike {
                    int value() { return 12 + 12; }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val originalText = file.text

        // Open the file in an editor, then park the caret at offset zero to prove the handler
        // targets the passed expression, not the caret state.
        myFixture.configureByFile("example/ConstantSpike.java")
        myFixture.editor.caretModel.moveToOffset(0)
        val firstLiteral = PsiTreeUtil.findChildrenOfType(file, PsiLiteralExpression::class.java)
            .first { it.text == "12" }

        val throwingDialog = object : TestDialog {
            override fun show(message: String): Int =
                throw AssertionError("Introduce Constant must not open a dialog: $message")
        }
        val previousDialog = TestDialogManager.setTestDialog(throwingDialog)
        try {
            CommandProcessor.getInstance().executeCommand(
                project,
                Runnable {
                    CommandProcessor.getInstance().markCurrentCommandAsGlobal(project)
                    FixedConstantHandler("MONTHS_PER_YEAR").invoke(project, myFixture.editor, firstLiteral)
                },
                "Headless Introduce Constant Spike",
                null,
            )
        } finally {
            TestDialogManager.setTestDialog(previousDialog)
        }
        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val field = PsiTreeUtil.findChildrenOfType(file, PsiField::class.java)
            .single { it.name == "MONTHS_PER_YEAR" }
        assertTrue("constant must be private", field.hasModifierProperty(PsiModifier.PRIVATE))
        assertTrue("constant must be static", field.hasModifierProperty(PsiModifier.STATIC))
        assertTrue("constant must be final", field.hasModifierProperty(PsiModifier.FINAL))
        assertEquals("12", field.initializer!!.text)
        assertEquals(
            "selected-only replacement: one new initializer plus one untouched original occurrence",
            2,
            Regex("\\b12\\b").findAll(file.text).count(),
        )

        val undoManager = UndoManager.getInstance(project)
        assertTrue("Introduce Constant must be available as one global Undo", undoManager.isUndoAvailable(null))
        val undoDialog = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undoManager.undo(null)
        } finally {
            TestDialogManager.setTestDialog(undoDialog)
        }
        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(originalText, file.text)
    }

    fun testIntroduceFieldHeadlessSelectedOnlyAndOneUndo() {
        val file = myFixture.addFileToProject(
            "example/FieldSpike.java",
            """
                package example;

                class FieldSpike {
                    private final Policy policy = new Policy();
                    int value() { return policy.rate() + policy.rate(); }
                    static class Policy { int rate() { return 2; } }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val originalText = file.text

        // Open the file in an editor, then park the caret at offset zero to prove the handler
        // targets the passed expression, not the caret state.
        myFixture.configureByFile("example/FieldSpike.java")
        myFixture.editor.caretModel.moveToOffset(0)
        val firstCall = PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression::class.java)
            .first { it.text == "policy.rate()" }

        val throwingDialog = object : TestDialog {
            override fun show(message: String): Int =
                throw AssertionError("Introduce Field must not open a dialog: $message")
        }
        val previousDialog = TestDialogManager.setTestDialog(throwingDialog)
        var ok = false
        try {
            CommandProcessor.getInstance().executeCommand(
                project,
                Runnable {
                    CommandProcessor.getInstance().markCurrentCommandAsGlobal(project)
                    ok = FixedFieldHandler("defaultRate").run(project, myFixture.editor, firstCall)
                },
                "Headless Introduce Field Spike",
                null,
            )
        } finally {
            TestDialogManager.setTestDialog(previousDialog)
        }
        assertTrue("native Introduce Field must report success", ok)
        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val field = PsiTreeUtil.findChildrenOfType(file, PsiField::class.java)
            .single { it.name == "defaultRate" }
        assertTrue("field must be private", field.hasModifierProperty(PsiModifier.PRIVATE))
        assertTrue("field must be final", field.hasModifierProperty(PsiModifier.FINAL))
        assertFalse("field must not be static", field.hasModifierProperty(PsiModifier.STATIC))
        assertEquals("policy.rate()", field.initializer!!.text)
        assertEquals(
            "selected-only replacement: one new initializer plus one untouched original call",
            2,
            Regex("policy\\.rate\\(\\)").findAll(file.text).count(),
        )

        val undoManager = UndoManager.getInstance(project)
        assertTrue("Introduce Field must be available as one global Undo", undoManager.isUndoAvailable(null))
        val undoDialog = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undoManager.undo(null)
        } finally {
            TestDialogManager.setTestDialog(undoDialog)
        }
        myFixture.psiManager.dropResolveCaches()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(originalText, file.text)
    }
}

private fun settings(
    name: String,
    expression: PsiExpression,
    type: PsiType,
    containingClass: PsiClass,
    declareStatic: Boolean,
): BaseExpressionToFieldHandler.Settings = BaseExpressionToFieldHandler.Settings(
    name,
    expression,
    arrayOf(expression),
    false,
    declareStatic,
    true,
    BaseExpressionToFieldHandler.InitializationPlace.IN_FIELD_DECLARATION,
    PsiModifier.PRIVATE,
    null,
    type,
    false,
    BaseExpressionToFieldHandler.TargetDestination(containingClass),
    false,
    false,
)
