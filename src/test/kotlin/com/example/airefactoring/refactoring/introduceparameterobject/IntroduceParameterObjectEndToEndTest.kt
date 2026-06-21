package com.example.airefactoring.refactoring.introduceparameterobject

import com.example.airefactoring.action.AiIntroduceParameterObjectAction
import com.example.airefactoring.llm.LlmClient
import com.example.airefactoring.settings.AiRefactoringSettings
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiMethod
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/**
 * Action-level test proving [AiIntroduceParameterObjectAction] drives the introduce-parameter-object
 * handler from a caret position through the shared pipeline.
 */
class IntroduceParameterObjectEndToEndTest : LightJavaCodeInsightFixtureTestCase() {

    private class FakeLlm(var response: String) : LlmClient {
        var calls = 0
        override fun complete(systemPrompt: String, userPrompt: String, settings: AiRefactoringSettings.State): String {
            calls++
            return response
        }
    }

    private class SpyExecutor : IntroduceParameterObjectExecutor {
        var calls = 0
        var lastClassName: String? = null
        override fun introduce(project: Project, method: PsiMethod, className: String): String {
            calls++
            lastClassName = className
            return "Introduced parameter object '$className' for method '${method.name}'."
        }
    }

    private fun configureValidSettings() {
        AiRefactoringSettings.getInstance().loadState(
            AiRefactoringSettings.State(
                baseUrl = "http://localhost",
                apiKey = "test-key",
                model = "test-model",
                enablePreview = false,
            )
        )
    }

    // Caret inside the body of a 3-parameter method; the handler resolves the enclosing method.
    private fun configureMethod() {
        myFixture.configureByText(
            "Svc.java",
            "public class Svc { void handle(int userId, String name, boolean active){ int <caret>x = userId; } }",
        )
    }

    fun testIntroduceParameterObjectFlowRoutesToHandler() {
        configureValidSettings()
        configureMethod()
        val llm = FakeLlm("""{"action":"introduce_parameter_object","className":"UserRequest"}""")
        val spy = SpyExecutor()
        val action = AiIntroduceParameterObjectAction(llmFactory = { llm }, introduceExecutorFactory = { spy })
        action.run(project, myFixture.editor, myFixture.file)
        assertEquals(1, llm.calls)
        assertEquals(1, spy.calls)
        assertEquals("UserRequest", spy.lastClassName)
    }

    fun testNoActionDoesNotCallExecutor() {
        configureValidSettings()
        configureMethod()
        val llm = FakeLlm("""{"action":"no_action"}""")
        val spy = SpyExecutor()
        val action = AiIntroduceParameterObjectAction(llmFactory = { llm }, introduceExecutorFactory = { spy })
        action.run(project, myFixture.editor, myFixture.file)
        assertEquals(1, llm.calls)
        assertEquals(0, spy.calls)
    }

    fun testInvalidClassNameDoesNotCallExecutor() {
        configureValidSettings()
        configureMethod()
        val llm = FakeLlm("""{"action":"introduce_parameter_object","className":"userRequest"}""")
        val spy = SpyExecutor()
        val action = AiIntroduceParameterObjectAction(llmFactory = { llm }, introduceExecutorFactory = { spy })
        action.run(project, myFixture.editor, myFixture.file)
        assertEquals(1, llm.calls)
        assertEquals(0, spy.calls)
    }
}
