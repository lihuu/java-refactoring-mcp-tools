package com.example.airefactoring.action

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AiRenameSymbolActionRegistrationTest : BasePlatformTestCase() {
    fun testActionIsRegistered() {
        val action = ActionManager.getInstance().getAction("com.example.airefactoring.AiRenameSymbol")
        assertNotNull("AiRenameSymbol action should be registered in plugin.xml", action)
        assertTrue(action is AiRenameSymbolAction)
    }
}
