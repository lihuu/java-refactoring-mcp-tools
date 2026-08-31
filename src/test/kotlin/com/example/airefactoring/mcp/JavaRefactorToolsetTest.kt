package com.example.airefactoring.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.impl.ReflectionToolsProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        assertTrue("java_inline_method missing", "java_inline_method" in names)
        assertTrue("java_introduce_constant missing", "java_introduce_constant" in names)
        assertTrue("java_introduce_field missing", "java_introduce_field" in names)
        assertTrue("java_introduce_parameter missing", "java_introduce_parameter" in names)
        assertTrue("java_safe_delete missing", "java_safe_delete" in names)
        assertTrue("java_move_instance_method missing", "java_move_instance_method" in names)
        assertTrue("java_make_static missing", "java_make_static" in names)
        assertTrue("java_convert_to_instance_method missing", "java_convert_to_instance_method" in names)
        assertTrue("java_encapsulate_fields missing", "java_encapsulate_fields" in names)
        assertTrue("java_extract_interface missing", "java_extract_interface" in names)
        assertTrue("java_extract_superclass missing", "java_extract_superclass" in names)
        assertTrue("java_pull_members_up missing", "java_pull_members_up" in names)
        assertTrue("java_push_members_down missing", "java_push_members_down" in names)
        assertTrue("java_use_interface_where_possible missing", "java_use_interface_where_possible" in names)
        assertTrue("java_introduce_parameter_object missing", "java_introduce_parameter_object" in names)
        assertTrue("java_replace_method_with_method_object missing", "java_replace_method_with_method_object" in names)
        assertTrue("java_move_class missing", "java_move_class" in names)
        assertTrue("java_extract_delegate missing", "java_extract_delegate" in names)
        assertTrue("java_locate_symbol missing", "java_locate_symbol" in names)
        assertEquals(23, names.count { it.startsWith("java_") })
        assertEquals(1, McpToolset.EP.extensionList.count { it is JavaRefactorToolset })
    }

    fun testMoveClassSchemaAndDescriptionPreserveContract() {
        val descriptor = ReflectionToolsProvider().getTools().map { it.descriptor }
            .single { it.name == "java_move_class" }
        assertEquals(
            setOf(
                "pathInProject", "classStartLine", "classStartColumn", "classEndLine", "classEndColumn",
                "targetPackage", "projectPath",
            ),
            descriptor.inputSchema.propertiesSchema.keys,
        )
        assertTrue(
            descriptor.inputSchema.requiredProperties.containsAll(
                setOf(
                    "pathInProject", "classStartLine", "classStartColumn", "classEndLine", "classEndColumn",
                    "targetPackage",
                ),
            ),
        )
        assertTrue(descriptor.description.contains("Move Class"))
        assertTrue(descriptor.description.contains("top-level"))
        assertTrue(descriptor.description.contains("affectedFiles"))
        assertTrue(descriptor.description.contains("Never use direct text edits"))
    }

    fun testExtractDelegateSchemaAndDescriptionPreserveContract() {
        val descriptor = ReflectionToolsProvider().getTools().map { it.descriptor }
            .single { it.name == "java_extract_delegate" }
        assertEquals(
            setOf(
                "pathInProject", "classStartLine", "classStartColumn", "classEndLine", "classEndColumn",
                "extractedFields", "extractedMethods", "newClassName", "extractInnerClass", "projectPath",
            ),
            descriptor.inputSchema.propertiesSchema.keys,
        )
        assertTrue(
            descriptor.inputSchema.requiredProperties.containsAll(
                setOf(
                    "pathInProject", "classStartLine", "classStartColumn", "classEndLine", "classEndColumn",
                    "extractedFields", "extractedMethods", "newClassName", "extractInnerClass",
                ),
            ),
        )
        val fields = descriptor.inputSchema.propertiesSchema.getValue("extractedFields").jsonObject
        assertEquals("array", fields.getValue("type").jsonPrimitive.content)
        assertEquals("string", fields.getValue("items").jsonObject.getValue("type").jsonPrimitive.content)
        val methods = descriptor.inputSchema.propertiesSchema.getValue("extractedMethods").jsonObject
        assertEquals("array", methods.getValue("type").jsonPrimitive.content)
        assertEquals("string", methods.getValue("items").jsonObject.getValue("type").jsonPrimitive.content)
        assertTrue(descriptor.description.contains("native Extract Delegate"))
        assertTrue(descriptor.description.contains("top-level class"))
        assertTrue(descriptor.description.contains("affectedFiles"))
        assertTrue(descriptor.description.contains("Never use direct text edits"))
    }

    fun testReplaceMethodWithMethodObjectSchemaAndDescriptionPreserveContract() {
        val descriptor = ReflectionToolsProvider().getTools().map { it.descriptor }
            .single { it.name == "java_replace_method_with_method_object" }
        assertEquals(
            setOf(
                "pathInProject", "methodStartLine", "methodStartColumn", "methodEndLine", "methodEndColumn",
                "methodObjectClassName", "methodObjectMethodName", "projectPath",
            ),
            descriptor.inputSchema.propertiesSchema.keys,
        )
        assertTrue(
            descriptor.inputSchema.requiredProperties.containsAll(
                setOf(
                    "pathInProject", "methodStartLine", "methodStartColumn", "methodEndLine", "methodEndColumn",
                    "methodObjectClassName", "methodObjectMethodName",
                ),
            ),
        )
        assertTrue(descriptor.description.contains("Replace Method with Method Object"))
        assertTrue(descriptor.description.contains("inner-class"))
        assertTrue(descriptor.description.contains("call sites are preserved"))
        assertTrue(descriptor.description.contains("affectedFiles"))
        assertTrue(descriptor.description.contains("Never use direct text edits"))
    }

    fun testInlineMethodSchemaAndDescriptionPreserveNativeAllUsagesContract() {
        val descriptor = ReflectionToolsProvider().getTools().map { it.descriptor }
            .single { it.name == "java_inline_method" }
        assertEquals(
            setOf("pathInProject", "methodStartLine", "methodStartColumn", "methodEndLine", "methodEndColumn", "projectPath"),
            descriptor.inputSchema.propertiesSchema.keys,
        )
        assertTrue(
            descriptor.inputSchema.requiredProperties.containsAll(
                setOf("pathInProject", "methodStartLine", "methodStartColumn", "methodEndLine", "methodEndColumn"),
            ),
        )
        assertTrue(descriptor.description.contains("native Inline Method"))
        assertTrue(descriptor.description.contains("Never use direct text edits"))
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

    fun testMoveInstanceMethodSchemaContainsExactlyTenDeclaredArguments() {
        val descriptor = ReflectionToolsProvider().getTools()
            .map { it.descriptor }
            .single { it.name == "java_move_instance_method" }

        assertEquals(
            setOf(
                "pathInProject",
                "methodStartLine",
                "methodStartColumn",
                "methodEndLine",
                "methodEndColumn",
                "targetStartLine",
                "targetStartColumn",
                "targetEndLine",
                "targetEndColumn",
                "newVisibility",
                "projectPath",
            ),
            descriptor.inputSchema.propertiesSchema.keys,
        )
    }

    fun testMoveInstanceMethodDescriptionStatesAgentAndSafetyContract() {
        val description = ReflectionToolsProvider().getTools()
            .map { it.descriptor }
            .single { it.name == "java_move_instance_method" }
            .description

        assertTrue(description.contains("Move Instance Method"))
        assertTrue(description.contains("Java"))
        assertTrue(description.contains("native"))
        assertTrue(description.contains("target parameter"))
        assertTrue(description.contains("Never use direct text edits"))
    }

    fun testMakeStaticSchemaUsesRuntimeSerializableParallelFieldLists() {
        val descriptor = ReflectionToolsProvider().getTools()
            .map { it.descriptor }
            .single { it.name == "java_make_static" }

        assertEquals(
            setOf(
                "pathInProject",
                "startLine",
                "startColumn",
                "endLine",
                "endColumn",
                "replaceUsages",
                "classParameterName",
                "fieldStartLines",
                "fieldStartColumns",
                "fieldEndLines",
                "fieldEndColumns",
                "fieldParameterNames",
                "generateDelegate",
                "projectPath",
            ),
            descriptor.inputSchema.propertiesSchema.keys,
        )
        assertFalse(
            "classParameterName must remain optional",
            "classParameterName" in descriptor.inputSchema.requiredProperties,
        )
    }

    fun testMakeStaticFieldListsUseOnlyPrimitiveArrayElements() {
        val descriptor = ReflectionToolsProvider().getTools()
            .map { it.descriptor }
            .single { it.name == "java_make_static" }

        val properties = descriptor.inputSchema.propertiesSchema
        listOf("fieldStartLines", "fieldStartColumns", "fieldEndLines", "fieldEndColumns").forEach { name ->
            val schema = properties.getValue(name).jsonObject
            assertEquals("array", schema.getValue("type").jsonPrimitive.content)
            assertEquals("integer", schema.getValue("items").jsonObject.getValue("type").jsonPrimitive.content)
        }
        val names = properties.getValue("fieldParameterNames").jsonObject
        assertEquals("array", names.getValue("type").jsonPrimitive.content)
        assertEquals("string", names.getValue("items").jsonObject.getValue("type").jsonPrimitive.content)
    }

    fun testMakeStaticDescriptionStatesAgentAndSafetyContract() {
        val description = ReflectionToolsProvider().getTools()
            .map { it.descriptor }
            .single { it.name == "java_make_static" }
            .description

        assertTrue(description.contains("Make Static"))
        assertTrue(description.contains("Java"))
        assertTrue(description.contains("native"))
        assertTrue(description.contains("method"))
        assertTrue(description.contains("inner class"))
        assertTrue(description.contains("explicitly"))
        assertTrue(description.contains("Never use direct text edits"))
    }

    fun testConvertToInstanceMethodSchemaAndDescription() {
        val descriptor = ReflectionToolsProvider().getTools().map { it.descriptor }.single { it.name == "java_convert_to_instance_method" }
        assertTrue(descriptor.description.contains("Convert to Instance Method"))
        assertTrue(descriptor.description.lowercase().contains("never uses direct text edits") || descriptor.description.contains("Never use direct text edits"))
        assertEquals(
            setOf("pathInProject","methodStartLine","methodStartColumn","methodEndLine","methodEndColumn","targetKind","targetStartLine","targetStartColumn","targetEndLine","targetEndColumn","newVisibility","confirmInterfaceImplementations","projectPath"),
            descriptor.inputSchema.propertiesSchema.keys,
        )
    }

    fun testEncapsulateFieldsSchemaAndDescription() {
        val descriptor = ReflectionToolsProvider().getTools().map { it.descriptor }.single { it.name == "java_encapsulate_fields" }
        assertTrue(descriptor.description.contains("Encapsulate Fields"))
        assertTrue(descriptor.description.contains("Never use direct text edits"))
        assertEquals(
            setOf("pathInProject","fieldStartLines","fieldStartColumns","fieldEndLines","fieldEndColumns","getterNames","setterNames","fieldsVisibility","accessorsVisibility","encapsulateGet","encapsulateSet","useAccessorsWhenAccessible","projectPath"),
            descriptor.inputSchema.propertiesSchema.keys,
        )
        // primitive array check
        val props = descriptor.inputSchema.propertiesSchema
        listOf("fieldStartLines","fieldStartColumns","fieldEndLines","fieldEndColumns").forEach { name ->
            val schema = props.getValue(name).jsonObject
            assertEquals("array", schema.getValue("type").jsonPrimitive.content)
            assertEquals("integer", schema.getValue("items").jsonObject.getValue("type").jsonPrimitive.content)
        }
        assertEquals("array", props.getValue("getterNames").jsonObject.getValue("type").jsonPrimitive.content)
    }

    fun testExtractInterfaceSchemaAndDescription() {
        val descriptor = ReflectionToolsProvider().getTools().map { it.descriptor }.single { it.name == "java_extract_interface" }
        assertTrue(descriptor.description.contains("Extract Interface"))
        assertTrue(descriptor.description.contains("Never use direct text edits"))
        assertTrue(descriptor.description.contains("Java"))
        assertEquals(
            setOf("pathInProject","sourceClassStartLine","sourceClassStartColumn","sourceClassEndLine","sourceClassEndColumn","memberStartLines","memberStartColumns","memberEndLines","memberEndColumns","interfaceName","targetPackage","projectPath"),
            descriptor.inputSchema.propertiesSchema.keys,
        )
        assertFalse("targetPackage must remain optional", "targetPackage" in descriptor.inputSchema.requiredProperties)
        val props = descriptor.inputSchema.propertiesSchema
        listOf("memberStartLines","memberStartColumns","memberEndLines","memberEndColumns").forEach { name ->
            val schema = props.getValue(name).jsonObject
            assertEquals("array", schema.getValue("type").jsonPrimitive.content)
            assertEquals("integer", schema.getValue("items").jsonObject.getValue("type").jsonPrimitive.content)
        }
    }

    fun testExtractSuperclassSchemaAndDescription() {
        val descriptor = ReflectionToolsProvider().getTools().map { it.descriptor }.single { it.name == "java_extract_superclass" }
        assertTrue(descriptor.description.contains("Extract Superclass"))
        assertTrue(descriptor.description.contains("Never use direct text edits"))
        assertTrue(descriptor.description.contains("Java"))
        assertEquals(
            setOf("pathInProject","sourceClassStartLine","sourceClassStartColumn","sourceClassEndLine","sourceClassEndColumn","memberStartLines","memberStartColumns","memberEndLines","memberEndColumns","superclassName","targetPackage","projectPath"),
            descriptor.inputSchema.propertiesSchema.keys,
        )
        assertFalse("targetPackage must remain optional", "targetPackage" in descriptor.inputSchema.requiredProperties)
        val props = descriptor.inputSchema.propertiesSchema
        listOf("memberStartLines","memberStartColumns","memberEndLines","memberEndColumns").forEach { name ->
            val schema = props.getValue(name).jsonObject
            assertEquals("array", schema.getValue("type").jsonPrimitive.content)
            assertEquals("integer", schema.getValue("items").jsonObject.getValue("type").jsonPrimitive.content)
        }
    }

    fun testPullMembersUpSchemaAndDescription() {
        val descriptor = ReflectionToolsProvider().getTools().map { it.descriptor }.single { it.name == "java_pull_members_up" }
        assertTrue(descriptor.description.contains("Pull Members Up"))
        assertTrue(descriptor.description.contains("Never use direct text edits"))
        assertTrue(descriptor.description.contains("Java"))
        assertEquals(
            setOf("pathInProject","sourceSubclassStartLine","sourceSubclassStartColumn","sourceSubclassEndLine","sourceSubclassEndColumn","memberStartLines","memberStartColumns","memberEndLines","memberEndColumns","targetSuperclassFqn","projectPath"),
            descriptor.inputSchema.propertiesSchema.keys,
        )
        val props = descriptor.inputSchema.propertiesSchema
        listOf("memberStartLines","memberStartColumns","memberEndLines","memberEndColumns").forEach { name ->
            val schema = props.getValue(name).jsonObject
            assertEquals("array", schema.getValue("type").jsonPrimitive.content)
            assertEquals("integer", schema.getValue("items").jsonObject.getValue("type").jsonPrimitive.content)
        }
    }

    fun testPushMembersDownSchemaAndDescription() {
        val descriptor = ReflectionToolsProvider().getTools().map { it.descriptor }.single { it.name == "java_push_members_down" }
        assertTrue(descriptor.description.contains("Push Members Down"))
        assertTrue(descriptor.description.contains("Never use direct text edits"))
        assertTrue(descriptor.description.contains("Java"))
        assertEquals(
            setOf("pathInProject","sourceSuperclassStartLine","sourceSuperclassStartColumn","sourceSuperclassEndLine","sourceSuperclassEndColumn","memberStartLines","memberStartColumns","memberEndLines","memberEndColumns","targetSubclassFqns","projectPath"),
            descriptor.inputSchema.propertiesSchema.keys,
        )
        val props = descriptor.inputSchema.propertiesSchema
        listOf("memberStartLines","memberStartColumns","memberEndLines","memberEndColumns").forEach { name ->
            val schema = props.getValue(name).jsonObject
            assertEquals("array", schema.getValue("type").jsonPrimitive.content)
            assertEquals("integer", schema.getValue("items").jsonObject.getValue("type").jsonPrimitive.content)
        }
        val target = props.getValue("targetSubclassFqns").jsonObject
        assertEquals("array", target.getValue("type").jsonPrimitive.content)
        assertEquals("string", target.getValue("items").jsonObject.getValue("type").jsonPrimitive.content)
    }

    fun testUseInterfaceWherePossibleSchemaAndDescription() {
        val descriptor = ReflectionToolsProvider().getTools().map { it.descriptor }.single { it.name == "java_use_interface_where_possible" }
        assertTrue(descriptor.description.contains("Use Interface Where Possible"))
        assertTrue(descriptor.description.contains("Never use direct text edits"))
        assertTrue(descriptor.description.contains("Java"))
        assertTrue(descriptor.description.contains("native"))
        assertTrue(descriptor.description.contains("replaceInstanceOf is fixed off"))
        assertEquals(
            setOf("pathInProject","sourceClassStartLine","sourceClassStartColumn","sourceClassEndLine","sourceClassEndColumn","targetInterfaceFqn","projectPath"),
            descriptor.inputSchema.propertiesSchema.keys,
        )
        val target = descriptor.inputSchema.propertiesSchema.getValue("targetInterfaceFqn").jsonObject
        assertEquals("string", target.getValue("type").jsonPrimitive.content)
    }

    fun testPluginXmlRegistersOnlyJavaRefactorToolset() {
        val xml = Files.readString(Path.of("src/main/resources/META-INF/plugin.xml"))

        assertEquals(1, Regex("<mcpServer\\.mcpToolset").findAll(xml).count())
        assertTrue(xml.contains("com.example.airefactoring.mcp.JavaRefactorToolset"))
        assertFalse(xml.contains("ExtractMethodMcpToolset"))
    }
}
