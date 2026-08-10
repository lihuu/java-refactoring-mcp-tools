package com.example.airefactoring.validator

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class NameValidatorPlatformTest : BasePlatformTestCase() {
    private val v = NameValidator()

    fun testProjectAwarePsiNameHelperAcceptsValid() {
        val r = v.validateMethodName("calculateTotal", project)
        assertEquals(ValidationResult.Ok, r)
    }

    fun testProjectAwarePsiNameHelperRejectsKeyword() {
        val r = v.validateMethodName("class", project)
        assertTrue(r is ValidationResult.Invalid)
    }
}
