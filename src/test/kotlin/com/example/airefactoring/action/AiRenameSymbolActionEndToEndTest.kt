package com.example.airefactoring.action

import com.example.airefactoring.llm.LlmClient
import com.example.airefactoring.refactor.RenameExecutor
import com.example.airefactoring.settings.AiRefactoringSettings
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiNamedElement
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class AiRenameSymbolActionEndToEndTest : LightJavaCodeInsightFixtureTestCase() {
    override fun getTestDataPath(): String = "src/test/testData/action"

    private class FakeLlm(var response: String) : LlmClient {
        var calls = 0
        override fun complete(systemPrompt: String, userPrompt: String, settings: AiRefactoringSettings.State): String {
            calls++
            return response
        }
    }

    private class SpyExecutor(private val real: RenameExecutor) : RenameExecutor {
        var lastNewName: String? = null
        var calls = 0
        override fun rename(project: Project, element: PsiNamedElement, newName: String, preview: Boolean) {
            calls++
            lastNewName = newName
            real.rename(project, element, newName, preview)
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

    fun testRenameFlowAppliesNewName() {
        configureValidSettings()
        myFixture.configureByFile("EndToEnd.java")
        val llm = FakeLlm("""{"action":"rename_symbol","newName":"counter"}""")
        val executor = SpyExecutor(com.example.airefactoring.refactor.IntellijRenameExecutor())
        val action = AiRenameSymbolAction(llmFactory = { llm }, executorFactory = { executor })
        action.runForTest(project, myFixture.editor, myFixture.file)
        myFixture.checkResultByFile("EndToEnd_after.java")
        assertEquals(1, llm.calls)
        assertEquals(1, executor.calls)
        assertEquals("counter", executor.lastNewName)
    }

    fun testNoActionDoesNotCallExecutor() {
        configureValidSettings()
        myFixture.configureByFile("EndToEnd.java")
        val llm = FakeLlm("""{"action":"no_action","reason":"already clear"}""")
        val executor = SpyExecutor(com.example.airefactoring.refactor.IntellijRenameExecutor())
        val action = AiRenameSymbolAction(llmFactory = { llm }, executorFactory = { executor })
        action.runForTest(project, myFixture.editor, myFixture.file)
        assertEquals(1, llm.calls)
        assertEquals(0, executor.calls)
    }

    fun testInvalidJsonDoesNotCallExecutor() {
        configureValidSettings()
        myFixture.configureByFile("EndToEnd.java")
        val llm = FakeLlm("not json at all")
        val executor = SpyExecutor(com.example.airefactoring.refactor.IntellijRenameExecutor())
        val action = AiRenameSymbolAction(llmFactory = { llm }, executorFactory = { executor })
        action.runForTest(project, myFixture.editor, myFixture.file)
        assertEquals(1, llm.calls)
        assertEquals(0, executor.calls)
    }

    fun testUnknownActionDoesNotCallExecutor() {
        configureValidSettings()
        myFixture.configureByFile("EndToEnd.java")
        val llm = FakeLlm("""{"action":"explode_universe","newName":"counter"}""")
        val executor = SpyExecutor(com.example.airefactoring.refactor.IntellijRenameExecutor())
        val action = AiRenameSymbolAction(llmFactory = { llm }, executorFactory = { executor })
        action.runForTest(project, myFixture.editor, myFixture.file)
        assertEquals(1, llm.calls)
        assertEquals(0, executor.calls)
    }

    fun testNonStringActionDoesNotCallExecutor() {
        configureValidSettings()
        myFixture.configureByFile("EndToEnd.java")
        val llm = FakeLlm("""{"action":[1,2],"newName":"counter"}""")
        val executor = SpyExecutor(com.example.airefactoring.refactor.IntellijRenameExecutor())
        val action = AiRenameSymbolAction(llmFactory = { llm }, executorFactory = { executor })
        action.runForTest(project, myFixture.editor, myFixture.file)
        assertEquals(1, llm.calls)
        assertEquals(0, executor.calls)
    }

    fun testNonObjectJsonDoesNotCallExecutor() {
        configureValidSettings()
        myFixture.configureByFile("EndToEnd.java")
        val llm = FakeLlm("""[{"action":"no_action"}]""")
        val executor = SpyExecutor(com.example.airefactoring.refactor.IntellijRenameExecutor())
        val action = AiRenameSymbolAction(llmFactory = { llm }, executorFactory = { executor })
        action.runForTest(project, myFixture.editor, myFixture.file)
        assertEquals(1, llm.calls)
        assertEquals(0, executor.calls)
    }

    fun testNonJavaFileSkipsLlmAndExecutor() {
        configureValidSettings()
        myFixture.configureByText("notes.txt", "hello <caret>world")
        val llm = FakeLlm("ignored")
        val executor = SpyExecutor(com.example.airefactoring.refactor.IntellijRenameExecutor())
        val action = AiRenameSymbolAction(llmFactory = { llm }, executorFactory = { executor })
        action.runForTest(project, myFixture.editor, myFixture.file)
        assertEquals(0, llm.calls)
        assertEquals(0, executor.calls)
    }

    fun testMissingApiKeySkipsLlm() {
        AiRefactoringSettings.getInstance().loadState(
            AiRefactoringSettings.State(baseUrl = "http://localhost", apiKey = "", model = "m")
        )
        myFixture.configureByFile("EndToEnd.java")
        val llm = FakeLlm("ignored")
        val executor = SpyExecutor(com.example.airefactoring.refactor.IntellijRenameExecutor())
        val action = AiRenameSymbolAction(llmFactory = { llm }, executorFactory = { executor })
        action.runForTest(project, myFixture.editor, myFixture.file)
        assertEquals(0, llm.calls)
        assertEquals(0, executor.calls)
    }

    fun testValidationRejectsKeywordWithoutCallingExecutor() {
        configureValidSettings()
        myFixture.configureByFile("EndToEnd.java")
        val llm = FakeLlm("""{"action":"rename_symbol","newName":"class"}""")
        val executor = SpyExecutor(com.example.airefactoring.refactor.IntellijRenameExecutor())
        val action = AiRenameSymbolAction(llmFactory = { llm }, executorFactory = { executor })
        action.runForTest(project, myFixture.editor, myFixture.file)
        assertEquals(1, llm.calls)
        assertEquals(0, executor.calls)
    }
}
