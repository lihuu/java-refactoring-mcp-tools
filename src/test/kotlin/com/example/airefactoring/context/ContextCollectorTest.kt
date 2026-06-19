package com.example.airefactoring.context

import com.example.airefactoring.resolver.SymbolKind
import com.example.airefactoring.resolver.SymbolResolver
import com.example.airefactoring.resolver.ResolvedSymbol
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class ContextCollectorTest : LightJavaCodeInsightFixtureTestCase() {
    override fun getTestDataPath(): String = "src/test/testData/context"

    private val resolver = SymbolResolver()
    private val collector = ContextCollector()

    private fun resolved(): ResolvedSymbol.Resolved {
        val r = resolver.resolve(myFixture.file, myFixture.editor.caretModel.offset)
        check(r is ResolvedSymbol.Resolved) { "fixture must resolve" }
        return r
    }

    fun testLocalVarUsesMethodBodyAsNearbyCode() {
        myFixture.configureByFile("MethodScope.java")
        val r = resolved()
        val ctx = collector.collect(myFixture.file, r.element, r.kind)
        assertEquals("java", ctx.language)
        assertEquals("userCount", ctx.symbolName)
        assertEquals(SymbolKind.LOCAL_VARIABLE, ctx.symbolKind)
        assertEquals("MethodScope", ctx.enclosingClass)
        assertEquals("run", ctx.enclosingMethod)
        assertEquals("int", ctx.symbolType)
        assertTrue(ctx.nearbyCode.contains("userCount++"))
        assertFalse(ctx.nearbyCode.contains("class MethodScope"))
    }

    fun testFieldUsesClassBodyAsNearbyCode() {
        myFixture.configureByFile("ClassScope.java")
        val r = resolved()
        val ctx = collector.collect(myFixture.file, r.element, r.kind)
        assertEquals(SymbolKind.FIELD, ctx.symbolKind)
        assertEquals("ClassScope", ctx.enclosingClass)
        assertNull(ctx.enclosingMethod)
        assertEquals("String", ctx.symbolType)
        assertTrue(ctx.nearbyCode.contains("class ClassScope"))
        assertTrue(ctx.nearbyCode.contains("greet"))
    }

    fun testNearbyCodeIsCappedAt4000Chars() {
        val filler = "    int x = 0;\n".repeat(2000)  // ~30 KB method body
        myFixture.configureByText(
            "Big.java",
            """
            public class Big {
                void m() {
                    int <caret>target = 0;
                    $filler
                }
            }
            """.trimIndent()
        )
        val r = resolved()
        val ctx = collector.collect(myFixture.file, r.element, r.kind)
        assertTrue(
            "nearbyCode must be capped, was ${ctx.nearbyCode.length}",
            ctx.nearbyCode.length <= ContextCollector.MAX_NEARBY_CODE_CHARS
        )
    }
}
