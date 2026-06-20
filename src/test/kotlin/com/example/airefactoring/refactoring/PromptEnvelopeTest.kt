package com.example.airefactoring.refactoring

import com.example.airefactoring.refactoring.rename.RenameSymbolHandler
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/**
 * Ports the prompt safety + content assertions from the retired prompt-builder test, now exercising
 * the real [PromptEnvelope.assemble] against the real [RenameSymbolHandler] contribution. Uses a
 * platform fixture to build a genuine [RefactorTarget] from a Java local variable under the caret.
 */
class PromptEnvelopeTest : LightJavaCodeInsightFixtureTestCase() {
    override fun getTestDataPath(): String = "src/test/testData/resolver"

    private val handler = RenameSymbolHandler()

    private fun assemble(): Pair<String, String> {
        myFixture.configureByFile("LocalVar.java")
        val target = handler.resolve(myFixture.file, myFixture.editor.caretModel.offset)!!
        return PromptEnvelope.assemble(handler.promptContribution(target), target)
    }

    fun testSystemPromptStatesPlannerRoleAndForbidsCodeGeneration() {
        val (system, _) = assemble()
        assertTrue(system.contains("Java refactoring planner"))
        assertTrue(system.lowercase().contains("must not edit code"))
        assertTrue(system.lowercase().contains("must not output java"))
        assertTrue(system.contains("Return only JSON"))
    }

    fun testSystemPromptListsAllowedCommandsOnly() {
        val (system, _) = assemble()
        assertTrue(system.contains("rename_symbol"))
        assertTrue(system.contains("no_action"))
    }

    fun testSystemPromptIncludesJavaNamingGuidance() {
        val (system, _) = assemble()
        assertTrue(system.lowercase().contains("camel"))
    }

    fun testUserPromptIncludesContextFields() {
        val (_, user) = assemble()
        assertTrue(user.contains("userCount"))
        assertTrue(user.contains("LOCAL_VARIABLE"))
        assertTrue(user.contains("\"int\""))
        assertTrue(user.contains("LocalVar"))
    }

    fun testSystemPromptDoesNotInstructFilesystemAccess() {
        val (system, user) = assemble()
        val combined = (system + user).lowercase()
        assertFalse(combined.contains("read the file"))
        assertFalse(combined.contains("filesystem"))
        assertFalse(combined.contains("shell"))
    }
}
