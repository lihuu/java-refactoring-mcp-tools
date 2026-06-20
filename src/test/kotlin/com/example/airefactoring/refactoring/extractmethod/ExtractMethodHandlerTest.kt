package com.example.airefactoring.refactoring.extractmethod

import com.example.airefactoring.settings.AiRefactoringSettings
import com.example.airefactoring.refactoring.RefactorParseException
import com.example.airefactoring.validator.ValidationResult
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class ExtractMethodHandlerTest : LightJavaCodeInsightFixtureTestCase() {

    private val handler = ExtractMethodHandler()

    private class SpyExecutor : ExtractMethodExecutor {
        var calls = 0
        var lastMethodName: String? = null
        var lastElementCount: Int = -1
        override fun extract(
            project: Project,
            file: PsiFile,
            elements: Array<PsiElement>,
            methodName: String,
        ): String {
            calls++
            lastMethodName = methodName
            lastElementCount = elements.size
            return "Extracted method '$methodName'."
        }
    }

    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    // --- resolve ---

    fun testResolveWithSelection() {
        myFixture.configureByText(
            "Calc.java",
            "public class Calc { int add(int x,int y){ <selection>int s = x + y;</selection> return s; } }",
        )
        val target = handler.resolve(myFixture.file, myFixture.editor, myFixture.editor.caretModel.offset)
        assertNotNull(target)
        target!!
        val ctx = target.context as ExtractMethodContext
        assertTrue(ctx.data.selectedCode.contains("int s = x + y"))
        assertTrue(ctx.elements.isNotEmpty())
    }

    fun testResolveWithCaretInStatementNoSelection() {
        myFixture.configureByText(
            "Calc.java",
            "public class Calc { int add(int x,int y){ int s = x +<caret> y; return s; } }",
        )
        val target = handler.resolve(myFixture.file, myFixture.editor, myFixture.editor.caretModel.offset)
        assertNotNull(target)
    }

    fun testResolveNonJavaFileReturnsNull() {
        myFixture.configureByText("notes.txt", "hello <caret>world")
        val target = handler.resolve(myFixture.file, myFixture.editor, myFixture.editor.caretModel.offset)
        assertNull(target)
    }

    fun testResolveCaretNotInStatementReturnsNull() {
        myFixture.configureByText("Calc.java", "public class Cal<caret>c { }")
        val target = handler.resolve(myFixture.file, myFixture.editor, myFixture.editor.caretModel.offset)
        assertNull(target)
    }

    // --- parse ---

    fun testParseValid() {
        val op = handler.parse(json("""{"action":"extract_method","methodName":"computeSum","reason":"x"}"""))
        assertTrue(op is ExtractMethodOperation)
        op as ExtractMethodOperation
        assertEquals("computeSum", op.methodName)
        assertEquals("x", op.reason)
    }

    fun testParseMissingMethodNameThrows() {
        try {
            handler.parse(json("""{"action":"extract_method","reason":"x"}"""))
            fail("expected RefactorParseException")
        } catch (e: RefactorParseException) {
            // expected
        }
    }

    fun testParseBlankMethodNameThrows() {
        try {
            handler.parse(json("""{"action":"extract_method","methodName":"   "}"""))
            fail("expected RefactorParseException")
        } catch (e: RefactorParseException) {
            // expected
        }
    }

    fun testParseNonStringMethodNameThrows() {
        try {
            handler.parse(json("""{"action":"extract_method","methodName":[]}"""))
            fail("expected RefactorParseException")
        } catch (e: RefactorParseException) {
            // expected: safe extraction
        }
    }

    fun testParseWrongActionThrows() {
        try {
            handler.parse(json("""{"action":"no_action","reason":"x"}"""))
            fail("expected RefactorParseException")
        } catch (e: RefactorParseException) {
            // expected
        }
    }

    // --- validate ---

    private fun selectionTarget(): com.example.airefactoring.refactoring.RefactorTarget {
        myFixture.configureByText(
            "Calc.java",
            "public class Calc { int add(int x,int y){ <selection>int s = x + y;</selection> return s; } }",
        )
        return handler.resolve(myFixture.file, myFixture.editor, myFixture.editor.caretModel.offset)!!
    }

    fun testValidateAcceptsGoodName() {
        val target = selectionTarget()
        val result = handler.validate(ExtractMethodOperation("computeSum", null), target, project)
        assertEquals(ValidationResult.Ok, result)
    }

    fun testValidateRejectsKeyword() {
        val target = selectionTarget()
        val result = handler.validate(ExtractMethodOperation("class", null), target, project)
        assertTrue(result is ValidationResult.Invalid)
    }

    fun testValidateRejectsUpperCamel() {
        val target = selectionTarget()
        val result = handler.validate(ExtractMethodOperation("ComputeSum", null), target, project)
        assertTrue(result is ValidationResult.Invalid)
    }

    // --- execute ---

    fun testExecuteCallsExecutorWithMethodName() {
        myFixture.configureByText(
            "Calc.java",
            "public class Calc { int add(int x,int y){ <selection>int s = x + y;</selection> return s; } }",
        )
        val target = handler.resolve(myFixture.file, myFixture.editor, myFixture.editor.caretModel.offset)!!
        val spy = SpyExecutor()
        val handlerWithSpy = ExtractMethodHandler(executorFactory = { spy })
        val summary = handlerWithSpy.execute(
            ExtractMethodOperation("computeSum", null),
            target,
            project,
            AiRefactoringSettings.State(enablePreview = false),
        )
        assertEquals(1, spy.calls)
        assertEquals("computeSum", spy.lastMethodName)
        assertEquals((target.context as ExtractMethodContext).elements.size, spy.lastElementCount)
        assertEquals("Extracted method 'computeSum'.", summary)
    }
}
