package com.example.airefactoring.refactor

import com.example.airefactoring.resolver.ResolvedSymbol
import com.example.airefactoring.resolver.SymbolResolver
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class IntellijRenameExecutorTest : LightJavaCodeInsightFixtureTestCase() {
    override fun getTestDataPath(): String = "src/test/testData/refactor"

    fun testRenameLocalUpdatesAllUsages() {
        myFixture.configureByFile("RenameLocal.java")
        val resolver = SymbolResolver()
        val r = resolver.resolve(myFixture.file, myFixture.editor.caretModel.offset)
        check(r is ResolvedSymbol.Resolved)
        IntellijRenameExecutor().rename(project, r.element, "totalUsers", preview = false)
        myFixture.checkResultByFile("RenameLocal_after.java")
    }
}
