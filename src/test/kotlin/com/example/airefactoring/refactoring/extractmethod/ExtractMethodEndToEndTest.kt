package com.example.airefactoring.refactoring.extractmethod

import com.example.airefactoring.action.AiRenameSymbolAction
import com.example.airefactoring.llm.LlmClient
import com.example.airefactoring.settings.AiRefactoringSettings
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/**
 * Action-level test proving the generic orchestrator routes to the extract-method handler via a
 * selection (rename is registry-first but does not resolve a renameable declaration here).
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

    // Selection is a print statement (not a var declaration), so the rename handler does not
    // resolve a renameable symbol and the extract handler wins resolve.
    private fun configureSelection() {
        myFixture.configureByText(
            "Calc.java",
            "public class Calc { void run(int x,int y){ <selection>System.out.println(x + y);</selection> } }",
        )
    }

    fun testExtractMethodFlowRoutesToExtractHandler() {
        configureValidSettings()
        configureSelection()
        val llm = FakeLlm("""{"action":"extract_method","methodName":"computeSum"}""")
        val spy = SpyExtractExecutor()
        val action = AiRenameSymbolAction(llmFactory = { llm }, extractExecutorFactory = { spy })
        action.runForTest(project, myFixture.editor, myFixture.file)
        assertEquals(1, llm.calls)
        assertEquals(1, spy.calls)
        assertEquals("computeSum", spy.lastMethodName)
    }

    fun testNoActionDoesNotCallExtractExecutor() {
        configureValidSettings()
        configureSelection()
        val llm = FakeLlm("""{"action":"no_action"}""")
        val spy = SpyExtractExecutor()
        val action = AiRenameSymbolAction(llmFactory = { llm }, extractExecutorFactory = { spy })
        action.runForTest(project, myFixture.editor, myFixture.file)
        assertEquals(1, llm.calls)
        assertEquals(0, spy.calls)
    }

    fun testInvalidMethodNameDoesNotCallExtractExecutor() {
        configureValidSettings()
        configureSelection()
        val llm = FakeLlm("""{"action":"extract_method","methodName":"class"}""")
        val spy = SpyExtractExecutor()
        val action = AiRenameSymbolAction(llmFactory = { llm }, extractExecutorFactory = { spy })
        action.runForTest(project, myFixture.editor, myFixture.file)
        assertEquals(1, llm.calls)
        assertEquals(0, spy.calls)
    }
}
