package com.example.airefactoring.refactoring.extractmethod

import com.example.airefactoring.action.AiExtractMethodAction
import com.example.airefactoring.llm.LlmClient
import com.example.airefactoring.settings.AiRefactoringSettings
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/**
 * Action-level test proving [AiExtractMethodAction] drives the extract-method handler from a
 * selection through the shared pipeline.
 */
class ExtractMethodEndToEndTest : LightJavaCodeInsightFixtureTestCase() {

    private class FakeLlm(var response: String) : LlmClient {
        var calls = 0
        override fun complete(systemPrompt: String, userPrompt: String, settings: AiRefactoringSettings.State): String {
            calls++
            return response
        }
    }

    private class SpyExtractExecutor : ExtractMethodExecutor {
        var calls = 0
        var lastMethodName: String? = null
        override fun extract(
            project: Project,
            file: PsiFile,
            elements: Array<PsiElement>,
            methodName: String,
        ): String {
            calls++
            lastMethodName = methodName
            return "Extracted method '$methodName'."
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

    // Select an extractable statement; the extract handler resolves it directly.
    private fun configureSelection() {
        myFixture.configureByText(
            "Calc.java",
            "public class Calc { int run(int x,int y){ <selection>int sum = x + y;</selection> return sum; } }",
        )
    }

    fun testExtractMethodFlowRoutesToExtractHandler() {
        configureValidSettings()
        configureSelection()
        val llm = FakeLlm("""{"action":"extract_method","methodName":"computeSum"}""")
        val spy = SpyExtractExecutor()
        val action = AiExtractMethodAction(llmFactory = { llm }, extractExecutorFactory = { spy })
        action.run(project, myFixture.editor, myFixture.file)
        assertEquals(1, llm.calls)
        assertEquals(1, spy.calls)
        assertEquals("computeSum", spy.lastMethodName)
    }

    fun testNoActionDoesNotCallExtractExecutor() {
        configureValidSettings()
        configureSelection()
        val llm = FakeLlm("""{"action":"no_action"}""")
        val spy = SpyExtractExecutor()
        val action = AiExtractMethodAction(llmFactory = { llm }, extractExecutorFactory = { spy })
        action.run(project, myFixture.editor, myFixture.file)
        assertEquals(1, llm.calls)
        assertEquals(0, spy.calls)
    }

    fun testInvalidMethodNameDoesNotCallExtractExecutor() {
        configureValidSettings()
        configureSelection()
        val llm = FakeLlm("""{"action":"extract_method","methodName":"class"}""")
        val spy = SpyExtractExecutor()
        val action = AiExtractMethodAction(llmFactory = { llm }, extractExecutorFactory = { spy })
        action.run(project, myFixture.editor, myFixture.file)
        assertEquals(1, llm.calls)
        assertEquals(0, spy.calls)
    }
}
