package com.example.airefactoring.mcp

import junit.framework.TestCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class McpRefactoringResultTest : TestCase() {
    fun testInlineVariableSuccessContainsVariableAndOccurrenceFields() {
        val obj = Json.parseToJsonElement(
            McpRefactoringResult.inlineVariableSuccess(
                projectBasePath = "/project",
                filePath = "src/main/java/example/Price.java",
                variableName = "subtotal",
                inlinedOccurrenceCount = 2,
                summary = "Inlined 2 occurrences of local variable 'subtotal' and removed its declaration.",
            ).toJson(),
        ).jsonObject

        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("java_inline_variable", obj.getValue("operation").jsonPrimitive.content)
        assertEquals("subtotal", obj.getValue("variableName").jsonPrimitive.content)
        assertEquals(2, obj.getValue("inlinedOccurrenceCount").jsonPrimitive.int)
        assertFalse(obj.containsKey("requestedVariableName"))
        assertFalse(obj.containsKey("parameterName"))
        assertFalse(obj.containsKey("code"))
    }

    fun testIntroduceVariableSuccessContainsRequestedActualAndTypeFields() {
        val obj = Json.parseToJsonElement(
            McpRefactoringResult.introduceVariableSuccess(
                projectBasePath = "/project",
                filePath = "src/main/java/example/Calculator.java",
                requestedVariableName = "totalPrice",
                actualVariableName = "totalPrice1",
                variableType = "java.math.BigDecimal",
                summary = "Introduced local variable 'totalPrice1'.",
            ).toJson()
        ).jsonObject

        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("java_introduce_variable", obj.getValue("operation").jsonPrimitive.content)
        assertEquals("totalPrice", obj.getValue("requestedVariableName").jsonPrimitive.content)
        assertEquals("totalPrice1", obj.getValue("actualVariableName").jsonPrimitive.content)
        assertEquals("java.math.BigDecimal", obj.getValue("variableType").jsonPrimitive.content)
        assertFalse(obj.containsKey("methodName"))
        assertFalse(obj.containsKey("code"))
        assertChangeSignatureFieldsAbsent(obj.keys)
        assertInlineVariableFieldsAbsent(obj.keys)
    }

    fun testExtractMethodSuccessOmitsIntroduceVariableFields() {
        val obj = Json.parseToJsonElement(
            McpRefactoringResult.extractMethodSuccess(
                projectBasePath = "/project",
                filePath = "Calc.java",
                methodName = "calculateTotal",
                summary = "Extracted method 'calculateTotal'.",
            ).toJson()
        ).jsonObject

        assertFalse(obj.containsKey("requestedVariableName"))
        assertFalse(obj.containsKey("actualVariableName"))
        assertFalse(obj.containsKey("variableType"))
        assertChangeSignatureFieldsAbsent(obj.keys)
        assertInlineVariableFieldsAbsent(obj.keys)
    }

    fun testChangeSignatureSuccessContainsParameterAndAffectedUsageFields() {
        val obj = Json.parseToJsonElement(
            McpRefactoringResult.changeSignatureAddParameterSuccess(
                projectBasePath = "/project",
                filePath = "src/main/java/example/GreetingService.java",
                methodName = "greet",
                parameterName = "punctuation",
                parameterType = "java.lang.String",
                parameterPosition = 2,
                defaultCallSiteExpression = "\"!\"",
                updatedCallSiteCount = 2,
                affectedFiles = listOf("Caller.java", "GreetingService.java"),
                summary = "Added parameter 'punctuation' at position 2 and updated 2 call sites.",
            ).toJson(),
        ).jsonObject

        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals(
            "java_change_signature_add_parameter",
            obj.getValue("operation").jsonPrimitive.content,
        )
        assertEquals("punctuation", obj.getValue("parameterName").jsonPrimitive.content)
        assertEquals("java.lang.String", obj.getValue("parameterType").jsonPrimitive.content)
        assertEquals(2, obj.getValue("parameterPosition").jsonPrimitive.int)
        assertEquals("\"!\"", obj.getValue("defaultCallSiteExpression").jsonPrimitive.content)
        assertEquals(2, obj.getValue("updatedCallSiteCount").jsonPrimitive.int)
        assertEquals(
            listOf("Caller.java", "GreetingService.java"),
            obj.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse(obj.containsKey("requestedVariableName"))
        assertFalse(obj.containsKey("code"))
        assertInlineVariableFieldsAbsent(obj.keys)
    }

    fun testSuccessContainsOnlySuccessFields() {
        val obj = Json.parseToJsonElement(
            McpRefactoringResult.extractMethodSuccess(
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
            McpRefactoringResult.extractMethodSuccess(
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

    fun testIntroduceConstantSuccessContainsFixedMemberMetadata() {
        val json = McpRefactoringResult.introduceConstantSuccess(
            projectBasePath = "/project",
            filePath = "src/A.java",
            requestedFieldName = "MONTHS",
            actualFieldName = "MONTHS2",
            fieldType = "int",
            summary = "Introduced constant 'MONTHS2'.",
        ).toJson()
        val obj = Json.parseToJsonElement(json).jsonObject

        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("java_introduce_constant", obj.getValue("operation").jsonPrimitive.content)
        assertEquals("MONTHS", obj.getValue("requestedFieldName").jsonPrimitive.content)
        assertEquals("MONTHS2", obj.getValue("actualFieldName").jsonPrimitive.content)
        assertEquals("int", obj.getValue("fieldType").jsonPrimitive.content)
        assertEquals(
            listOf("private", "static", "final"),
            obj.getValue("fieldModifiers").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            "FIELD_DECLARATION",
            obj.getValue("initializationPlace").jsonPrimitive.content,
        )
        assertEquals("/project", obj.getValue("projectBasePath").jsonPrimitive.content)
        assertFalse(obj.containsKey("code"))
    }

    fun testIntroduceFieldSuccessContainsDeclarationInitializationMetadata() {
        val obj = Json.parseToJsonElement(
            McpRefactoringResult.introduceFieldSuccess(
                projectBasePath = "/project",
                filePath = "src/A.java",
                requestedFieldName = "result",
                actualFieldName = "result",
                fieldType = "int",
                summary = "Introduced field 'result'.",
            ).toJson()
        ).jsonObject

        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("java_introduce_field", obj.getValue("operation").jsonPrimitive.content)
        assertEquals("result", obj.getValue("requestedFieldName").jsonPrimitive.content)
        assertEquals("result", obj.getValue("actualFieldName").jsonPrimitive.content)
        assertEquals("int", obj.getValue("fieldType").jsonPrimitive.content)
        assertEquals(
            listOf("private", "final"),
            obj.getValue("fieldModifiers").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            "FIELD_DECLARATION",
            obj.getValue("initializationPlace").jsonPrimitive.content,
        )
        assertFalse(obj.containsKey("methodName"))
        assertFalse(obj.containsKey("code"))
    }

    fun testExistingSuccessShapesOmitAllNewMemberFields() {
        val shapes = listOf(
            Json.parseToJsonElement(
                McpRefactoringResult.extractMethodSuccess(
                    projectBasePath = "/p",
                    filePath = "A.java",
                    methodName = "m",
                    summary = "s",
                ).toJson()
            ).jsonObject,
            Json.parseToJsonElement(
                McpRefactoringResult.introduceVariableSuccess(
                    projectBasePath = "/p",
                    filePath = "A.java",
                    requestedVariableName = "v",
                    actualVariableName = "v1",
                    variableType = "int",
                    summary = "s",
                ).toJson()
            ).jsonObject,
            Json.parseToJsonElement(
                McpRefactoringResult.inlineVariableSuccess(
                    projectBasePath = "/p",
                    filePath = "A.java",
                    variableName = "v",
                    inlinedOccurrenceCount = 1,
                    summary = "s",
                ).toJson()
            ).jsonObject,
            Json.parseToJsonElement(
                McpRefactoringResult.changeSignatureAddParameterSuccess(
                    projectBasePath = "/p",
                    filePath = "A.java",
                    methodName = "m",
                    parameterName = "p",
                    parameterType = "int",
                    parameterPosition = 1,
                    defaultCallSiteExpression = "\"!\"",
                    updatedCallSiteCount = 2,
                    affectedFiles = listOf("A.java"),
                    summary = "s",
                ).toJson()
            ).jsonObject,
        )
        shapes.forEach { obj -> assertMemberFieldsAbsent(obj.keys) }
    }

    private fun assertMemberFieldsAbsent(keys: Set<String>) {
        assertFalse(keys.contains("requestedFieldName"))
        assertFalse(keys.contains("actualFieldName"))
        assertFalse(keys.contains("fieldType"))
        assertFalse(keys.contains("fieldModifiers"))
        assertFalse(keys.contains("initializationPlace"))
    }

    private fun assertChangeSignatureFieldsAbsent(keys: Set<String>) {
        assertFalse(keys.contains("parameterName"))
        assertFalse(keys.contains("parameterType"))
        assertFalse(keys.contains("parameterPosition"))
        assertFalse(keys.contains("defaultCallSiteExpression"))
        assertFalse(keys.contains("updatedCallSiteCount"))
        assertFalse(keys.contains("affectedFiles"))
    }

    private fun assertInlineVariableFieldsAbsent(keys: Set<String>) {
        assertFalse(keys.contains("variableName"))
        assertFalse(keys.contains("inlinedOccurrenceCount"))
    }
}
