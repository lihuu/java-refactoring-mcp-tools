package com.example.airefactoring.prompt

import com.example.airefactoring.context.RefactorContext
import com.example.airefactoring.resolver.SymbolKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    private fun ctx(
        name: String = "userCount",
        kind: SymbolKind = SymbolKind.LOCAL_VARIABLE,
        type: String? = "int",
    ) = RefactorContext(
        language = "java",
        filePath = "/Demo.java",
        symbolName = name,
        symbolKind = kind,
        enclosingClass = "Demo",
        enclosingMethod = "run",
        symbolType = type,
        nearbyCode = "int $name = 0; $name++;",
    )

    @Test fun systemPromptStatesPlannerRoleAndForbidsCodeGeneration() {
        val (system, _) = PromptBuilder.build(ctx())
        assertTrue(system.contains("Java refactoring planner"))
        assertTrue(system.lowercase().contains("must not edit code"))
        assertTrue(system.lowercase().contains("must not output java"))
        assertTrue(system.contains("Return only JSON"))
    }

    @Test fun systemPromptListsAllowedCommandsOnly() {
        val (system, _) = PromptBuilder.build(ctx())
        assertTrue(system.contains("rename_symbol"))
        assertTrue(system.contains("no_action"))
    }

    @Test fun systemPromptIncludesJavaNamingGuidance() {
        val (system, _) = PromptBuilder.build(ctx())
        assertTrue(system.lowercase().contains("camel"))
    }

    @Test fun userPromptIncludesContextFields() {
        val (_, user) = PromptBuilder.build(ctx())
        assertTrue(user.contains("userCount"))
        assertTrue(user.contains("LOCAL_VARIABLE"))
        assertTrue(user.contains("\"int\""))
        assertTrue(user.contains("Demo"))
    }

    @Test fun systemPromptDoesNotInstructFilesystemAccess() {
        val (system, user) = PromptBuilder.build(ctx())
        val combined = (system + user).lowercase()
        assertFalse(combined.contains("read the file"))
        assertFalse(combined.contains("filesystem"))
        assertFalse(combined.contains("shell"))
    }
}
