package com.example.airefactoring.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.impl.ReflectionToolsProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase

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
}
