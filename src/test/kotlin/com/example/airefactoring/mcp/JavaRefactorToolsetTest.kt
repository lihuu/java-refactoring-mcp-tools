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
    }

    fun testBothPluginToolsAreExposedFromOneToolset() {
        val names = ReflectionToolsProvider().getTools()
            .map { it.descriptor.name }
            .toSet()

        assertTrue("java_extract_method missing", "java_extract_method" in names)
        assertTrue("java_introduce_variable missing", "java_introduce_variable" in names)
        assertEquals(1, McpToolset.EP.extensionList.count { it is JavaRefactorToolset })
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

    fun testPluginXmlRegistersOnlyJavaRefactorToolset() {
        val xml = Files.readString(Path.of("src/main/resources/META-INF/plugin.xml"))

        assertEquals(1, Regex("<mcpServer\\.mcpToolset").findAll(xml).count())
        assertTrue(xml.contains("com.example.airefactoring.mcp.JavaRefactorToolset"))
        assertFalse(xml.contains("ExtractMethodMcpToolset"))
    }
}
