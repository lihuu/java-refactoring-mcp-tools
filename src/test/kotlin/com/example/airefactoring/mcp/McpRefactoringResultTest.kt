package com.example.airefactoring.mcp

import junit.framework.TestCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class McpRefactoringResultTest : TestCase() {
    fun testSuccessContainsOnlySuccessFields() {
        val obj = Json.parseToJsonElement(
            McpRefactoringResult.success(
                projectBasePath = "/home/example",
                filePath = "src/main/java/example/Calc.java",
                methodName = "calculateTotal",
                summary = "Extracted method 'calculateTotal'.",
            ).toJson()
        ).jsonObject

        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("java_extract_method", obj.getValue("operation").jsonPrimitive.content)
        assertEquals("calculateTotal", obj.getValue("methodName").jsonPrimitive.content)
        assertFalse(obj.containsKey("code"))
        assertFalse(obj.containsKey("message"))
    }

    fun testSuccessEchoesProjectBasePath() {
        val obj = Json.parseToJsonElement(
            McpRefactoringResult.success(
                projectBasePath = "/home/example",
                filePath = "src/main/java/example/Calc.java",
                methodName = "calculateTotal",
                summary = "Extracted method 'calculateTotal'.",
            ).toJson()
        ).jsonObject

        assertEquals("/home/example", obj.getValue("projectBasePath").jsonPrimitive.content)
    }

    fun testFailureOmitsProjectBasePath() {
        val obj = Json.parseToJsonElement(
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.INVALID_RANGE,
                "The source range is empty.",
            ).toJson()
        ).jsonObject

        assertFalse(obj.containsKey("projectBasePath"))
    }

    fun testFailureUsesStableCodeAndOmitsSuccessFields() {
        val obj = Json.parseToJsonElement(
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.INVALID_RANGE,
                "The source range is empty.",
            ).toJson()
        ).jsonObject

        assertFalse(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("INVALID_RANGE", obj.getValue("code").jsonPrimitive.content)
        assertFalse(obj.containsKey("methodName"))
        assertFalse(obj.containsKey("summary"))
    }

    fun testEveryFailureCodeSerializesByStableEnumName() {
        McpRefactoringErrorCode.entries.forEach { code ->
            val obj = Json.parseToJsonElement(
                McpRefactoringResult.failure(code, "failure").toJson()
            ).jsonObject
            assertEquals(code.name, obj.getValue("code").jsonPrimitive.content)
        }
    }
}
