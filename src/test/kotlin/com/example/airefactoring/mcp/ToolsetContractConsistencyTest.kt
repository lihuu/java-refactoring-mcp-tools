package com.example.airefactoring.mcp

import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import junit.framework.TestCase
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.valueParameters

class ToolsetContractConsistencyTest : TestCase() {

    fun testEveryOptionalParameterIsNullableAndViceVersa() {
        val violations = mutableListOf<String>()
        for (function in JavaRefactorToolset::class.memberFunctions) {
            if (function.findAnnotation<McpTool>() == null) continue
            for (parameter in function.valueParameters) {
                val nullable = parameter.type.isMarkedNullable
                val optional = parameter.isOptional
                // Dangerous combo: schema-advertised-optional nullable type without a Kotlin
                // default forces callers to send an explicit JSON null, which the argument
                // binder rejects with a cryptic error. Non-null-with-default is fine.
                if (nullable && !optional) {
                    violations += "${function.name}#${parameter.name}: nullable=$nullable optional=$optional"
                }
            }
        }
        assertTrue(
            "nullable<->optional contract violated:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    fun testRequiredParametersNeverAdvertiseNull() {
        val violations = mutableListOf<String>()
        for (function in JavaRefactorToolset::class.memberFunctions) {
            if (function.findAnnotation<McpTool>() == null) continue
            for (parameter in function.valueParameters) {
                if (parameter.isOptional) continue
                val description = parameter.findAnnotation<McpDescription>()?.description ?: ""
                if (description.contains("null") && !description.startsWith("Required")) {
                    violations += "${function.name}#${parameter.name}: required param description mentions null without leading 'Required': \"$description\""
                }
                val needsLeadingRequired =
                    !parameter.type.isMarkedNullable && parameter.name!!.endsWith("Visibility")
                if (needsLeadingRequired && !description.startsWith("Required")) {
                    violations += "${function.name}#${parameter.name}: visibility-like required param must lead with 'Required': \"$description\""
                }
            }
        }
        assertTrue(
            "required parameters must not advertise null:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }
}
