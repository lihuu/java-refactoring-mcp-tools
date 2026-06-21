package com.example.airefactoring.refactoring.introduceparameterobject

import com.example.airefactoring.settings.AiRefactoringSettings
import com.example.airefactoring.refactoring.RefactorParseException
import com.example.airefactoring.validator.ValidationResult
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiMethod
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class IntroduceParameterObjectHandlerTest : LightJavaCodeInsightFixtureTestCase() {

    private val handler = IntroduceParameterObjectHandler(executorFactory = { SpyExecutor() })

    private class SpyExecutor : IntroduceParameterObjectExecutor {
        var calls = 0
        var lastClassName: String? = null
        override fun introduce(project: Project, method: PsiMethod, className: String): String {
            calls++
            lastClassName = className
            return "Introduced parameter object '$className'."
        }
    }

    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    // --- resolve ---

    fun testResolveThreeParamMethod() {
        myFixture.configureByText(
            "Svc.java",
            "public class Svc { void handle(int userId, String name, boolean active){ int <caret>x = userId; } }",
        )
        val target = handler.resolve(myFixture.file, myFixture.editor, myFixture.editor.caretModel.offset)
        assertNotNull(target)
        target!!
        val ctx = target.context as IntroduceParameterObjectContext
        assertEquals(3, ctx.data.parameters.size)
        assertEquals("handle", ctx.data.methodName)
    }

    fun testResolveTwoParamMethodReturnsNull() {
        myFixture.configureByText(
            "Svc.java",
            "public class Svc { void handle(int a, String b){ int <caret>x = a; } }",
        )
        val target = handler.resolve(myFixture.file, myFixture.editor, myFixture.editor.caretModel.offset)
        assertNull(target)
    }

    fun testResolveNonJavaFileReturnsNull() {
        myFixture.configureByText("notes.txt", "hello <caret>world")
        val target = handler.resolve(myFixture.file, myFixture.editor, myFixture.editor.caretModel.offset)
        assertNull(target)
    }

    fun testResolveCaretNotInMethodReturnsNull() {
        myFixture.configureByText("Svc.java", "public class Sv<caret>c { void handle(int a, String b, boolean c){} }")
        val target = handler.resolve(myFixture.file, myFixture.editor, myFixture.editor.caretModel.offset)
        assertNull(target)
    }

    // --- parse ---

    fun testParseValid() {
        val op = handler.parse(json("""{"action":"introduce_parameter_object","className":"UserData","reason":"x"}"""))
        assertTrue(op is IntroduceParameterObjectOperation)
        op as IntroduceParameterObjectOperation
        assertEquals("UserData", op.className)
        assertEquals("x", op.reason)
    }

    fun testParseMissingClassNameThrows() {
        try {
            handler.parse(json("""{"action":"introduce_parameter_object","reason":"x"}"""))
            fail("expected RefactorParseException")
        } catch (e: RefactorParseException) {
            // expected
        }
    }

    fun testParseBlankClassNameThrows() {
        try {
            handler.parse(json("""{"action":"introduce_parameter_object","className":"   "}"""))
            fail("expected RefactorParseException")
        } catch (e: RefactorParseException) {
            // expected
        }
    }

    fun testParseNonStringClassNameThrows() {
        try {
            handler.parse(json("""{"action":"introduce_parameter_object","className":[]}"""))
            fail("expected RefactorParseException")
        } catch (e: RefactorParseException) {
            // expected
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

    private fun methodTarget(): com.example.airefactoring.refactoring.RefactorTarget {
        myFixture.configureByText(
            "Svc.java",
            "public class Svc { void handle(int userId, String name, boolean active){ int <caret>x = userId; } }",
        )
        return handler.resolve(myFixture.file, myFixture.editor, myFixture.editor.caretModel.offset)!!
    }

    fun testValidateAcceptsGoodName() {
        val target = methodTarget()
        val result = handler.validate(IntroduceParameterObjectOperation("UserData", null), target, project)
        assertEquals(ValidationResult.Ok, result)
    }

    fun testValidateRejectsLowerCamel() {
        val target = methodTarget()
        val result = handler.validate(IntroduceParameterObjectOperation("userData", null), target, project)
        assertTrue(result is ValidationResult.Invalid)
    }

    fun testValidateRejectsKeyword() {
        val target = methodTarget()
        val result = handler.validate(IntroduceParameterObjectOperation("class", null), target, project)
        assertTrue(result is ValidationResult.Invalid)
    }

    // --- execute ---

    fun testExecuteCallsExecutorWithClassName() {
        myFixture.configureByText(
            "Svc.java",
            "public class Svc { void handle(int userId, String name, boolean active){ int <caret>x = userId; } }",
        )
        val target = handler.resolve(myFixture.file, myFixture.editor, myFixture.editor.caretModel.offset)!!
        val spy = SpyExecutor()
        val handlerWithSpy = IntroduceParameterObjectHandler(executorFactory = { spy })
        val summary = handlerWithSpy.execute(
            IntroduceParameterObjectOperation("UserData", null),
            target,
            project,
            AiRefactoringSettings.State(enablePreview = false),
        )
        assertEquals(1, spy.calls)
        assertEquals("UserData", spy.lastClassName)
        assertEquals("Introduced parameter object 'UserData'.", summary)
    }
}
