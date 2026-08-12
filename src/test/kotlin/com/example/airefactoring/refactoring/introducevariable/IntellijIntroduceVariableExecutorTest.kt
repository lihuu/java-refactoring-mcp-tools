package com.example.airefactoring.refactoring.introducevariable

import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiBinaryExpression
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTypes
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path

class IntellijIntroduceVariableExecutorTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = IntroduceVariableSelectionResolver()
    private val executor = IntellijIntroduceVariableExecutor()

    fun testIntroducesExplicitNonFinalVariableForOnlySelectedOccurrence() {
        val selection = resolveFirstExpression(
            "CalculatorOne.java",
            "class CalculatorOne { int total() { return (10 + 20) + (10 + 20); } }",
            "10 + 20",
        )

        val result = executor.introduce(project, selection, "sum")
        PsiDocumentManager.getInstance(project).commitDocument(selection.document)

        assertEquals("sum", result.actualVariableName)
        assertEquals("int", result.variableType)
        assertEquals("Introduced local variable 'sum'.", result.summary)
        val variable = PsiTreeUtil.findChildrenOfType(selection.file, PsiLocalVariable::class.java)
            .single { it.name == result.actualVariableName }
        assertEquals("int", variable.typeElement.text)
        assertFalse(variable.hasModifierProperty(PsiModifier.FINAL))
        assertEquals("10 + 20", variable.initializer!!.text)
        val remaining = PsiTreeUtil.findChildrenOfType(selection.file, PsiBinaryExpression::class.java)
            .count { it.text == "10 + 20" }
        assertEquals("initializer plus untouched duplicate must remain", 2, remaining)
    }

    fun testConflictingPreferredNameUsesIntellijUniqueName() {
        val selection = resolveFirstExpression(
            "CalculatorConflict.java",
            "class CalculatorConflict { int total() { int sum = 1; return Math.max(10 + 20, sum); } }",
            "10 + 20",
        )

        val result = executor.introduce(project, selection, "sum")
        PsiDocumentManager.getInstance(project).commitDocument(selection.document)

        assertTrue(result.actualVariableName.startsWith("sum"))
        assertFalse("conflict must change the requested name", result.actualVariableName == "sum")
        assertTrue(
            PsiTreeUtil.findChildrenOfType(selection.file, PsiLocalVariable::class.java)
                .any { it.name == result.actualVariableName },
        )
    }

    fun testNativeVoidPreflightThrowsPreparationExceptionWithoutMutation() {
        val selection = selectionWithoutSupportedChecks(
            "VoidPreflight.java",
            "class VoidPreflight { void run() { System.out.println(1); } }",
            PsiMethodCallExpression::class.java,
        )
        val original = selection.file.text

        try {
            executor.introduce(project, selection, "printed")
            fail("expected native preflight refusal")
        } catch (e: IntroduceVariablePreparationException) {
            assertTrue(e.message!!.isNotBlank())
        }
        assertEquals(original, selection.file.text)
    }

    fun testDoesNotSaveUnrelatedDirtyDocument() {
        val selection = resolveFirstExpression(
            "SaveTarget.java",
            "class SaveTarget { int total() { return 10 + 20; } }",
            "10 + 20",
        )
        val otherDocument = createAndDirtyRealJavaFile("OtherDirty.java")
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(otherDocument))

        executor.introduce(project, selection, "sum")

        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(otherDocument))
    }

    fun testOneGlobalUndoRestoresExactOriginalSource() {
        val selection = resolveFirstExpression(
            "UndoTarget.java",
            "class UndoTarget { int total() { return 10 + 20; } }",
            "10 + 20",
        )
        val original = selection.document.text

        executor.introduce(project, selection, "sum")

        val undoManager = UndoManager.getInstance(project)
        assertTrue(undoManager.isUndoAvailable(null))
        val previous = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undoManager.undo(null)
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
        PsiDocumentManager.getInstance(project).commitDocument(selection.document)
        assertEquals(original, selection.document.text)
    }

    private fun resolveFirstExpression(
        fileName: String,
        source: String,
        expressionText: String,
    ): IntroduceVariableSelection {
        val virtualFile = mirrorRealFile(fileName, source)
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        val startOffset = document.text.indexOf(expressionText)
        assertTrue("expression '$expressionText' missing", startOffset >= 0)
        val result = resolver.resolve(
            project,
            fileName,
            range(document, startOffset, startOffset + expressionText.length),
        )
        assertTrue(
            "expected successful selection but was $result",
            result is IntroduceVariableSelectionResolution.Success,
        )
        return (result as IntroduceVariableSelectionResolution.Success).selection
    }

    private fun <T : PsiExpression> selectionWithoutSupportedChecks(
        fileName: String,
        source: String,
        expressionClass: Class<T>,
    ): IntroduceVariableSelection {
        val virtualFile = mirrorRealFile(fileName, source)
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val file = PsiManager.getInstance(project).findFile(virtualFile) as PsiJavaFile
        val expression = PsiTreeUtil.findChildOfType(file, expressionClass)!!
        return IntroduceVariableSelection(
            file,
            document,
            expression,
            PsiTypes.voidType(),
        )
    }

    private fun createAndDirtyRealJavaFile(fileName: String): Document {
        val virtualFile = mirrorRealFile(fileName, "class OtherDirty { int value = 1; }")
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(document.textLength, "\n// dirty")
        }
        return document
    }

    private fun mirrorRealFile(fileName: String, text: String): VirtualFile {
        val target = Path.of(project.basePath!!, fileName)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(target.toString())!!
    }

    private fun range(document: Document, startOffset: Int, endOffset: Int): SourceRange {
        fun position(offset: Int): Pair<Int, Int> {
            val line = document.getLineNumber(offset)
            return (line + 1) to (offset - document.getLineStartOffset(line) + 1)
        }

        val (startLine, startColumn) = position(startOffset)
        val (endLine, endColumn) = position(endOffset)
        return SourceRange(startLine, startColumn, endLine, endColumn)
    }
}
