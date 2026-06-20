package com.example.airefactoring.validator

import com.example.airefactoring.resolver.SymbolKind
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiNameHelper

class NameValidator {

    fun validate(
        newName: String,
        kind: SymbolKind,
        currentName: String,
        project: Project?,
    ): ValidationResult {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return ValidationResult.Invalid("New name must not be empty.")
        if (trimmed == currentName) return ValidationResult.Invalid("New name is the same as the current name.")
        if (trimmed in JAVA_KEYWORDS) return ValidationResult.Invalid("'$trimmed' is a Java keyword.")

        val identifierOk = if (project != null) {
            PsiNameHelper.getInstance(project).isIdentifier(trimmed)
        } else {
            isPlainJavaIdentifier(trimmed)
        }
        if (!identifierOk) return ValidationResult.Invalid("'$trimmed' is not a valid Java identifier.")

        when (kind) {
            // Methods share the same lowerCamelCase rule as local variables and fields.
            SymbolKind.LOCAL_VARIABLE, SymbolKind.FIELD, SymbolKind.METHOD -> {
                if (!trimmed[0].isLowerCase()) {
                    return ValidationResult.Invalid("Local variables and fields should use lowerCamelCase.")
                }
            }
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
