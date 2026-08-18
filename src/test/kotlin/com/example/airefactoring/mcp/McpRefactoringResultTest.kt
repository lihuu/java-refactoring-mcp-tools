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
            targetClassQualifiedName = "example.A",
            summary = "Introduced constant 'MONTHS2'.",
        ).toJson()
        val obj = Json.parseToJsonElement(json).jsonObject

        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("java_introduce_constant", obj.getValue("operation").jsonPrimitive.content)
        assertEquals("MONTHS", obj.getValue("requestedFieldName").jsonPrimitive.content)
        assertEquals("MONTHS2", obj.getValue("actualFieldName").jsonPrimitive.content)
        assertEquals("int", obj.getValue("fieldType").jsonPrimitive.content)
        assertEquals("example.A", obj.getValue("targetClassQualifiedName").jsonPrimitive.content)
        assertEquals(
            listOf("private", "static", "final"),
            obj.getValue("fieldModifiers").jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse(
            "Constant success omits initializationPlace (Field-only metadata)",
            obj.containsKey("initializationPlace"),
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
                targetClassQualifiedName = "example.A",
                summary = "Introduced field 'result'.",
            ).toJson()
        ).jsonObject

        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("java_introduce_field", obj.getValue("operation").jsonPrimitive.content)
        assertEquals("result", obj.getValue("requestedFieldName").jsonPrimitive.content)
        assertEquals("result", obj.getValue("actualFieldName").jsonPrimitive.content)
        assertEquals("int", obj.getValue("fieldType").jsonPrimitive.content)
        assertEquals("example.A", obj.getValue("targetClassQualifiedName").jsonPrimitive.content)
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

    fun testIntroduceParameterSuccessContainsSourceKindAndParameterFields() {
        val obj = Json.parseToJsonElement(
            McpRefactoringResult.introduceParameterSuccess(
                projectBasePath = "/project",
                filePath = "src/IpOpService.java",
                methodName = "opPrice",
                parameterName = "multiplier",
                parameterType = "int",
                parameterPosition = 2,
                sourceKind = "EXPRESSION",
                updatedCallSiteCount = 2,
                affectedFiles = listOf("IpOpCallerOne.java", "IpOpCallerTwo.java", "IpOpService.java"),
                summary = "Introduced parameter 'multiplier' to 'opPrice' and updated 2 call site(s).",
            ).toJson()
        ).jsonObject

        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("java_introduce_parameter", obj.getValue("operation").jsonPrimitive.content)
        assertEquals("/project", obj.getValue("projectBasePath").jsonPrimitive.content)
        assertEquals("src/IpOpService.java", obj.getValue("filePath").jsonPrimitive.content)
        assertEquals("opPrice", obj.getValue("methodName").jsonPrimitive.content)
        assertEquals("multiplier", obj.getValue("parameterName").jsonPrimitive.content)
        assertEquals("int", obj.getValue("parameterType").jsonPrimitive.content)
        assertEquals(2, obj.getValue("parameterPosition").jsonPrimitive.int)
        assertEquals("EXPRESSION", obj.getValue("sourceKind").jsonPrimitive.content)
        assertEquals(2, obj.getValue("updatedCallSiteCount").jsonPrimitive.int)
        assertEquals(
            listOf("IpOpCallerOne.java", "IpOpCallerTwo.java", "IpOpService.java"),
            obj.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            "Introduced parameter 'multiplier' to 'opPrice' and updated 2 call site(s).",
            obj.getValue("summary").jsonPrimitive.content,
        )
        assertFalse(obj.containsKey("code"))
        assertFalse(
            "the introduce-parameter envelope must not carry Change Signature metadata",
            obj.containsKey("defaultCallSiteExpression"),
        )
    }

    fun testIntroduceParameterSuccessAcceptsLocalVariableSourceKind() {
        val obj = Json.parseToJsonElement(
            McpRefactoringResult.introduceParameterSuccess(
                projectBasePath = "/project",
                filePath = "src/IpOpLocal.java",
                methodName = "opPrice",
                parameterName = "doubled",
                parameterType = "int",
                parameterPosition = 2,
                sourceKind = "LOCAL_VARIABLE",
                updatedCallSiteCount = 0,
                affectedFiles = listOf("IpOpLocal.java"),
                summary = "Introduced parameter 'doubled' to 'opPrice' and updated 0 call site(s).",
            ).toJson()
        ).jsonObject

        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("LOCAL_VARIABLE", obj.getValue("sourceKind").jsonPrimitive.content)
        assertEquals(0, obj.getValue("updatedCallSiteCount").jsonPrimitive.int)
        assertEquals(
            listOf("IpOpLocal.java"),
            obj.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    fun testSuccessShapesOtherThanIntroduceParameterOmitSourceKind() {
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
            Json.parseToJsonElement(
                McpRefactoringResult.introduceConstantSuccess(
                    projectBasePath = "/p",
                    filePath = "A.java",
                    requestedFieldName = "C",
                    actualFieldName = "C1",
                    fieldType = "int",
                    targetClassQualifiedName = "example.A",
                    summary = "s",
                ).toJson()
            ).jsonObject,
            Json.parseToJsonElement(
                McpRefactoringResult.introduceFieldSuccess(
                    projectBasePath = "/p",
                    filePath = "A.java",
                    requestedFieldName = "f",
                    actualFieldName = "f",
                    fieldType = "int",
                    targetClassQualifiedName = "example.A",
                    summary = "s",
                ).toJson()
            ).jsonObject,
        )
        shapes.forEach { obj ->
            assertFalse(
                "sourceKind must be populated only by the introduce-parameter envelope",
                obj.containsKey("sourceKind"),
            )
        }
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

    fun testSafeDeleteSuccessContainsTargetDescriptionAndNativeUsageCount() {
        val obj = Json.parseToJsonElement(
            McpRefactoringResult.safeDeleteSuccess(
                projectBasePath = "/project",
                filePath = "src/main/java/example/Service.java",
                targetDescription = "method obsolete()",
                nativeUsageCount = 0,
                summary = "Deleted method 'obsolete()'.",
            ).toJson()
        ).jsonObject

        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("java_safe_delete", obj.getValue("operation").jsonPrimitive.content)
        assertEquals("/project", obj.getValue("projectBasePath").jsonPrimitive.content)
        assertEquals("src/main/java/example/Service.java", obj.getValue("filePath").jsonPrimitive.content)
        assertEquals("method obsolete()", obj.getValue("targetDescription").jsonPrimitive.content)
        assertEquals(0, obj.getValue("nativeUsageCount").jsonPrimitive.int)
        assertEquals("Deleted method 'obsolete()'.", obj.getValue("summary").jsonPrimitive.content)
        assertFalse(obj.containsKey("code"))
    }

    fun testMoveInstanceMethodSuccessContainsMoveFields() {
        val obj = Json.parseToJsonElement(
            McpRefactoringResult.moveInstanceMethodSuccess(
                projectBasePath = "/project",
                filePath = "src/main/java/example/Order.java",
                methodName = "applyDiscount",
                targetDescription = "parameter customer of type example.Customer",
                targetClassQualifiedName = "example.Customer",
                newVisibility = "public",
                updatedCallSiteCount = 2,
                summary = "Moved 'applyDiscount' to 'example.Customer' as 'public'.",
            ).toJson()
        ).jsonObject

        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals(
            "java_move_instance_method",
            obj.getValue("operation").jsonPrimitive.content,
        )
        assertEquals("/project", obj.getValue("projectBasePath").jsonPrimitive.content)
        assertEquals(
            "src/main/java/example/Order.java",
            obj.getValue("filePath").jsonPrimitive.content,
        )
        assertEquals("applyDiscount", obj.getValue("methodName").jsonPrimitive.content)
        assertEquals(
            "parameter customer of type example.Customer",
            obj.getValue("targetDescription").jsonPrimitive.content,
        )
        assertEquals(
            "example.Customer",
            obj.getValue("targetClassQualifiedName").jsonPrimitive.content,
        )
        assertEquals("public", obj.getValue("newVisibility").jsonPrimitive.content)
        assertEquals(2, obj.getValue("updatedCallSiteCount").jsonPrimitive.int)
        assertFalse(obj.containsKey("code"))
    }

    fun testMoveInstanceMethodSuccessOmitsFieldMemberMetadata() {
        val obj = Json.parseToJsonElement(
            McpRefactoringResult.moveInstanceMethodSuccess(
                projectBasePath = "/project",
                filePath = "src/A.java",
                methodName = "applyDiscount",
                targetDescription = "parameter customer of type example.Customer",
                targetClassQualifiedName = "example.Customer",
                newVisibility = "protected",
                updatedCallSiteCount = 0,
                summary = "Moved 'applyDiscount'.",
            ).toJson()
        ).jsonObject

        assertFalse(obj.containsKey("requestedFieldName"))
        assertFalse(obj.containsKey("actualFieldName"))
        assertFalse(obj.containsKey("fieldType"))
        assertFalse(obj.containsKey("fieldModifiers"))
        assertFalse(obj.containsKey("initializationPlace"))
    }

    fun testInvalidVisibilityErrorCodeSerializesByStableName() {
        val obj = Json.parseToJsonElement(
            McpRefactoringResult.failure(
                McpRefactoringErrorCode.INVALID_VISIBILITY,
                "Visibility must be public, protected, private, or package-local.",
            ).toJson()
        ).jsonObject

        assertFalse(obj.getValue("ok").jsonPrimitive.boolean)
        assertEquals("INVALID_VISIBILITY", obj.getValue("code").jsonPrimitive.content)
    }

    private fun assertMemberFieldsAbsent(keys: Set<String>) {
        assertFalse(keys.contains("requestedFieldName"))
        assertFalse(keys.contains("actualFieldName"))
        assertFalse(keys.contains("fieldType"))
        assertFalse(keys.contains("fieldModifiers"))
        assertFalse(keys.contains("targetClassQualifiedName"))
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
