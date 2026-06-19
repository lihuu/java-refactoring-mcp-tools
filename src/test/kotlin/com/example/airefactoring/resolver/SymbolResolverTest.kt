package com.example.airefactoring.resolver

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class SymbolResolverTest : LightJavaCodeInsightFixtureTestCase() {
    override fun getTestDataPath(): String = "src/test/testData/resolver"

    private val resolver = SymbolResolver()

    fun testLocalVariableUnderCaret() {
        myFixture.configureByFile("LocalVar.java")
        val result = resolver.resolve(myFixture.file, myFixture.editor.caretModel.offset)
        assertTrue(result is ResolvedSymbol.Resolved)
        result as ResolvedSymbol.Resolved
        assertEquals(SymbolKind.LOCAL_VARIABLE, result.kind)
        assertEquals("userCount", result.element.name)
    }

    fun testFieldUnderCaret() {
        myFixture.configureByFile("Field.java")
        val result = resolver.resolve(myFixture.file, myFixture.editor.caretModel.offset)
        assertTrue(result is ResolvedSymbol.Resolved)
        result as ResolvedSymbol.Resolved
        assertEquals(SymbolKind.FIELD, result.kind)
        assertEquals("userName", result.element.name)
    }

    fun testNoSymbolUnderCaret() {
        myFixture.configureByFile("NoSymbol.java")
        val result = resolver.resolve(myFixture.file, myFixture.editor.caretModel.offset)
        assertTrue(result is ResolvedSymbol.NotFound)
    }

    fun testMethodIsUnsupportedInMvp() {
        myFixture.configureByFile("MethodTarget.java")
        val result = resolver.resolve(myFixture.file, myFixture.editor.caretModel.offset)
        assertTrue(result is ResolvedSymbol.Unsupported)
    }

    fun testNonJavaFile() {
        myFixture.configureByText("notes.txt", "hello <caret>world")
        val result = resolver.resolve(myFixture.file, myFixture.editor.caretModel.offset)
        assertTrue(result is ResolvedSymbol.NotJava)
    }
}
