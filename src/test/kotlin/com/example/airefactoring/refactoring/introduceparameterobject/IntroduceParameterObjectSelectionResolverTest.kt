package com.example.airefactoring.refactoring.introduceparameterobject

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path

class IntroduceParameterObjectSelectionResolverTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = IntroduceParameterObjectSelectionResolver()

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testResolvesExactMethodNameAndSelectedParametersInDeclarationOrder() {
        val path = "example/ResolverTop.java"
        mirrorRealFile(path, """
            package example;
            public class ResolverTop {
                public void createInvoice(String customer, String currency, int dueDays, boolean preview) {
                    System.out.println(customer + currency + dueDays + preview);
                }
            }
        """.trimIndent())
        // create caller to verify cross-file inventory later
        mirrorRealFile("example/ResolverCaller.java", """
            package example;
            public class ResolverCaller { void call(){ new ResolverTop().createInvoice("a","USD",1,false);} }
        """.trimIndent())
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeForMethod(path, "createInvoice")
        val res = resolver.resolve(project, path, range, listOf("dueDays","customer"), "new_top_level", "InvoiceRequest", "example.request", null, true, false)
        assertTrue("expected Success but was $res", res is IntroduceParameterObjectSelectionResolution.Success)
        val prep = (res as IntroduceParameterObjectSelectionResolution.Success).preparation
        assertEquals(JavaParameterObjectPlacement.NEW_TOP_LEVEL, prep.placement)
        assertEquals(listOf("customer","dueDays"), prep.parameterNamesSnapshot) // declaration order: customer before dueDays
        assertEquals("InvoiceRequest", prep.className)
        assertEquals("example.request", prep.targetPackage)
        assertTrue(prep.affectedVirtualFiles.any { it.path.contains("ResolverTop") })
    }

    fun testRejectsMethodRangeThatIncludesTypeOrParentheses() {
        val path = "example/ResolverBadRange.java"
        mirrorRealFile(path, """
            package example;
            public class ResolverBadRange {
                public void createInvoice(String customer, String currency) {}
            }
        """.trimIndent())
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val doc = document(path)
        // range that includes return type "void"
        val voidOffset = doc.text.indexOf("void")
        val voidRange = range(doc, voidOffset, voidOffset + 4)
        val res1 = resolver.resolve(project, path, voidRange, listOf("customer"), "new_inner_class", "Req", null, null, true, false)
        assertFailure(res1, McpRefactoringErrorCode.INVALID_RANGE)
        // range that includes parentheses
        val parenOffset = doc.text.indexOf("createInvoice(") + "createInvoice".length
        val parenRange = range(doc, doc.text.indexOf("createInvoice"), parenOffset + 1)
        val res2 = resolver.resolve(project, path, parenRange, listOf("customer"), "new_inner_class", "Req", null, null, true, false)
        assertFailure(res2, McpRefactoringErrorCode.INVALID_RANGE)
    }

    fun testRejectsEmptyDuplicateOrUnknownParameterNames() {
        val path = "example/ResolverParams.java"
        mirrorRealFile(path, """
            package example;
            public class ResolverParams {
                public void createInvoice(String customer, String currency, int dueDays) {}
            }
        """.trimIndent())
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeForMethod(path, "createInvoice")
        assertFailure(resolver.resolve(project, path, range, emptyList(), "new_inner_class","Req",null,null,true,false), McpRefactoringErrorCode.INVALID_RANGE)
        assertFailure(resolver.resolve(project, path, range, listOf("customer","customer"), "new_inner_class","Req",null,null,true,false), McpRefactoringErrorCode.INVALID_RANGE)
        assertFailure(resolver.resolve(project, path, range, listOf("unknown"), "new_inner_class","Req",null,null,true,false), McpRefactoringErrorCode.INVALID_RANGE)
    }

    fun testRejectsPlacementSpecificContradictions() {
        val path = "example/ResolverPlacement.java"
        mirrorRealFile(path, """
            package example;
            public class ResolverPlacement {
                public void createInvoice(String customer, String currency) {}
            }
        """.trimIndent())
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeForMethod(path, "createInvoice")
        // top-level must not have existingClassFqn
        assertFailure(resolver.resolve(project, path, range, listOf("customer"), "new_top_level", "Req", "example.req", "example.Foo", true, false), McpRefactoringErrorCode.UNSUPPORTED_TARGET)
        // inner must not have targetPackage
        assertFailure(resolver.resolve(project, path, range, listOf("customer"), "new_inner_class", "Req", "example.pkg", null, true, false), McpRefactoringErrorCode.UNSUPPORTED_TARGET)
        // inner must not have existingClassFqn
        assertFailure(resolver.resolve(project, path, range, listOf("customer"), "new_inner_class", "Req", null, "example.Foo", true, false), McpRefactoringErrorCode.UNSUPPORTED_TARGET)
        // existing must not have className
        mirrorRealFile("example/ExistingFoo.java", "package example; public class ExistingFoo { public ExistingFoo(String c){}}")
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertFailure(resolver.resolve(project, path, range, listOf("customer"), "existing_class", "Req", null, "example.ExistingFoo", true, false), McpRefactoringErrorCode.UNSUPPORTED_TARGET)
    }

    fun testRejectsMissingTopLevelPackageOrExistingClassFqn() {
        val path = "example/ResolverMissing.java"
        mirrorRealFile(path, """
            package example;
            public class ResolverMissing {
                public void createInvoice(String customer, String currency) {}
            }
        """.trimIndent())
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeForMethod(path, "createInvoice")
        assertFailure(resolver.resolve(project, path, range, listOf("customer"), "new_top_level", "Req", null, null, true, false), McpRefactoringErrorCode.INVALID_FIELD_NAME)
        assertFailure(resolver.resolve(project, path, range, listOf("customer"), "new_top_level", null, "example.req", null, true, false), McpRefactoringErrorCode.INVALID_FIELD_NAME)
        assertFailure(resolver.resolve(project, path, range, listOf("customer"), "existing_class", null, null, null, true, false), McpRefactoringErrorCode.INVALID_FIELD_NAME)
        assertFailure(resolver.resolve(project, path, range, listOf("customer"), "new_inner_class", null, null, null, true, false), McpRefactoringErrorCode.INVALID_FIELD_NAME)
    }

    fun testRejectsExistingClassOutsideProjectOrNotAClass() {
        val path = "example/ResolverExistingBad.java"
        mirrorRealFile(path, """
            package example;
            public class ResolverExistingBad {
                public void createInvoice(String customer, String currency) {}
            }
        """.trimIndent())
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeForMethod(path, "createInvoice")
        // outside project: java.util.ArrayList is library - accept either OUTSIDE_PROJECT or UNSUPPORTED_TARGET depending on platform
        val resOutside = resolver.resolve(project, path, range, listOf("customer"), "existing_class", null, null, "java.util.ArrayList", true, false)
        assertTrue("expected Failure for outside project but was $resOutside", resOutside is IntroduceParameterObjectSelectionResolution.Failure)
        assertTrue((resOutside as IntroduceParameterObjectSelectionResolution.Failure).code in setOf(McpRefactoringErrorCode.OUTSIDE_PROJECT, McpRefactoringErrorCode.UNSUPPORTED_TARGET))
        // not a class: interface
        mirrorRealFile("example/MyInterface.java", "package example; public interface MyInterface {}")
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertFailure(resolver.resolve(project, path, range, listOf("customer"), "existing_class", null, null, "example.MyInterface", true, false), McpRefactoringErrorCode.UNSUPPORTED_TARGET)
        // not found
        assertFailure(resolver.resolve(project, path, range, listOf("customer"), "existing_class", null, null, "example.NotExist", true, false), McpRefactoringErrorCode.UNSUPPORTED_TARGET)
    }

    fun testRejectsReadOnlyMethodCallerOrExistingClassFile() {
        val path = "example/ResolverReadOnly.java"
        val vf = mirrorRealFile(path, """
            package example;
            public class ResolverReadOnly {
                public void createInvoice(String customer, String currency) {}
            }
        """.trimIndent())
        mirrorRealFile("example/ResolverReadOnlyCaller.java", """
            package example;
            public class ResolverReadOnlyCaller { void call(){ new ResolverReadOnly().createInvoice("a","b"); } }
        """.trimIndent())
        mirrorRealFile("example/ExistingReadOnly.java", "package example; public class ExistingReadOnly { public ExistingReadOnly(String c){}}")
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeForMethod(path, "createInvoice")
        // make method file read-only
        WriteAction.run<Throwable> { vf.isWritable = false }
        try {
            val res = resolver.resolve(project, path, range, listOf("customer"), "new_inner_class", "Req", null, null, true, false)
            assertFailure(res, McpRefactoringErrorCode.READ_ONLY)
        } finally {
            WriteAction.run<Throwable> { vf.isWritable = true }
        }
        // make caller read-only
        val callerVf = LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, "example/ResolverReadOnlyCaller.java").toString())!!
        WriteAction.run<Throwable> { callerVf.isWritable = false }
        try {
            val res = resolver.resolve(project, path, range, listOf("customer"), "new_inner_class", "Req", null, null, true, false)
            assertFailure(res, McpRefactoringErrorCode.READ_ONLY)
        } finally {
            WriteAction.run<Throwable> { callerVf.isWritable = true }
        }
        // make existing class read-only
        val existingVf = LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, "example/ExistingReadOnly.java").toString())!!
        WriteAction.run<Throwable> { existingVf.isWritable = false }
        try {
            val res = resolver.resolve(project, path, range, listOf("customer"), "existing_class", null, null, "example.ExistingReadOnly", true, false)
            assertFailure(res, McpRefactoringErrorCode.READ_ONLY)
        } finally {
            WriteAction.run<Throwable> { existingVf.isWritable = true }
        }
    }

    fun testCapturesCrossFileUsagesAndCreatedClassDestinationFiles() {
        val path = "example/ResolverCapture.java"
        mirrorRealFile(path, """
            package example;
            public class ResolverCapture {
                public void createInvoice(String customer, String currency, int dueDays) {}
            }
        """.trimIndent())
        mirrorRealFile("example/ResolverCaptureCallerOne.java", "package example; public class ResolverCaptureCallerOne { void call(){ new ResolverCapture().createInvoice(\"a\",\"b\",1);} }")
        mirrorRealFile("example/ResolverCaptureCallerTwo.java", "package example; public class ResolverCaptureCallerTwo { void call(){ new ResolverCapture().createInvoice(\"x\",\"y\",2);} }")
        mirrorRealFile("example/ExistingCapture.java", "package example; public class ExistingCapture { public ExistingCapture(String c,int d){}}")
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val range = rangeForMethod(path, "createInvoice")
        // new_top_level should capture callers
        val resTop = resolver.resolve(project, path, range, listOf("customer","currency"), "new_top_level", "Req", "example.pkg", null, true, false)
        assertTrue(resTop is IntroduceParameterObjectSelectionResolution.Success)
        val prepTop = (resTop as IntroduceParameterObjectSelectionResolution.Success).preparation
        assertTrue(prepTop.affectedVirtualFiles.any { it.path.contains("ResolverCapture.java") })
        assertTrue(prepTop.affectedVirtualFiles.any { it.path.contains("ResolverCaptureCallerOne.java") })
        assertTrue(prepTop.affectedVirtualFiles.any { it.path.contains("ResolverCaptureCallerTwo.java") })
        // existing should also include existing class file
        val resExist = resolver.resolve(project, path, range, listOf("customer","currency"), "existing_class", null, null, "example.ExistingCapture", true, false)
        assertTrue(resExist is IntroduceParameterObjectSelectionResolution.Success)
        val prepExist = (resExist as IntroduceParameterObjectSelectionResolution.Success).preparation
        assertTrue(prepExist.affectedVirtualFiles.any { it.path.contains("ExistingCapture") })
    }

    private fun rangeForMethod(path: String, methodName: String): SourceRange {
        val doc = document(path)
        val off = doc.text.indexOf(methodName)
        assertTrue("method $methodName not found in $path", off >= 0)
        return range(doc, off, off + methodName.length)
    }

    private fun range(doc: Document, startOff: Int, endOff: Int): SourceRange {
        fun pos(off: Int): Pair<Int,Int> {
            val line = doc.getLineNumber(off)
            return (line+1) to (off - doc.getLineStartOffset(line) + 1)
        }
        val (sl, sc) = pos(startOff)
        val (el, ec) = pos(endOff)
        return SourceRange(sl, sc, el, ec)
    }

    private fun document(path: String): Document {
        val vf = LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, path).toString())!!
        return FileDocumentManager.getInstance().getDocument(vf)!!
    }

    private fun mirrorRealFile(path: String, text: String): com.intellij.openapi.vfs.VirtualFile {
        val t = Path.of(project.basePath!!, path)
        Files.createDirectories(t.parent)
        if (!Files.exists(t)) Files.createFile(t)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(t.toString())!!
        WriteAction.run<RuntimeException> { VfsUtil.saveText(vf, text) }
        return vf
    }

    private fun assertFailure(res: IntroduceParameterObjectSelectionResolution, expected: McpRefactoringErrorCode) {
        assertTrue("expected Failure($expected) but was $res", res is IntroduceParameterObjectSelectionResolution.Failure)
        val f = res as IntroduceParameterObjectSelectionResolution.Failure
        assertEquals(expected, f.code)
        assertTrue(f.message.isNotBlank())
    }
}
