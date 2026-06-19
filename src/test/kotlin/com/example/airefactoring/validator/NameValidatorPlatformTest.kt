package com.example.airefactoring.validator

import com.example.airefactoring.resolver.SymbolKind
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class NameValidatorPlatformTest : BasePlatformTestCase() {
    private val v = NameValidator()

    fun testProjectAwarePsiNameHelperAcceptsValid() {
        val r = v.validate("userCount", SymbolKind.LOCAL_VARIABLE, "u", project)
        assertEquals(ValidationResult.Ok, r)
    }

    fun testProjectAwarePsiNameHelperRejectsKeyword() {
        val r = v.validate("class", SymbolKind.FIELD, "c", project)
        assertTrue(r is ValidationResult.Invalid)
    }
}
