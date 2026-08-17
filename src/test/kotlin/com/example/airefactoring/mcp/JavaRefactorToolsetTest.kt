package com.example.airefactoring.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.impl.ReflectionToolsProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class JavaRefactorToolsetTest : BasePlatformTestCase() {

    fun testExactlyOneJavaRefactorToolsetIsRegistered() {
        assertEquals(1, McpToolset.EP.extensionList.count { it is JavaRefactorToolset })
    }

    fun testExtractMethodDescriptorPreservesSafetyContract() {
        val descriptor = ReflectionToolsProvider().getTools()
            .map { it.descriptor }
            .single { it.name == "java_extract_method" }

        assertTrue(
            descriptor.description.contains(
                "Never implement Extract Method through direct text edits",
            ),
        )
    }

    fun testExtractMethodSchemaRemainsCompatible() {
        val descriptor = ReflectionToolsProvider().getTools()
            .map { it.descriptor }
            .single { it.name == "java_extract_method" }

        assertEquals(
            setOf(
                "pathInProject",
                "startLine",
                "startColumn",
                "endLine",
                "endColumn",
                "methodName",
                "projectPath",
            ),
            descriptor.inputSchema.propertiesSchema.keys,
        )
        assertFalse(
            "targetClassQualifiedName must remain optional",
            "targetClassQualifiedName" in descriptor.inputSchema.requiredProperties,
        )
    }

    fun testAllPluginToolsAreExposedFromOneToolset() {
        val names = ReflectionToolsProvider().getTools()
            .map { it.descriptor.name }
            .toSet()

        assertTrue("java_extract_method missing", "java_extract_method" in names)
        assertTrue("java_introduce_variable missing", "java_introduce_variable" in names)
        assertTrue(
            "java_change_signature_add_parameter missing",
            "java_change_signature_add_parameter" in names,
        )
        assertTrue("java_inline_variable missing", "java_inline_variable" in names)
        assertTrue("java_introduce_constant missing", "java_introduce_constant" in names)
        assertTrue("java_introduce_field missing", "java_introduce_field" in names)
        assertTrue("java_introduce_parameter missing", "java_introduce_parameter" in names)
        assertTrue("java_safe_delete missing", "java_safe_delete" in names)
        assertEquals(1, McpToolset.EP.extensionList.count { it is JavaRefactorToolset })
    }

    fun testIntroduceParameterSchemaContainsExactlySixDeclaredArguments() {
        val descriptor = ReflectionToolsProvider().getTools()
            .map { it.descriptor }
            .single { it.name == "java_introduce_parameter" }

        assertEquals(
            setOf(
                "pathInProject",
                "startLine",
                "startColumn",
                "endLine",
                "endColumn",
                "parameterName",
                "projectPath",
            ),
            descriptor.inputSchema.propertiesSchema.keys,
        )
    }

    fun testIntroduceParameterDescriptionStatesAgentAndSafetyContract() {
        val description = ReflectionToolsProvider().getTools()
            .map { it.descriptor }
            .single { it.name == "java_introduce_parameter" }
            .description

        assertTrue(description.contains("derived natively from the selected source"))
        assertTrue(description.contains("not the general Change Signature"))
        assertTrue(description.contains("waiting for user approval"))
        assertTrue(description.contains("affectedFiles"))
        assertTrue(description.contains("Never use direct text edits"))
        assertTrue(description.contains("native"))
        assertTrue(description.contains("start inclusive, end exclusive"))
        assertFalse(
            "the introduce-parameter description must not route the agent to the Change Signature tool",
            description.contains("java_change_signature"),
        )
    }

    fun testIntroduceConstantSchemaContainsOptionalTargetClassArgument() {
        val descriptor = ReflectionToolsProvider().getTools()
            .map { it.descriptor }
            .single { it.name == "java_introduce_constant" }

        assertEquals(
            setOf(
                "pathInProject",
                "startLine",
                "startColumn",
                "endLine",
                "endColumn",
                "preferredName",
                "targetClassQualifiedName",
                "projectPath",
            ),
            descriptor.inputSchema.propertiesSchema.keys,
        )
        assertFalse(
            "targetClassQualifiedName must remain optional",
            "targetClassQualifiedName" in descriptor.inputSchema.requiredProperties,
        )
    }

    fun testIntroduceConstantDescriptionStatesAgentAndSafetyContract() {
        val description = ReflectionToolsProvider().getTools()
            .map { it.descriptor }
            .single { it.name == "java_introduce_constant" }
            .description

        assertTrue(description.contains("nearest containing class"))
        assertTrue(description.contains("targetClassQualifiedName"))
        assertTrue(description.contains("exact source range"))
        assertTrue(description.contains("selected occurrence"))
        assertTrue(description.contains("private static final"))
        assertTrue(description.contains("native"))
        assertTrue(description.contains("diagnostics, build, and tests"))
        assertTrue(description.contains("Read the current source before supplying the range"))
        assertTrue(description.contains("positions change after every refactoring"))
        assertTrue(description.contains("Never use direct text edits"))
        assertFalse(
            "constant description must not route the agent to the field tool",
            description.contains("java_introduce_field"),
        )
    }

    fun testIntroduceFieldSchemaContainsOptionalTargetClassArgument() {
        val descriptor = ReflectionToolsProvider().getTools()
            .map { it.descriptor }
            .single { it.name == "java_introduce_field" }

        assertEquals(
            setOf(
                "pathInProject",
                "startLine",
                "startColumn",
                "endLine",
                "endColumn",
                "preferredName",
                "targetClassQualifiedName",
                "projectPath",
            ),
            descriptor.inputSchema.propertiesSchema.keys,
        )
    }

    fun testIntroduceFieldDescriptionStatesAgentAndSafetyContract() {
        val description = ReflectionToolsProvider().getTools()
            .map { it.descriptor }
            .single { it.name == "java_introduce_field" }
            .description

        assertTrue(description.contains("nearest containing class"))
        assertTrue(description.contains("targetClassQualifiedName"))
        assertTrue(description.contains("exact source range"))
        assertTrue(description.contains("selected occurrence"))
        assertTrue(description.contains("private final"))
        assertTrue(description.contains("initialized at its declaration"))
        assertTrue(description.contains("native"))
        assertTrue(description.contains("diagnostics, build, and tests"))
        assertTrue(description.contains("Read the current source before supplying the range"))
        assertTrue(description.contains("positions change after every refactoring"))
        assertTrue(description.contains("Never use direct text edits"))
        assertFalse(
            "field description must not route the agent to the constant tool",
            description.contains("java_introduce_constant"),
        )
    }

    fun testInlineVariableSchemaContainsExactlyThreeDeclaredArguments() {
        val descriptor = ReflectionToolsProvider().getTools()
            .map { it.descriptor }
            .single { it.name == "java_inline_variable" }

        assertEquals(
            setOf("pathInProject", "line", "column", "projectPath"),
            descriptor.inputSchema.propertiesSchema.keys,
        )
    }

    fun testInlineVariableDescriptionStatesAgentAndSafetyContract() {
        val description = ReflectionToolsProvider().getTools()
            .map { it.descriptor }
            .single { it.name == "java_inline_variable" }
            .description

        assertTrue(description.contains("all supported read references"))
        assertTrue(description.contains("deletes the declaration"))
        assertTrue(description.contains("waiting for user approval"))
        assertTrue(description.contains("Never use direct text edits"))
    }

    fun testIntroduceVariableSchemaContainsExactlySixDeclaredArguments() {
        val descriptor = ReflectionToolsProvider().getTools()
            .map { it.descriptor }
            .single { it.name == "java_introduce_variable" }

        assertEquals(
            setOf(
                "pathInProject",
                "startLine",
                "startColumn",
                "endLine",
                "endColumn",
                "preferredVariableName",
                "projectPath",
            ),
            descriptor.inputSchema.propertiesSchema.keys,
        )
    }

    fun testIntroduceVariableDescriptionStatesAgentAndSafetyContract() {
        val description = ReflectionToolsProvider().getTools()
            .map { it.descriptor }
            .single { it.name == "java_introduce_variable" }
            .description

        assertTrue(description.contains("one exact Java expression"))
        assertTrue(description.contains("semantic preferred variable name"))
        assertTrue(description.contains("waiting for user approval"))
        assertTrue(description.contains("Never use direct text edits"))
    }

    fun testChangeSignatureSchemaContainsExactlySevenDeclaredArguments() {
        val descriptor = ReflectionToolsProvider().getTools()
            .map { it.descriptor }
            .single { it.name == "java_change_signature_add_parameter" }

        assertEquals(
            setOf(
                "pathInProject",
                "line",
                "column",
                "parameterName",
                "parameterType",
                "parameterPosition",
                "defaultCallSiteExpression",
                "projectPath",
            ),
            descriptor.inputSchema.propertiesSchema.keys,
        )
    }

    fun testChangeSignatureDescriptionStatesAgentAndSafetyContract() {
        val description = ReflectionToolsProvider().getTools()
            .map { it.descriptor }
            .single { it.name == "java_change_signature_add_parameter" }
            .description

        assertTrue(description.contains("search_symbol"))
        assertTrue(description.contains("all existing call sites"))
        assertTrue(description.contains("waiting for user approval"))
        assertTrue(description.contains("affectedFiles"))
        assertTrue(description.contains("Never use direct text edits"))
        assertTrue(description.contains("does not support general Change Signature"))
    }

    fun testSafeDeleteSchemaContainsExactlyFiveDeclaredArguments() {
        val descriptor = ReflectionToolsProvider().getTools()
            .map { it.descriptor }
            .single { it.name == "java_safe_delete" }

        assertEquals(
            setOf(
                "pathInProject",
                "startLine",
                "startColumn",
                "endLine",
                "endColumn",
                "projectPath",
            ),
            descriptor.inputSchema.propertiesSchema.keys,
        )
    }

    fun testSafeDeleteDescriptionStatesAgentAndSafetyContract() {
        val description = ReflectionToolsProvider().getTools()
            .map { it.descriptor }
            .single { it.name == "java_safe_delete" }
            .description

        assertTrue(description.contains("Safe Delete"))
        assertTrue(description.contains("native"))
        assertTrue(description.contains("unsafe usages"))
        assertTrue(description.contains("Never use direct text edits"))
    }

    fun testPluginXmlRegistersOnlyJavaRefactorToolset() {
        val xml = Files.readString(Path.of("src/main/resources/META-INF/plugin.xml"))

        assertEquals(1, Regex("<mcpServer\\.mcpToolset").findAll(xml).count())
        assertTrue(xml.contains("com.example.airefactoring.mcp.JavaRefactorToolset"))
        assertFalse(xml.contains("ExtractMethodMcpToolset"))
    }
}
