package com.example.airefactoring.refactoring

import com.example.airefactoring.context.RefactorContext
import com.example.airefactoring.resolver.SymbolKind
import com.example.airefactoring.settings.AiRefactoringSettings
import com.example.airefactoring.validator.ValidationResult
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlinx.serialization.json.JsonObject

/**
 * Pure-ish unit test for [RefactoringRegistry] using fake handlers that decide solely by caret
 * offset. Uses [LightJavaCodeInsightFixtureTestCase] to obtain a real [PsiFile] + [PsiNamedElement]
 * (the registry contract needs them); the fakes never touch the platform otherwise.
 */
class RefactoringRegistryTest : LightJavaCodeInsightFixtureTestCase() {

    /** A fake handler that only resolves when caretOffset equals [matchOffset]. */
    private class FakeHandler(
        override val id: String,
        private val matchOffset: Int,
        private val element: PsiNamedElement,
    ) : RefactoringHandler {
        override val displayName: String = "Fake $id"
        override val notApplicableMessage: String = "Fake $id not applicable."

        override fun resolve(file: PsiFile, editor: Editor, caretOffset: Int): RefactorTarget? {
            if (caretOffset != matchOffset) return null
            return RefactorTarget(
                element = element,
                context = RefactorContext(
                    language = "JAVA",
                    filePath = "Fake.java",
                    symbolName = element.name ?: "x",
                    symbolKind = SymbolKind.LOCAL_VARIABLE,
                    enclosingClass = null,
                    enclosingMethod = null,
                    symbolType = null,
                    nearbyCode = "",
                ),
            )
        }

        override fun promptContribution(target: RefactorTarget): PromptContribution = TODO()
        override fun parse(actionJson: JsonObject): RefactorOperation = TODO()
        override fun validate(operation: RefactorOperation, target: RefactorTarget, project: Project): ValidationResult = TODO()
        override fun execute(operation: RefactorOperation, target: RefactorTarget, project: Project, settings: AiRefactoringSettings.State): String = TODO()
    }

    private fun namedElement(): PsiNamedElement {
        val file = myFixture.configureByText(
            "Fake.java",
            "public class Fake { void m() { int userCount = 0; userCount++; } }",
        )
        // Any PsiNamedElement will do; grab the class declaration.
        val element = com.intellij.psi.util.PsiTreeUtil.findChildOfType(file, com.intellij.psi.PsiNamedElement::class.java)
        assertNotNull("expected a PsiNamedElement in the fixture file", element)
        return element!!
    }

    fun testFirstApplicableWins() {
        val el = namedElement()
        val file = myFixture.file
        val a = FakeHandler(id = "a", matchOffset = 1, element = el)
        val b = FakeHandler(id = "b", matchOffset = 2, element = el)
        val registry = RefactoringRegistry(listOf(a, b))

        val r1 = registry.resolve(file, myFixture.editor, 1)
        assertNotNull(r1)
        assertSame(a, r1!!.first)
        assertEquals("a", r1.first.id)

        val r2 = registry.resolve(file, myFixture.editor, 2)
        assertNotNull(r2)
        assertSame(b, r2!!.first)
        assertEquals("b", r2.first.id)

        assertNull(registry.resolve(file, myFixture.editor, 99))
    }

    fun testOrderMattersWhenBothMatch() {
        val el = namedElement()
        val file = myFixture.file
        val a2 = FakeHandler(id = "a2", matchOffset = 1, element = el)
        val a = FakeHandler(id = "a", matchOffset = 1, element = el)
        val registry = RefactoringRegistry(listOf(a2, a))

        val r = registry.resolve(file, myFixture.editor, 1)
        assertNotNull(r)
        assertSame(a2, r!!.first)
        assertEquals("a2", r.first.id)
    }

    fun testAllReturnsHandlersInOrder() {
        val el = namedElement()
        val a = FakeHandler(id = "a", matchOffset = 1, element = el)
        val b = FakeHandler(id = "b", matchOffset = 2, element = el)
        val registry = RefactoringRegistry(listOf(a, b))

        val all = registry.all()
        assertEquals(2, all.size)
        assertSame(a, all[0])
        assertSame(b, all[1])
    }
}
