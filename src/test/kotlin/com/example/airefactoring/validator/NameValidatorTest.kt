package com.example.airefactoring.validator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameValidatorTest {
    private val v = NameValidator()

    @Test fun acceptsLowerCamelCaseMethodName() {
        assertEquals(ValidationResult.Ok, v.validateMethodName("calculateTotal", project = null))
    }

    @Test fun rejectsEmpty() {
        assertTrue(v.validateMethodName("", null) is ValidationResult.Invalid)
    }

    @Test fun rejectsBlank() {
        assertTrue(v.validateMethodName("   ", null) is ValidationResult.Invalid)
    }

    @Test fun rejectsJavaKeyword() {
        assertTrue(v.validateMethodName("class", null) is ValidationResult.Invalid)
        assertTrue(v.validateMethodName("return", null) is ValidationResult.Invalid)
    }

    @Test fun rejectsInvalidIdentifier() {
        assertTrue(v.validateMethodName("123name", null) is ValidationResult.Invalid)
        assertTrue(v.validateMethodName("user-name", null) is ValidationResult.Invalid)
    }

    @Test fun rejectsUpperCamelMethodName() {
        assertTrue(v.validateMethodName("CalculateTotal", null) is ValidationResult.Invalid)
    }

    @Test fun acceptsLegalVariableIdentifierWithoutMethodCaseRule() {
        assertEquals(ValidationResult.Ok, v.validateVariableName("totalPrice", null))
        assertEquals(ValidationResult.Ok, v.validateVariableName("URL", null))
        assertEquals(ValidationResult.Ok, v.validateVariableName("var", null))
    }

    @Test fun rejectsBlankPaddedInvalidAndKeywordVariableNamesWithoutTrimming() {
        listOf("", "   ", " totalPrice ", "total-price", "123total", "class", "_")
            .forEach { name ->
                assertTrue(
                    "expected invalid: '$name'",
                    v.validateVariableName(name, null) is ValidationResult.Invalid,
                )
            }
    }
}
