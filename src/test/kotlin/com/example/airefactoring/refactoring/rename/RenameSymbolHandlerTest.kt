package com.example.airefactoring.refactoring.rename

import com.example.airefactoring.refactor.IntellijRenameExecutor
import com.example.airefactoring.refactor.RenameExecutor
import com.example.airefactoring.context.RefactorContext
import com.example.airefactoring.refactoring.RefactorParseException
import com.example.airefactoring.refactoring.RefactorTarget
import com.example.airefactoring.resolver.SymbolKind
import com.example.airefactoring.settings.AiRefactoringSettings
import com.example.airefactoring.validator.ValidationResult
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiNamedElement
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class RenameSymbolHandlerTest : LightJavaCodeInsightFixtureTestCase() {
    override fun getTestDataPath(): String = "src/test/testData/resolver"

    private val handler = RenameSymbolHandler()

    private class SpyExecutor(private val real: RenameExecutor) : RenameExecutor {
        var lastNewName: String? = null
        var lastElement: PsiNamedElement? = null
        var calls = 0
        override fun rename(project: Project, element: PsiNamedElement, newName: String, preview: Boolean) {
            calls++
            lastNewName = newName
            lastElement = element
            real.rename(project, element, newName, preview)
        }
    }

    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    // --- resolve ---

    fun testResolveLocalVariable() {
        myFixture.configureByFile("LocalVar.java")
        val target = handler.resolve(myFixture.file, myFixture.editor.caretModel.offset)
        assertNotNull(target)
        target!!
        assertEquals("userCount", target.element.let { it as com.intellij.psi.PsiNamedElement }.name)
        assertEquals(SymbolKind.LOCAL_VARIABLE, (target.context as RefactorContext).symbolKind)
    }

    fun testResolveField() {
        myFixture.configureByFile("Field.java")
        val target = handler.resolve(myFixture.file, myFixture.editor.caretModel.offset)
        assertNotNull(target)
        target!!
        assertEquals("userName", target.element.let { it as com.intellij.psi.PsiNamedElement }.name)
        assertEquals(SymbolKind.FIELD, (target.context as RefactorContext).symbolKind)
    }

    fun testResolveOnWhitespaceReturnsNull() {
        myFixture.configureByFile("NoSymbol.java")
        val target = handler.resolve(myFixture.file, myFixture.editor.caretModel.offset)
        assertNull(target)
    }

    fun testResolveNonJavaFileReturnsNull() {
        myFixture.configureByText("notes.txt", "hello <caret>world")
        val target = handler.resolve(myFixture.file, myFixture.editor.caretModel.offset)
        assertNull(target)
    }

    // --- parse ---

    fun testParseValid() {
        val op = handler.parse(json("""{"action":"rename_symbol","newName":"counter","reason":"clearer"}"""))
        assertTrue(op is RenameOperation)
        op as RenameOperation
        assertEquals("counter", op.newName)
        assertEquals("clearer", op.reason)
    }

    fun testParseMissingNewNameThrows() {
        try {
            handler.parse(json("""{"action":"rename_symbol","reason":"x"}"""))
            fail("expected RefactorParseException")
        } catch (e: RefactorParseException) {
            // expected
        }
    }

    fun testParseBlankNewNameThrows() {
        try {
            handler.parse(json("""{"action":"rename_symbol","newName":"   "}"""))
            fail("expected RefactorParseException")
        } catch (e: RefactorParseException) {
            // expected
        }
    }

    fun testParseNonStringNewNameThrowsRefactorParseException() {
        try {
            handler.parse(json("""{"action":"rename_symbol","newName":[]}"""))
            fail("expected RefactorParseException")
        } catch (e: RefactorParseException) {
            // expected: safe extraction, NOT IllegalArgumentException
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

    private fun localVarTarget(): RefactorTarget {
        myFixture.configureByFile("LocalVar.java")
        return handler.resolve(myFixture.file, myFixture.editor.caretModel.offset)!!
    }

    fun testValidateRejectsKeyword() {
        val target = localVarTarget()
        val result = handler.validate(RenameOperation("class", null), target, project)
        assertTrue(result is ValidationResult.Invalid)
    }

    fun testValidateAcceptsGoodName() {
        val target = localVarTarget()
        val result = handler.validate(RenameOperation("counter", null), target, project)
        assertEquals(ValidationResult.Ok, result)
    }

    // --- execute ---

    fun testExecuteCallsExecutorWithNewName() {
        myFixture.configureByFile("LocalVar.java")
        val target = handler.resolve(myFixture.file, myFixture.editor.caretModel.offset)!!
        val spy = SpyExecutor(IntellijRenameExecutor())
        val handlerWithSpy = RenameSymbolHandler(executorFactory = { spy })
        val summary = handlerWithSpy.execute(
            RenameOperation("counter", null),
            target,
            project,
            AiRefactoringSettings.State(enablePreview = false),
        )
        assertEquals(1, spy.calls)
        assertEquals("counter", spy.lastNewName)
        assertEquals(target.element, spy.lastElement)
        assertEquals("Renamed 'userCount' to 'counter'.", summary)
    }
}
