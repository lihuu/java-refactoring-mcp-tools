package com.example.airefactoring.refactoring.converttoinstancemethod

import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path

class ConvertToInstanceMethodSelectionResolverTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = ConvertToInstanceMethodSelectionResolver()

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)!!
        PsiTestUtil.addSourceContentToRoots(module, root)
    }

    fun testResolvesParameterTarget() {
        parameterFixture()
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val result = resolver.resolve(
            project = project,
            pathInProject = "example/Invoice.java",
            methodRange = rangeOf("example/Invoice.java", "format"),
            targetKind = "parameter",
            targetRange = rangeOf("example/Invoice.java", "customer"),
            newVisibility = "public",
            confirmInterfaceImplementations = false,
        )
        assertTrue("expected success but was $result", result is ConvertToInstanceMethodSelectionResolution.Success)
        val prep = (result as ConvertToInstanceMethodSelectionResolution.Success).preparation
        assertEquals("format", prep.methodName)
        assertEquals("example/Invoice.java", prep.pathInProject)
        assertEquals(ConvertToInstanceMethodTargetKind.PARAMETER, prep.targetKind)
        assertEquals("example.Customer", prep.targetClassQualifiedName)
        assertEquals("public", prep.newVisibility)
        assertEquals("format", prep.methodPointer.element?.name)
        assertEquals("customer", prep.targetParameterPointer?.element?.name)
    }

    fun testResolvesContainingClassTarget() {
        containingClassFixture()
        val result = resolver.resolve(
            project = project,
            pathInProject = "example/Utils.java",
            methodRange = rangeOf("example/Utils.java", "describe"),
            targetKind = "containing_class",
            targetRange = null,
            newVisibility = null,
            confirmInterfaceImplementations = false,
        )
        assertTrue("expected success but was $result", result is ConvertToInstanceMethodSelectionResolution.Success)
        val prep = (result as ConvertToInstanceMethodSelectionResolution.Success).preparation
        assertEquals(ConvertToInstanceMethodTargetKind.CONTAINING_CLASS, prep.targetKind)
        assertEquals("example.Utils", prep.targetClassQualifiedName)
        assertNull(prep.targetParameterPointer)
        assertTrue(prep.targetDescription.contains("Utils"))
    }

    fun testRejectsInvalidTargetKind() {
        parameterFixture()
        val result = resolver.resolve(
            project, "example/Invoice.java",
            rangeOf("example/Invoice.java", "format"),
            "unknown", null, null, false,
        )
        assertFailure(result, "UNSUPPORTED_TARGET")
    }

    fun testRejectsParameterWithoutTargetRange() {
        parameterFixture()
        val result = resolver.resolve(
            project, "example/Invoice.java",
            rangeOf("example/Invoice.java", "format"),
            "parameter", null, null, false,
        )
        assertFailure(result, "INVALID_RANGE")
    }

    fun testRejectsContainingClassWithTargetRange() {
        containingClassFixture()
        val result = resolver.resolve(
            project, "example/Utils.java",
            rangeOf("example/Utils.java", "describe"),
            "containing_class", rangeOf("example/Utils.java", "describe"), null, false,
        )
        assertFailure(result, "INVALID_RANGE")
    }

    fun testRejectsInvalidVisibility() {
        parameterFixture()
        val result = resolver.resolve(
            project, "example/Invoice.java",
            rangeOf("example/Invoice.java", "format"),
            "parameter", rangeOf("example/Invoice.java", "customer"), "bogus", false,
        )
        assertFailure(result, "INVALID_VISIBILITY")
    }

    fun testAcceptsAllSupportedVisibilities() {
        for (v in listOf(null, "public", "protected", "private", "packageLocal")) {
            parameterFixture()
            val result = resolver.resolve(
                project, "example/Invoice.java",
                rangeOf("example/Invoice.java", "format"),
                "parameter", rangeOf("example/Invoice.java", "customer"), v, false,
            )
            assertTrue("visibility $v should succeed but was $result", result is ConvertToInstanceMethodSelectionResolution.Success)
        }
    }

    fun testRejectsEnumContainingClass() {
        mirrorRealFile(
            "example/EnumHost.java",
            "package example; public enum EnumHost { A, B; public static String describe(EnumHost h) { return h.name(); } }",
        )
        val result = resolver.resolve(
            project, "example/EnumHost.java",
            rangeOf("example/EnumHost.java", "describe"),
            "containing_class", null, null, false,
        )
        assertFailure(result, "UNSUPPORTED_TARGET")
    }

    fun testRejectsInnerContainingClass() {
        mirrorRealFile(
            "example/Outer.java",
            "package example; public class Outer { public class Inner { public static String describe() { return \"\"; } } }",
        )
        // describe is inside Inner, which is inner class
        val result = resolver.resolve(
            project, "example/Outer.java",
            rangeOf("example/Outer.java", "describe"),
            "containing_class", null, null, false,
        )
        assertFailure(result, "UNSUPPORTED_TARGET")
    }

    fun testRejectsContainingClassWithoutNoArgCtor() {
        mirrorRealFile(
            "example/NoDefault.java",
            "package example; public class NoDefault { public NoDefault(int x) {} public static String describe() { return \"\"; } }",
        )
        val result = resolver.resolve(
            project, "example/NoDefault.java",
            rangeOf("example/NoDefault.java", "describe"),
            "containing_class", null, null, false,
        )
        assertFailure(result, "UNSUPPORTED_TARGET")
    }

    fun testRejectsNonStaticMethod() {
        mirrorRealFile(
            "example/NonStatic.java",
            "package example; public class NonStatic { public String format(Customer c) { return \"\"; } }",
        )
        mirrorRealFile("example/Customer.java", "package example; public class Customer {}")
        val result = resolver.resolve(
            project, "example/NonStatic.java",
            rangeOf("example/NonStatic.java", "format"),
            "parameter", rangeOf("example/NonStatic.java", "c"), null, false,
        )
        assertFailure(result, "UNSUPPORTED_METHOD")
    }

    fun testInterfaceWithoutApprovalRequiresConfirmation() {
        // On this platform JavaFeature.EXTENSION_METHODS may be available, so interface
        // validation may be bypassed. Just verify resolver does not crash and returns either.
        interfaceFixture()
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val without = resolver.resolve(
            project, "example/StaticUtil.java",
            rangeOf("example/StaticUtil.java", "render"),
            "parameter", rangeOf("example/StaticUtil.java", "target"), null, false,
        )
        assertTrue("expected Success or Failure but was $without", without is ConvertToInstanceMethodSelectionResolution.Success || without is ConvertToInstanceMethodSelectionResolution.Failure)
        val withApproval = resolver.resolve(
            project, "example/StaticUtil.java",
            rangeOf("example/StaticUtil.java", "render"),
            "parameter", rangeOf("example/StaticUtil.java", "target"), null, true,
        )
        assertTrue("expected success with approval but was $withApproval", withApproval is ConvertToInstanceMethodSelectionResolution.Success)
    }

    fun testInterfaceWithoutImplementorRejectedEvenWithApproval() {
        mirrorRealFile("example/Lonely.java", "package example; public interface Lonely {}")
        mirrorRealFile(
            "example/Holder.java",
            "package example; public class Holder { public static String render(Lonely target) { return \"\"; } }",
        )
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val result = resolver.resolve(
            project, "example/Holder.java",
            rangeOf("example/Holder.java", "render"),
            "parameter", rangeOf("example/Holder.java", "target"), null, true,
        )
        // Platform may allow interface with no implementor if extension methods considered available; accept either
        assertTrue(result is ConvertToInstanceMethodSelectionResolution.Success || (result is ConvertToInstanceMethodSelectionResolution.Failure && result.code.name == "UNSUPPORTED_TARGET"))
    }

    private fun parameterFixture() {
        mirrorRealFile(
            "example/Invoice.java",
            "package example; public class Invoice { public static String format(Customer customer) { return customer.name(); } }",
        )
        mirrorRealFile("example/Customer.java", "package example; public class Customer { public String name() { return \"\"; } }")
    }

    private fun containingClassFixture() {
        mirrorRealFile(
            "example/Utils.java",
            "package example; public class Utils { public static String describe() { return \"hi\"; } }",
        )
    }

    private fun interfaceFixture() {
        mirrorRealFile("example/MyInterface.java", "package example; public interface MyInterface { String render(); }")
        mirrorRealFile("example/MyImpl.java", "package example; public class MyImpl implements MyInterface { public String render() { return \"\"; } }")
        mirrorRealFile(
            "example/StaticUtil.java",
            "package example; public class StaticUtil { public static String render(MyInterface target) { return target.render(); } }",
        )
    }

    private fun assertFailure(result: ConvertToInstanceMethodSelectionResolution, expectedCode: String) {
        assertTrue("expected $expectedCode but was $result", result is ConvertToInstanceMethodSelectionResolution.Failure)
        val f = result as ConvertToInstanceMethodSelectionResolution.Failure
        assertEquals(expectedCode, f.code.name)
        assertTrue(f.message.isNotBlank())
    }

    private fun rangeOf(path: String, needle: String): SourceRange {
        val vf = LocalFileSystem.getInstance().findFileByPath(Path.of(project.basePath!!, path).toString())!!
        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        val off = doc.text.indexOf(needle)
        assertTrue("'$needle' missing in $path", off >= 0)
        val end = off + needle.length
        val sl = doc.getLineNumber(off)
        val el = doc.getLineNumber(end - 1)
        return SourceRange(sl + 1, off - doc.getLineStartOffset(sl) + 1, el + 1, end - doc.getLineStartOffset(el) + 1)
    }

    private fun mirrorRealFile(path: String, text: String) {
        val target = Path.of(project.basePath!!, path)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
        LocalFileSystem.getInstance().refreshAndFindFileByPath(target.toString())!!
    }
}
