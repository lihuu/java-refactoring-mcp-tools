package com.example.airefactoring.mcp

import com.example.airefactoring.refactoring.SourceRange
import com.example.airefactoring.refactoring.changesignature.ChangeSignatureOperation
import com.example.airefactoring.refactoring.extractmethod.ExtractMethodOperation
import com.example.airefactoring.refactoring.inlinevariable.InlineVariableOperation
import com.example.airefactoring.refactoring.introducemember.IntroduceConstantOperation
import com.example.airefactoring.refactoring.introducemember.IntroduceFieldOperation
import com.example.airefactoring.refactoring.introduceparameter.IntroduceParameterOperation
import com.example.airefactoring.refactoring.introducevariable.IntroduceVariableOperation
import com.example.airefactoring.refactoring.converttoinstancemethod.ConvertToInstanceMethodOperation
import com.example.airefactoring.refactoring.encapsulatefields.EncapsulateFieldsOperation
import com.example.airefactoring.refactoring.extractinterface.ExtractInterfaceOperation
import com.example.airefactoring.refactoring.extractsuperclass.ExtractSuperclassOperation
import com.example.airefactoring.refactoring.pullup.PullMembersUpOperation
import com.example.airefactoring.refactoring.pushdown.PushMembersDownOperation
import com.example.airefactoring.refactoring.moveinstancemethod.MoveInstanceMethodOperation
import com.example.airefactoring.refactoring.safedelete.JavaSafeDeleteOperation
import com.example.airefactoring.refactoring.makestatic.JavaMakeStaticOperation
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import kotlinx.coroutines.currentCoroutineContext

/** MCP adapter for this plugin's native Java refactoring operations. */
class JavaRefactorToolset(
    private val extractMethodOperation: ExtractMethodOperation = ExtractMethodOperation(),
    private val introduceVariableOperation: IntroduceVariableOperation = IntroduceVariableOperation(),
    private val changeSignatureOperation: ChangeSignatureOperation = ChangeSignatureOperation(),
    private val inlineVariableOperation: InlineVariableOperation = InlineVariableOperation(),
    private val introduceConstantOperation: IntroduceConstantOperation = IntroduceConstantOperation(),
    private val introduceFieldOperation: IntroduceFieldOperation = IntroduceFieldOperation(),
    private val introduceParameterOperation: IntroduceParameterOperation = IntroduceParameterOperation(),
    private val javaSafeDeleteOperation: JavaSafeDeleteOperation = JavaSafeDeleteOperation(),
    private val moveInstanceMethodOperation: MoveInstanceMethodOperation = MoveInstanceMethodOperation(),
    private val javaMakeStaticOperation: JavaMakeStaticOperation = JavaMakeStaticOperation(),
    private val convertToInstanceMethodOperation: ConvertToInstanceMethodOperation = ConvertToInstanceMethodOperation(),
    private val encapsulateFieldsOperation: EncapsulateFieldsOperation = EncapsulateFieldsOperation(),
    private val extractInterfaceOperation: ExtractInterfaceOperation = ExtractInterfaceOperation(),
    private val extractSuperclassOperation: ExtractSuperclassOperation = ExtractSuperclassOperation(),
    private val pullMembersUpOperation: PullMembersUpOperation = PullMembersUpOperation(),
    private val pushMembersDownOperation: PushMembersDownOperation = PushMembersDownOperation(),
) : McpToolset {

    @McpTool
    @McpDescription(EXTRACT_METHOD_DESCRIPTION)
    suspend fun java_extract_method(
        @McpDescription("Java file path relative to the project root") pathInProject: String,
        @McpDescription("1-based inclusive start line") startLine: Int,
        @McpDescription("1-based inclusive start column") startColumn: Int,
        @McpDescription("1-based line containing the exclusive end position") endLine: Int,
        @McpDescription("1-based exclusive end column") endColumn: Int,
        @McpDescription("New lower-camel-case Java method name") methodName: String,
    ): String = extractMethodOperation.execute(
        currentCoroutineContext().project,
        pathInProject,
        SourceRange(startLine, startColumn, endLine, endColumn),
        methodName,
    )

    @McpTool
    @McpDescription(INTRODUCE_VARIABLE_DESCRIPTION)
    suspend fun java_introduce_variable(
        @McpDescription("Java file path relative to the project root") pathInProject: String,
        @McpDescription("1-based inclusive start line") startLine: Int,
        @McpDescription("1-based inclusive start column") startColumn: Int,
        @McpDescription("1-based line containing the exclusive end position") endLine: Int,
        @McpDescription("1-based exclusive end column") endColumn: Int,
        @McpDescription("Agent-selected preferred Java local-variable name") preferredVariableName: String,
    ): String = introduceVariableOperation.execute(
        currentCoroutineContext().project,
        pathInProject,
        SourceRange(startLine, startColumn, endLine, endColumn),
        preferredVariableName,
    )

    @McpTool
    @McpDescription(CHANGE_SIGNATURE_ADD_PARAMETER_DESCRIPTION)
    suspend fun java_change_signature_add_parameter(
        @McpDescription("Java file path relative to the project root") pathInProject: String,
        @McpDescription("1-based line inside the target method declaration or body") line: Int,
        @McpDescription("1-based column inside the target method declaration or body") column: Int,
        @McpDescription("Name of the one new Java parameter") parameterName: String,
        @McpDescription("Resolvable Java source type for the one new parameter") parameterType: String,
        @McpDescription("1-based position in the resulting parameter list") parameterPosition: Int,
        @McpDescription("Java expression inserted uniformly into all existing call sites")
        defaultCallSiteExpression: String,
    ): String = changeSignatureOperation.execute(
        currentCoroutineContext().project,
        pathInProject,
        line,
        column,
        parameterName,
        parameterType,
        parameterPosition,
        defaultCallSiteExpression,
    )

    @McpTool
    @McpDescription(INLINE_VARIABLE_DESCRIPTION)
    suspend fun java_inline_variable(
        @McpDescription("Java file path relative to the project root") pathInProject: String,
        @McpDescription(
            "1-based line on the local-variable declaration name or a resolved reference",
        )
        line: Int,
        @McpDescription(
            "1-based column on the local-variable declaration name or a resolved reference",
        )
        column: Int,
    ): String = inlineVariableOperation.execute(
        currentCoroutineContext().project,
        pathInProject,
        line,
        column,
    )

    @McpTool
    @McpDescription(INTRODUCE_CONSTANT_DESCRIPTION)
    suspend fun java_introduce_constant(
        @McpDescription("Java file path relative to the project root") pathInProject: String,
        @McpDescription("1-based inclusive start line") startLine: Int,
        @McpDescription("1-based inclusive start column") startColumn: Int,
        @McpDescription("1-based line containing the exclusive end position") endLine: Int,
        @McpDescription("1-based exclusive end column") endColumn: Int,
        @McpDescription("Agent-selected preferred Java constant field name") preferredName: String,
        @McpDescription(
            "Optional qualified name of the containing or enclosing class that will own the " +
                "constant; omitted means the nearest containing class",
        )
        targetClassQualifiedName: String? = null,
    ): String = introduceConstantOperation.execute(
        currentCoroutineContext().project,
        pathInProject,
        SourceRange(startLine, startColumn, endLine, endColumn),
        preferredName,
        targetClassQualifiedName,
    )

    @McpTool
    @McpDescription(INTRODUCE_FIELD_DESCRIPTION)
    suspend fun java_introduce_field(
        @McpDescription("Java file path relative to the project root") pathInProject: String,
        @McpDescription("1-based inclusive start line") startLine: Int,
        @McpDescription("1-based inclusive start column") startColumn: Int,
        @McpDescription("1-based line containing the exclusive end position") endLine: Int,
        @McpDescription("1-based exclusive end column") endColumn: Int,
        @McpDescription("Agent-selected preferred Java field name") preferredName: String,
        @McpDescription(
            "Optional qualified name of the containing or enclosing class that will own the " +
                "field; omitted means the nearest containing class",
        )
        targetClassQualifiedName: String? = null,
    ): String = introduceFieldOperation.execute(
        currentCoroutineContext().project,
        pathInProject,
        SourceRange(startLine, startColumn, endLine, endColumn),
        preferredName,
        targetClassQualifiedName,
    )

    @McpTool
    @McpDescription(INTRODUCE_PARAMETER_DESCRIPTION)
    suspend fun java_introduce_parameter(
        @McpDescription("Java file path relative to the project root") pathInProject: String,
        @McpDescription("1-based inclusive start line") startLine: Int,
        @McpDescription("1-based inclusive start column") startColumn: Int,
        @McpDescription("1-based line containing the exclusive end position") endLine: Int,
        @McpDescription("1-based exclusive end column") endColumn: Int,
        @McpDescription("Agent-selected preferred Java parameter name") parameterName: String,
    ): String = introduceParameterOperation.execute(
        currentCoroutineContext().project,
        pathInProject,
        SourceRange(startLine, startColumn, endLine, endColumn),
        parameterName,
    )

    @McpTool
    @McpDescription(MOVE_INSTANCE_METHOD_DESCRIPTION)
    suspend fun java_move_instance_method(
        @McpDescription("Java file path relative to the project root") pathInProject: String,
        @McpDescription("1-based inclusive start line of the method declaration name")
        methodStartLine: Int,
        @McpDescription("1-based inclusive start column of the method declaration name")
        methodStartColumn: Int,
        @McpDescription("1-based line containing the exclusive end position of the method declaration name")
        methodEndLine: Int,
        @McpDescription("1-based exclusive end column of the method declaration name")
        methodEndColumn: Int,
        @McpDescription("1-based inclusive start line of the target parameter name")
        targetStartLine: Int,
        @McpDescription("1-based inclusive start column of the target parameter name")
        targetStartColumn: Int,
        @McpDescription("1-based line containing the exclusive end position of the target parameter name")
        targetEndLine: Int,
        @McpDescription("1-based exclusive end column of the target parameter name")
        targetEndColumn: Int,
        @McpDescription("New visibility of the moved method: 'public', 'protected', 'private', or empty for package-local")
        newVisibility: String,
    ): String = moveInstanceMethodOperation.execute(
        currentCoroutineContext().project,
        pathInProject,
        SourceRange(methodStartLine, methodStartColumn, methodEndLine, methodEndColumn),
        SourceRange(targetStartLine, targetStartColumn, targetEndLine, targetEndColumn),
        newVisibility,
    )

    @McpTool
    @McpDescription(JAVA_MAKE_STATIC_DESCRIPTION)
    suspend fun java_make_static(
        @McpDescription("Java file path relative to the project root") pathInProject: String,
        @McpDescription("1-based inclusive start line of the method or inner-class declaration name")
        startLine: Int,
        @McpDescription("1-based inclusive start column of the method or inner-class declaration name")
        startColumn: Int,
        @McpDescription("1-based line containing the exclusive end position of the declaration name")
        endLine: Int,
        @McpDescription("1-based exclusive end column of the declaration name")
        endColumn: Int,
        @McpDescription("Replace usages of the made-static member throughout the project")
        replaceUsages: Boolean,
        @McpDescription(
            "Optional Java identifier for the enclosing-instance parameter, or null for no such parameter",
        )
        classParameterName: String? = null,
        @McpDescription(
            "Ordered 1-based start lines of explicitly selected instance fields; this list and every " +
                "other field list use the same index order",
        )
        fieldStartLines: List<Int> = emptyList(),
        @McpDescription("Ordered 1-based start columns of selected fields, aligned with fieldStartLines")
        fieldStartColumns: List<Int> = emptyList(),
        @McpDescription("Ordered 1-based exclusive end lines of selected fields, aligned with fieldStartLines")
        fieldEndLines: List<Int> = emptyList(),
        @McpDescription("Ordered 1-based exclusive end columns of selected fields, aligned with fieldStartLines")
        fieldEndColumns: List<Int> = emptyList(),
        @McpDescription("AI-selected Java parameter names, aligned with fieldStartLines")
        fieldParameterNames: List<String> = emptyList(),
        @McpDescription("Generate a delegate method that forwards to the made-static member")
        generateDelegate: Boolean,
    ): String = javaMakeStaticOperation.execute(
        currentCoroutineContext().project,
        pathInProject,
        SourceRange(startLine, startColumn, endLine, endColumn),
        replaceUsages,
        classParameterName,
        fieldStartLines,
        fieldStartColumns,
        fieldEndLines,
        fieldEndColumns,
        fieldParameterNames,
        generateDelegate,
    )

    @McpTool
    @McpDescription(SAFE_DELETE_DESCRIPTION)
    suspend fun java_safe_delete(
        @McpDescription("Java file path relative to the project root") pathInProject: String,
        @McpDescription("1-based inclusive start line") startLine: Int,
        @McpDescription("1-based inclusive start column") startColumn: Int,
        @McpDescription("1-based line containing the exclusive end position") endLine: Int,
        @McpDescription("1-based exclusive end column") endColumn: Int,
    ): String = javaSafeDeleteOperation.execute(
        currentCoroutineContext().project,
        pathInProject,
        SourceRange(startLine, startColumn, endLine, endColumn),
    )

    @McpTool
    @McpDescription(JAVA_ENCAPSULATE_FIELDS_DESCRIPTION)
    suspend fun java_encapsulate_fields(
        @McpDescription("Java file path relative to the project root") pathInProject: String,
        @McpDescription("Ordered 1-based start lines of the field declaration names to encapsulate; this list and every other field/getter/setter list use the same index order")
        fieldStartLines: List<Int>,
        @McpDescription("Ordered 1-based start columns of the field declaration names, aligned with fieldStartLines")
        fieldStartColumns: List<Int>,
        @McpDescription("Ordered 1-based exclusive end lines of the field declaration names, aligned with fieldStartLines")
        fieldEndLines: List<Int>,
        @McpDescription("Ordered 1-based exclusive end columns of the field declaration names, aligned with fieldStartLines")
        fieldEndColumns: List<Int>,
        @McpDescription("AI-selected Java getter names, aligned with fieldStartLines")
        getterNames: List<String>,
        @McpDescription("AI-selected Java setter names, aligned with fieldStartLines")
        setterNames: List<String>,
        @McpDescription("Field visibility after encapsulation: null or 'asIs' retains current, otherwise 'private', 'protected', or 'packageLocal'")
        fieldsVisibility: String? = null,
        @McpDescription("Accessor visibility: 'public', 'protected', 'packageLocal', or 'private'")
        accessorsVisibility: String,
        @McpDescription("Whether to generate and use getters")
        encapsulateGet: Boolean,
        @McpDescription("Whether to generate and use setters")
        encapsulateSet: Boolean,
        @McpDescription("Whether to rewrite references even when the field is already accessible at the use site")
        useAccessorsWhenAccessible: Boolean,
    ): String {
        if (
            fieldStartLines.size != fieldStartColumns.size ||
            fieldStartLines.size != fieldEndLines.size ||
            fieldStartLines.size != fieldEndColumns.size ||
            fieldStartLines.size != getterNames.size ||
            fieldStartLines.size != setterNames.size
        ) {
            return com.example.airefactoring.mcp.McpRefactoringResult.failure(
                com.example.airefactoring.mcp.McpRefactoringErrorCode.INVALID_RANGE,
                "Field range and accessor name lists must have equal lengths.",
            ).toJson()
        }
        if (fieldStartLines.isEmpty()) {
            return com.example.airefactoring.mcp.McpRefactoringResult.failure(
                com.example.airefactoring.mcp.McpRefactoringErrorCode.INVALID_RANGE,
                "At least one field must be selected.",
            ).toJson()
        }
        return encapsulateFieldsOperation.execute(
            currentCoroutineContext().project,
            pathInProject,
            fieldStartLines,
            fieldStartColumns,
            fieldEndLines,
            fieldEndColumns,
            getterNames,
            setterNames,
            fieldsVisibility,
            accessorsVisibility,
            encapsulateGet,
            encapsulateSet,
            useAccessorsWhenAccessible,
        )
    }

    @McpTool
    @McpDescription(JAVA_EXTRACT_INTERFACE_DESCRIPTION)
    suspend fun java_extract_interface(
        @McpDescription("Java file path relative to the project root") pathInProject: String,
        @McpDescription("1-based inclusive start line of the source class declaration name")
        sourceClassStartLine: Int,
        @McpDescription("1-based inclusive start column of the source class declaration name")
        sourceClassStartColumn: Int,
        @McpDescription("1-based line containing the exclusive end position of the source class declaration name")
        sourceClassEndLine: Int,
        @McpDescription("1-based exclusive end column of the source class declaration name")
        sourceClassEndColumn: Int,
        @McpDescription("Ordered 1-based start lines of the member declaration names to extract; this list and every other member list use the same index order")
        memberStartLines: List<Int>,
        @McpDescription("Ordered 1-based start columns of the member declaration names, aligned with memberStartLines")
        memberStartColumns: List<Int>,
        @McpDescription("Ordered 1-based exclusive end lines of the member declaration names, aligned with memberStartLines")
        memberEndLines: List<Int>,
        @McpDescription("Ordered 1-based exclusive end columns of the member declaration names, aligned with memberStartLines")
        memberEndColumns: List<Int>,
        @McpDescription("Simple Java name for the new interface")
        interfaceName: String,
        @McpDescription("Target package for the new interface; null or empty means same package as the source class")
        targetPackage: String? = null,
    ): String {
        if (
            memberStartLines.size != memberStartColumns.size ||
            memberStartLines.size != memberEndLines.size ||
            memberStartLines.size != memberEndColumns.size
        ) {
            return com.example.airefactoring.mcp.McpRefactoringResult.failure(
                com.example.airefactoring.mcp.McpRefactoringErrorCode.INVALID_RANGE,
                "Member range lists must have equal lengths.",
            ).toJson()
        }
        if (memberStartLines.isEmpty()) {
            return com.example.airefactoring.mcp.McpRefactoringResult.failure(
                com.example.airefactoring.mcp.McpRefactoringErrorCode.INVALID_RANGE,
                "At least one member must be selected.",
            ).toJson()
        }
        return extractInterfaceOperation.execute(
            currentCoroutineContext().project,
            pathInProject,
            sourceClassStartLine,
            sourceClassStartColumn,
            sourceClassEndLine,
            sourceClassEndColumn,
            memberStartLines,
            memberStartColumns,
            memberEndLines,
            memberEndColumns,
            interfaceName,
            targetPackage,
        )
    }

    @McpTool
    @McpDescription(JAVA_EXTRACT_SUPERCLASS_DESCRIPTION)
    suspend fun java_extract_superclass(
        @McpDescription("Java file path relative to the project root") pathInProject: String,
        @McpDescription("1-based inclusive start line of the source class declaration name")
        sourceClassStartLine: Int,
        @McpDescription("1-based inclusive start column of the source class declaration name")
        sourceClassStartColumn: Int,
        @McpDescription("1-based line containing the exclusive end position of the source class declaration name")
        sourceClassEndLine: Int,
        @McpDescription("1-based exclusive end column of the source class declaration name")
        sourceClassEndColumn: Int,
        @McpDescription("Ordered 1-based start lines of the member declaration names to extract; this list and every other member list use the same index order")
        memberStartLines: List<Int>,
        @McpDescription("Ordered 1-based start columns of the member declaration names, aligned with memberStartLines")
        memberStartColumns: List<Int>,
        @McpDescription("Ordered 1-based exclusive end lines of the member declaration names, aligned with memberStartLines")
        memberEndLines: List<Int>,
        @McpDescription("Ordered 1-based exclusive end columns of the member declaration names, aligned with memberStartLines")
        memberEndColumns: List<Int>,
        @McpDescription("Simple Java name for the new abstract superclass")
        superclassName: String,
        @McpDescription("Target package for the new superclass; null or empty means same package as the source class")
        targetPackage: String? = null,
    ): String {
        if (
            memberStartLines.size != memberStartColumns.size ||
            memberStartLines.size != memberEndLines.size ||
            memberStartLines.size != memberEndColumns.size
        ) {
            return com.example.airefactoring.mcp.McpRefactoringResult.failure(
                com.example.airefactoring.mcp.McpRefactoringErrorCode.INVALID_RANGE,
                "Member range lists must have equal lengths.",
            ).toJson()
        }
        if (memberStartLines.isEmpty()) {
            return com.example.airefactoring.mcp.McpRefactoringResult.failure(
                com.example.airefactoring.mcp.McpRefactoringErrorCode.INVALID_RANGE,
                "At least one member must be selected.",
            ).toJson()
        }
        return extractSuperclassOperation.execute(
            currentCoroutineContext().project,
            pathInProject,
            sourceClassStartLine,
            sourceClassStartColumn,
            sourceClassEndLine,
            sourceClassEndColumn,
            memberStartLines,
            memberStartColumns,
            memberEndLines,
            memberEndColumns,
            superclassName,
            targetPackage,
        )
    }

    @McpTool
    @McpDescription(JAVA_PULL_MEMBERS_UP_DESCRIPTION)
    suspend fun java_pull_members_up(
        @McpDescription("Java file path relative to the project root") pathInProject: String,
        @McpDescription("1-based inclusive start line of the source subclass declaration name")
        sourceSubclassStartLine: Int,
        @McpDescription("1-based inclusive start column of the source subclass declaration name")
        sourceSubclassStartColumn: Int,
        @McpDescription("1-based line containing the exclusive end position of the source subclass declaration name")
        sourceSubclassEndLine: Int,
        @McpDescription("1-based exclusive end column of the source subclass declaration name")
        sourceSubclassEndColumn: Int,
        @McpDescription("Ordered 1-based start lines of the member declaration names to pull up; this list and every other member list use the same index order")
        memberStartLines: List<Int>,
        @McpDescription("Ordered 1-based start columns of the member declaration names, aligned with memberStartLines")
        memberStartColumns: List<Int>,
        @McpDescription("Ordered 1-based exclusive end lines of the member declaration names, aligned with memberStartLines")
        memberEndLines: List<Int>,
        @McpDescription("Ordered 1-based exclusive end columns of the member declaration names, aligned with memberStartLines")
        memberEndColumns: List<Int>,
        @McpDescription("Qualified name of the existing direct superclass to pull members into")
        targetSuperclassFqn: String,
    ): String {
        if (
            memberStartLines.size != memberStartColumns.size ||
            memberStartLines.size != memberEndLines.size ||
            memberStartLines.size != memberEndColumns.size
        ) {
            return com.example.airefactoring.mcp.McpRefactoringResult.failure(
                com.example.airefactoring.mcp.McpRefactoringErrorCode.INVALID_RANGE,
                "Member range lists must have equal lengths.",
            ).toJson()
        }
        if (memberStartLines.isEmpty()) {
            return com.example.airefactoring.mcp.McpRefactoringResult.failure(
                com.example.airefactoring.mcp.McpRefactoringErrorCode.INVALID_RANGE,
                "At least one member must be selected.",
            ).toJson()
        }
        return pullMembersUpOperation.execute(
            currentCoroutineContext().project,
            pathInProject,
            sourceSubclassStartLine,
            sourceSubclassStartColumn,
            sourceSubclassEndLine,
            sourceSubclassEndColumn,
            memberStartLines,
            memberStartColumns,
            memberEndLines,
            memberEndColumns,
            targetSuperclassFqn,
        )
    }

    @McpTool
    @McpDescription(JAVA_PUSH_MEMBERS_DOWN_DESCRIPTION)
    suspend fun java_push_members_down(
        @McpDescription("Java file path relative to the project root") pathInProject: String,
        @McpDescription("1-based inclusive start line of the source superclass declaration name")
        sourceSuperclassStartLine: Int,
        @McpDescription("1-based inclusive start column of the source superclass declaration name")
        sourceSuperclassStartColumn: Int,
        @McpDescription("1-based line containing the exclusive end position of the source superclass declaration name")
        sourceSuperclassEndLine: Int,
        @McpDescription("1-based exclusive end column of the source superclass declaration name")
        sourceSuperclassEndColumn: Int,
        @McpDescription("Ordered 1-based start lines of the member declaration names to push down; this list and every other member list use the same index order")
        memberStartLines: List<Int>,
        @McpDescription("Ordered 1-based start columns of the member declaration names, aligned with memberStartLines")
        memberStartColumns: List<Int>,
        @McpDescription("Ordered 1-based exclusive end lines of the member declaration names, aligned with memberStartLines")
        memberEndLines: List<Int>,
        @McpDescription("Ordered 1-based exclusive end columns of the member declaration names, aligned with memberStartLines")
        memberEndColumns: List<Int>,
        @McpDescription("Qualified names of the existing direct subclasses to push members into")
        targetSubclassFqns: List<String>,
    ): String {
        if (
            memberStartLines.size != memberStartColumns.size ||
            memberStartLines.size != memberEndLines.size ||
            memberStartLines.size != memberEndColumns.size
        ) {
            return com.example.airefactoring.mcp.McpRefactoringResult.failure(
                com.example.airefactoring.mcp.McpRefactoringErrorCode.INVALID_RANGE,
                "Member range lists must have equal lengths.",
            ).toJson()
        }
        if (memberStartLines.isEmpty()) {
            return com.example.airefactoring.mcp.McpRefactoringResult.failure(
                com.example.airefactoring.mcp.McpRefactoringErrorCode.INVALID_RANGE,
                "At least one member must be selected.",
            ).toJson()
        }
        if (targetSubclassFqns.isEmpty()) {
            return com.example.airefactoring.mcp.McpRefactoringResult.failure(
                com.example.airefactoring.mcp.McpRefactoringErrorCode.INVALID_RANGE,
                "At least one target subclass must be specified.",
            ).toJson()
        }
        return pushMembersDownOperation.execute(
            currentCoroutineContext().project,
            pathInProject,
            sourceSuperclassStartLine,
            sourceSuperclassStartColumn,
            sourceSuperclassEndLine,
            sourceSuperclassEndColumn,
            memberStartLines,
            memberStartColumns,
            memberEndLines,
            memberEndColumns,
            targetSubclassFqns,
        )
    }

    @McpTool
    @McpDescription(CONVERT_TO_INSTANCE_METHOD_DESCRIPTION)
    suspend fun java_convert_to_instance_method(
        @McpDescription("Java file path relative to the project root") pathInProject: String,
        @McpDescription("1-based inclusive start line of the static method declaration name")
        methodStartLine: Int,
        @McpDescription("1-based inclusive start column of the static method declaration name")
        methodStartColumn: Int,
        @McpDescription("1-based line containing the exclusive end position of the static method declaration name")
        methodEndLine: Int,
        @McpDescription("1-based exclusive end column of the static method declaration name")
        methodEndColumn: Int,
        @McpDescription("Receiver target kind: 'parameter' or 'containing_class'")
        targetKind: String,
        @McpDescription("1-based inclusive start line of the target parameter name, or null for containing_class")
        targetStartLine: Int? = null,
        @McpDescription("1-based inclusive start column of the target parameter name, or null for containing_class")
        targetStartColumn: Int? = null,
        @McpDescription("1-based line containing the exclusive end position of the target parameter name, or null for containing_class")
        targetEndLine: Int? = null,
        @McpDescription("1-based exclusive end column of the target parameter name, or null for containing_class")
        targetEndColumn: Int? = null,
        @McpDescription("Optional new visibility: null retains current, or 'public', 'protected', 'private', 'packageLocal'")
        newVisibility: String? = null,
        @McpDescription("Explicit approval for an interface target without extension-method support that has implementations")
        confirmInterfaceImplementations: Boolean = false,
    ): String {
        val methodRange = SourceRange(methodStartLine, methodStartColumn, methodEndLine, methodEndColumn)
        val targetRange = when {
            targetStartLine == null && targetStartColumn == null && targetEndLine == null && targetEndColumn == null -> null
            targetStartLine != null && targetStartColumn != null && targetEndLine != null && targetEndColumn != null ->
                SourceRange(targetStartLine, targetStartColumn, targetEndLine, targetEndColumn)
            else -> {
                return com.example.airefactoring.mcp.McpRefactoringResult.failure(
                    com.example.airefactoring.mcp.McpRefactoringErrorCode.INVALID_RANGE,
                    "Target coordinates must be all present or all absent; partial tuples are not allowed.",
                ).toJson()
            }
        }
        return convertToInstanceMethodOperation.execute(
            currentCoroutineContext().project,
            pathInProject,
            methodRange,
            targetKind,
            targetRange,
            newVisibility,
            confirmInterfaceImplementations,
        )
    }

    private companion object {
        const val EXTRACT_METHOD_DESCRIPTION =
            "Extracts a Java expression or statement block into a new method using IntelliJ's " +
                "native Extract Method refactoring. Use it to split a complex or long Java method " +
                "into smaller, well-named methods: present the proposed decomposition and wait for " +
                "user approval before calling, call once per extracted method, then re-read the " +
                "modified file before computing the next source range because line and column " +
                "positions change after every extraction. Never implement Extract Method through " +
                "direct text edits; if the native refactoring refuses the selection, report the " +
                "failure rather than editing source. The target is addressed by a project-relative " +
                "Java file path and a 1-based source range (start inclusive, end exclusive). The " +
                "method name must be a valid lower-camel-case Java identifier. Returns a JSON " +
                "object with ok=true on success, or ok=false with a stable error code on failure."

        const val INTRODUCE_VARIABLE_DESCRIPTION =
            "Introduces one exact Java expression as one local variable using IntelliJ's native " +
                "Introduce Variable refactoring. Use it after reading the containing method, " +
                "choosing a semantic preferred variable name from the expression and context, " +
                "presenting the change, and waiting for user approval. Only the selected occurrence " +
                "is replaced; IntelliJ writes an explicit non-final type and may make a conflicting " +
                "valid name unique. Re-read the file after success and use the returned " +
                "actualVariableName for later work. Never use direct text edits, patches, or PSI " +
                "mutation as a fallback when this native refactoring rejects the expression. The " +
                "target uses a project-relative Java path and 1-based source range with an inclusive " +
                "start and exclusive end. Returns JSON with ok=true on success or a stable error " +
                "code on failure."

        const val CHANGE_SIGNATURE_ADD_PARAMETER_DESCRIPTION =
            "Adds exactly one parameter to one ordinary Java method using IntelliJ's native Change " +
                "Signature refactoring. Use search_symbol to obtain the exact declaration path and " +
                "1-based line/column, then read the method and callers, present the proposed signature " +
                "and uniform argument strategy, and call only after waiting for user approval. The " +
                "supplied expression is inserted into all existing call sites; it is a structural " +
                "default and may require separately reasoned caller-specific edits. Re-read every " +
                "returned affectedFiles entry and run diagnostics, build, and tests after success. " +
                "This tool rejects constructors, overloads, override hierarchies, method references, " +
                "unsupported usages, and conflicts; it does not support general Change Signature. " +
                "Never use direct text edits, patches, whole-file rewrites, or direct PSI mutation " +
                "as a fallback. Returns JSON with ok=true on success or ok=false with a stable error " +
                "code on failure."

        const val INLINE_VARIABLE_DESCRIPTION =
            "Inlines one Java local variable using IntelliJ's native Inline Variable refactoring. " +
                "Use it after reading the complete containing method and all variable uses, " +
                "proposing the readability change, and waiting for user approval. Pass a fresh " +
                "1-based line and column on the declaration name or a resolved reference. The " +
                "tool replaces all supported read references and deletes the declaration as one " +
                "Undoable operation; it does not support inlining one occurrence. Re-read the " +
                "changed file and run diagnostics, build, and tests after success. Never use " +
                "direct text edits, patches, whole-file rewrites, or direct PSI mutation as a " +
                "fallback when the native refactoring rejects the target. Returns JSON with " +
                "ok=true on success or ok=false with a stable error code on failure."

        const val INTRODUCE_CONSTANT_DESCRIPTION =
            "Introduces one exact Java expression as one private static final constant field of " +
                "the nearest containing class by default using IntelliJ's native Introduce Constant " +
                "refactoring. Pass targetClassQualifiedName when the agent explicitly chooses " +
                "that containing class or one of its enclosing classes. Use " +
                "it after reading the containing method, choosing a semantic preferred field " +
                "name, presenting the change, and waiting for user approval. Only the selected " +
                "occurrence is extracted; the new field is declared in the resolved target class and " +
                "initialized at its declaration. The target is a project-relative Java file path " +
                "and a 1-based exact source range with an inclusive start and exclusive end. " +
                "Read the current source before supplying the range, and re-read the modified " +
                "file before computing the next source range because line and column positions " +
                "change after every refactoring. Run diagnostics, build, and tests after " +
                "success. Never use direct text edits, patches, whole-file rewrites, or direct PSI " +
                "mutation as a fallback when the native refactoring refuses the expression. " +
                "Returns JSON with ok=true on success or ok=false with a stable error code on " +
                "failure."

        const val INTRODUCE_FIELD_DESCRIPTION =
            "Introduces one exact Java expression as one private final instance field of the " +
                "nearest containing class by default using IntelliJ's native Introduce Field " +
                "refactoring. Pass targetClassQualifiedName when the agent explicitly chooses " +
                "that containing class or one of its enclosing classes. Use it " +
                "after reading the containing method, choosing a semantic preferred field name, " +
                "presenting the change, and waiting for user approval. Only the selected " +
                "occurrence is replaced; the new field is declared in the resolved target class and " +
                "initialized at its declaration. The target is a project-relative Java file path " +
                "and a 1-based exact source range with an inclusive start and exclusive end. " +
                "Read the current source before supplying the range, and re-read the modified " +
                "file before computing the next source range because line and column positions " +
                "change after every refactoring. Run diagnostics, build, and tests after " +
                "success. Never use direct text edits, patches, whole-file rewrites, or direct PSI " +
                "mutation as a fallback when the native refactoring refuses the expression. " +
                "Returns JSON with ok=true on success or ok=false with a stable error code on " +
                "failure."

        const val INTRODUCE_PARAMETER_DESCRIPTION =
            "Introduces one exact Java expression or local variable as one new parameter of its " +
                "enclosing method using IntelliJ's native Introduce Parameter refactoring. The " +
                "new parameter is appended after every existing parameter, and every call site is " +
                "updated from the selected source: the affected callers are derived natively from " +
                "the selected source, never guessed by the agent. This tool is not the general " +
                "Change Signature tool; it only adds one parameter from one selected expression or " +
                "local variable. Read the containing method and all callers, analyze the proposed " +
                "signature change, present the change to the user, and call only after waiting for " +
                "user approval. Re-read every returned affectedFiles entry and run diagnostics, " +
                "build, and tests after success. Never use direct text edits, patches, whole-file " +
                "rewrites, or direct PSI mutation as a fallback when the native refactoring " +
                "refuses the selection. The target is a project-relative Java file path and a " +
                "1-based source range with an inclusive start and exclusive end (start inclusive, " +
                "end exclusive). The parameter name must be a valid Java identifier. Returns JSON " +
                "with ok=true on success or ok=false with a stable error code on failure."

        const val MOVE_INSTANCE_METHOD_DESCRIPTION =
            "Moves one Java instance method to its instance via IntelliJ's native Move Instance " +
                "Method refactoring, driven headlessly with no dialog. Use it after reading the " +
                "method, choosing the target parameter of the selected method that receives the " +
                "method, presenting the change, and waiting for user approval. The moved method " +
                "receives the new visibility you request, the old-owner access becomes the native " +
                "bridge parameter, and every call site is rewritten by IntelliJ. The target is a " +
                "project-relative Java file path plus two 1-based source ranges (start inclusive, " +
                "end exclusive): one for the method declaration name and one for the target " +
                "parameter name, which must be a parameter of the selected method. Re-read every " +
                "affected file and run diagnostics, build, and tests after success. Never use " +
                "direct text edits, patches, whole-file rewrites, or direct PSI mutation as a " +
                "fallback when the native refactoring refuses the request. Returns JSON with " +
                "ok=true on success or ok=false with a stable error code on failure."

        const val JAVA_MAKE_STATIC_DESCRIPTION =
            "Make Static converts one Java instance method or non-static inner class to static using " +
                "IntelliJ's native Make Static refactoring, driven headlessly with no dialog. Use it " +
                "after reading the target method or inner class, its enclosing state, and its call " +
                "sites, then choosing the parameterization explicitly: replaceUsages, an optional " +
                "classParameterName for the enclosing instance, ordered parallel field range and name " +
                "lists for explicitly selected instance fields, and generateDelegate. Every field list " +
                "uses the same index order. The plugin does not infer fields, choose parameter names, " +
                "reorder parameters, or decide whether a delegate is appropriate. The target is a " +
                "project-relative Java file path and a 1-based source range (start inclusive, end " +
                "exclusive) that exactly matches the method or inner-class declaration name. Never use " +
                "direct text edits, patches, whole-file rewrites, or direct PSI mutation as a fallback " +
                "when the native refactoring refuses the request. Returns JSON with ok=true on success " +
                "or ok=false with a stable error code on failure."

        const val SAFE_DELETE_DESCRIPTION =
            "Deletes one Java declaration using IntelliJ's native Safe Delete refactoring. Use it " +
                "after reading the declaration and all of its usages, presenting the deletion and " +
                "its consequences, and waiting for user approval. The native processor finds every " +
                "usage and refuses the deletion when any unsafe usages remain, so the agent must " +
                "never guess whether a target is safe. The target is a project-relative Java file " +
                "path and a 1-based source range (start inclusive, end exclusive) that exactly " +
                "matches the declaration name or the complete element. Re-read the modified file " +
                "and run diagnostics, build, and tests after success. Never use direct text edits, " +
                "patches, whole-file rewrites, or direct PSI mutation as a fallback when the " +
                "native refactoring refuses the target. Returns JSON with ok=true on success or " +
                "ok=false with a stable error code on failure."

        const val CONVERT_TO_INSTANCE_METHOD_DESCRIPTION =
            "Converts one Java static method to an instance method using IntelliJ's native " +
                "Convert to Instance Method refactoring, driven headlessly with no dialog. Use it " +
                "after reading the static method, choosing the native receiver explicitly and " +
                "waiting for user approval. The receiver is either 'parameter' (a resolvable " +
                "project-class parameter of the selected method, supplied with all four target " +
                "coordinates) or 'containing_class' (no target coordinates, using the method's " +
                "owning class as receiver with native named/non-enum/non-inner/no-arg-constructor " +
                "conditions). Pass the exact 1-based declaration-name ranges (start inclusive, end " +
                "exclusive), the AI-chosen newVisibility (null retains current visibility, otherwise " +
                "'public', 'protected', 'private', 'packageLocal'), and explicit " +
                "confirmInterfaceImplementations only when a target interface without extension " +
                "support needs implementations (false refuses without mutation). The plugin makes " +
                "none of these decisions. Operates only on Java source via " +
                "ConvertToInstanceMethodProcessor; never uses direct text edits, patches, file " +
                "rewrites, or PSI mutation as a fallback. Returns JSON with ok=true on success or " +
                "ok=false with a stable error code on failure."

        const val JAVA_ENCAPSULATE_FIELDS_DESCRIPTION =
            "Encapsulate Fields encapsulates 1..N fields of the same Java class using IntelliJ's native " +
                "Encapsulate Fields refactoring, driven headlessly with no dialog. Use it after reading the " +
                "containing class, choosing the fields and complete accessor policy explicitly, and waiting for " +
                "user approval. The agent explicitly selects ordered field declaration-name ranges (start inclusive, " +
                "end exclusive), ordered getterNames/setterNames, fieldsVisibility (null or 'asIs' retains current, " +
                "otherwise 'private', 'protected', or 'packageLocal'), accessorsVisibility ('public', 'protected', " +
                "'packageLocal', 'private'), encapsulateGet, encapsulateSet, and useAccessorsWhenAccessible. The " +
                "plugin neither fills in nor changes those decisions; Javadoc is fixed at 0 and targetClass is " +
                "fixed at the containing class. This is Java-only, native, and generates correct getter/setter " +
                "prototypes via JavaEncapsulateFieldHelper, rewrites every field reference, and reports native " +
                "conflicts before mutation. Never use direct text edits, patches, whole-file rewrites, or direct " +
                "PSI mutation as a fallback. Returns JSON with ok=true on success or ok=false with a stable error " +
                "code on failure with one native Undo."

        const val JAVA_EXTRACT_INTERFACE_DESCRIPTION =
            "Extract Interface creates a new Java interface from 1..N public members of one exact Java class using IntelliJ's native " +
                "Extract Interface refactoring, driven headlessly with no dialog. Use it after reading the source class and its members, choosing the members and interface identity explicitly, and waiting for " +
                "user approval. The agent explicitly selects the source class declaration-name range (start inclusive, end exclusive), ordered member declaration-name ranges (start inclusive, end exclusive) of public instance methods and public static final fields belonging to that class, a simple interfaceName, and an optional targetPackage (null or empty means same package as source; otherwise a dot-separated qualified name). The plugin neither fills in nor changes those decisions; the source class always implements the new interface, Javadoc is fixed at 0, and only public members are extractable in V1. This is Java-only, native, creates the new interface file and wires implements, and reports native conflicts before mutation. Never use direct text edits, patches, whole-file rewrites, or direct PSI mutation as a fallback. Returns JSON with ok=true on success or ok=false with a stable error code on failure with one native Undo."

        const val JAVA_EXTRACT_SUPERCLASS_DESCRIPTION =
            "Extract Superclass creates a new Java abstract superclass from 1..N public members of one exact Java class using IntelliJ's native " +
                "Extract Superclass refactoring, driven headlessly with no dialog. Use it after reading the source class and its members, choosing the members and superclass identity explicitly, and waiting for " +
                "user approval. The agent explicitly selects the source class declaration-name range (start inclusive, end exclusive), ordered member declaration-name ranges (start inclusive, end exclusive) of public instance methods and public static final fields belonging to that class, a simple superclassName, and an optional targetPackage (null or empty means same package as source; otherwise a dot-separated qualified name). The plugin neither fills in nor changes those decisions; the source class always extends the new abstract superclass, methods become abstract, Javadoc is fixed at 0, and only public members are extractable in V1. This is Java-only, native, creates the new superclass file and wires extends, and reports native conflicts before mutation. Never use direct text edits, patches, whole-file rewrites, or direct PSI mutation as a fallback. Returns JSON with ok=true on success or ok=false with a stable error code on failure with one native Undo."

        const val JAVA_PULL_MEMBERS_UP_DESCRIPTION =
            "Pull Members Up moves 1..N public members of one exact Java subclass into its existing direct superclass using IntelliJ's native " +
                "Pull Members Up refactoring, driven headlessly with no dialog. Use it after reading the subclass, its members, and the target superclass, choosing the members and hierarchy target explicitly, and waiting for " +
                "user approval. The agent explicitly selects the source subclass declaration-name range (start inclusive, end exclusive), ordered member declaration-name ranges (start inclusive, end exclusive) of public instance methods and public static final fields belonging to that subclass, and the qualified name of the direct superclass. The plugin neither fills in nor changes those decisions; methods become abstract in the superclass, Javadoc is fixed at 0, and only public members are movable in V1. This is Java-only, native, and reports native conflicts before mutation. Never use direct text edits, patches, whole-file rewrites, or direct PSI mutation as a fallback. Returns JSON with ok=true on success or ok=false with a stable error code on failure with one native Undo."

        const val JAVA_PUSH_MEMBERS_DOWN_DESCRIPTION =
            "Push Members Down moves 1..N public members of one exact Java superclass into its existing direct subclasses using IntelliJ's native " +
                "Push Members Down refactoring, driven headlessly with no dialog. Use it after reading the superclass, its members, and the target subclasses, choosing the members and hierarchy targets explicitly, and waiting for " +
                "user approval. The agent explicitly selects the source superclass declaration-name range (start inclusive, end exclusive), ordered member declaration-name ranges (start inclusive, end exclusive) of public instance methods and public static final fields belonging to that superclass, and the qualified names of the direct subclasses. The plugin neither fills in nor changes those decisions; methods become abstract in the source and concrete in each target, Javadoc is fixed at 0, and only public members are movable in V1. This is Java-only, native, and reports native conflicts before mutation. Never use direct text edits, patches, whole-file rewrites, or direct PSI mutation as a fallback. Returns JSON with ok=true on success or ok=false with a stable error code on failure with one native Undo."
    }
}
