package com.example.airefactoring.validator

import com.example.airefactoring.resolver.SymbolKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameValidatorTest {
    private val v = NameValidator()

    @Test fun acceptsLowerCamelCaseLocalVariable() {
        assertEquals(ValidationResult.Ok,
            v.validate("userRepository", SymbolKind.LOCAL_VARIABLE, "u", project = null))
    }

    @Test fun acceptsLowerCamelCaseField() {
        assertEquals(ValidationResult.Ok,
            v.validate("userName", SymbolKind.FIELD, "name", project = null))
    }

    @Test fun rejectsEmpty() {
        assertTrue(v.validate("", SymbolKind.FIELD, "x", null) is ValidationResult.Invalid)
    }

    @Test fun rejectsBlank() {
        assertTrue(v.validate("   ", SymbolKind.FIELD, "x", null) is ValidationResult.Invalid)
    }

    @Test fun rejectsJavaKeyword() {
        assertTrue(v.validate("class", SymbolKind.LOCAL_VARIABLE, "c", null) is ValidationResult.Invalid)
        assertTrue(v.validate("return", SymbolKind.FIELD, "r", null) is ValidationResult.Invalid)
    }

    @Test fun rejectsInvalidIdentifier() {
        assertTrue(v.validate("123name", SymbolKind.FIELD, "n", null) is ValidationResult.Invalid)
        assertTrue(v.validate("user-name", SymbolKind.FIELD, "n", null) is ValidationResult.Invalid)
    }

    @Test fun rejectsSameName() {
        assertTrue(v.validate("name", SymbolKind.FIELD, "name", null) is ValidationResult.Invalid)
    }

    @Test fun rejectsUpperCamelForLocalOrField() {
        assertTrue(v.validate("UserRepository", SymbolKind.LOCAL_VARIABLE, "u", null)
            is ValidationResult.Invalid)
        assertTrue(v.validate("UserName", SymbolKind.FIELD, "n", null)
            is ValidationResult.Invalid)
    }
}
