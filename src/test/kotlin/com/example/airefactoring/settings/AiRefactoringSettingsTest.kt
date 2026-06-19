package com.example.airefactoring.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AiRefactoringSettingsTest : BasePlatformTestCase() {
    fun testRoundTripsState() {
        val service = AiRefactoringSettings.getInstance()
        val saved = AiRefactoringSettings.State(
            baseUrl = "https://example.test",
            apiKey = "secret",
            model = "gpt-4o-mini",
            enablePreview = false,
        )
        service.loadState(saved)
        val back = service.state
        assertEquals("https://example.test", back.baseUrl)
        assertEquals("secret", back.apiKey)
        assertEquals("gpt-4o-mini", back.model)
        assertFalse(back.enablePreview)
    }
}
