package com.example.airefactoring.validator

sealed class ValidationResult {
    object Ok : ValidationResult()
    data class Invalid(val message: String) : ValidationResult()
}
