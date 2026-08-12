package com.example.airefactoring.validator

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiNameHelper
import javax.lang.model.SourceVersion

class NameValidator {

    fun validateVariableName(newName: String, project: Project?): ValidationResult {
        if (newName.isEmpty()) return ValidationResult.Invalid("Variable name must not be empty.")
        if (newName != newName.trim()) {
            return ValidationResult.Invalid("Variable name must not contain surrounding whitespace.")
        }
        val identifierOk = if (project != null) {
            PsiNameHelper.getInstance(project).isIdentifier(newName)
        } else {
            SourceVersion.isIdentifier(newName) && !SourceVersion.isKeyword(newName)
        }
        return if (identifierOk) {
            ValidationResult.Ok
        } else {
            ValidationResult.Invalid("'$newName' is not a valid Java identifier.")
        }
    }

    fun validateMethodName(newName: String, project: Project?): ValidationResult {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return ValidationResult.Invalid("Method name must not be empty.")
        if (trimmed in JAVA_KEYWORDS) {
            return ValidationResult.Invalid("'$trimmed' is a Java keyword.")
        }
        val identifierOk = if (project != null) {
            PsiNameHelper.getInstance(project).isIdentifier(trimmed)
        } else {
            isPlainJavaIdentifier(trimmed)
        }
        if (!identifierOk) {
            return ValidationResult.Invalid("'$trimmed' is not a valid Java identifier.")
        }
        if (!trimmed[0].isLowerCase()) {
            return ValidationResult.Invalid("'$trimmed' should use lowerCamelCase.")
        }
        return ValidationResult.Ok
    }

    private fun isPlainJavaIdentifier(s: String): Boolean {
        if (s.isEmpty()) return false
        if (!Character.isJavaIdentifierStart(s[0])) return false
        for (i in 1 until s.length) {
            if (!Character.isJavaIdentifierPart(s[i])) return false
        }
        return true
    }

    companion object {
        private val JAVA_KEYWORDS = setOf(
            "abstract","assert","boolean","break","byte","case","catch","char","class","const",
            "continue","default","do","double","else","enum","extends","final","finally","float",
            "for","goto","if","implements","import","instanceof","int","interface","long","native",
            "new","package","private","protected","public","return","short","static","strictfp",
            "super","switch","synchronized","this","throw","throws","transient","try","void",
            "volatile","while","true","false","null","var","yield","record","sealed","permits","non-sealed",
        )
    }
}
