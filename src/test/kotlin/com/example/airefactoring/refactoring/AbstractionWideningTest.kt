package com.example.airefactoring.refactoring

import com.example.airefactoring.settings.AiRefactoringSettings
import com.example.airefactoring.validator.ValidationResult
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlinx.serialization.json.JsonObject

/**
 * Readiness PROOF for the widened refactoring abstraction. A test-only fake non-symbol handler
 * drives resolve + promptContribution + PromptEnvelope with a NON-named [PsiElement] and a
 * handler-owned [RefactorContextData] — proving the agnostic core makes zero symbol assumptions.
 * No production extract-method exists; this is purely a test-side fake.
 */
class AbstractionWideningTest : LightJavaCodeInsightFixtureTestCase() {

    private class FakeContext(val blob: String) : RefactorContextData {
        override fun toPromptJson() = """{"selection":"$blob"}"""
    }

    /** A fake non-symbol handler that resolves a PsiCodeBlock (NOT a PsiNamedElement). */
    private class NonSymbolHandler(private val element: PsiElement) : RefactoringHandler {
        override val id = "extract_method"
        override val displayName = "code selection"

        override fun resolve(file: PsiFile, caretOffset: Int): RefactorTarget? =
            RefactorTarget(
                element = element,
                context = FakeContext("x + y"),
            )

        override fun promptContribution(target: RefactorTarget): PromptContribution =
            PromptContribution(
                systemFragment = "Extract the selected code into a new method.",
                jsonShapeExample = """{"action":"extract_method","methodName":"<identifier>"}""",
                question = "Should this selection be extracted into a method?",
            )

        override fun parse(actionJson: JsonObject): RefactorOperation = TODO()
        override fun validate(operation: RefactorOperation, target: RefactorTarget, project: Project): ValidationResult = TODO()
        override fun execute(operation: RefactorOperation, target: RefactorTarget, project: Project, settings: AiRefactoringSettings.State): String = TODO()
    }

    private fun codeBlockElement(): PsiElement {
        val file = myFixture.configureByText(
            "Calc.java",
            "public class Calc { int add(int x, int y) { return x + y; } }",
        )
        val block = PsiTreeUtil.findChildOfType(file, PsiCodeBlock::class.java)
        assertNotNull("expected a PsiCodeBlock in the fixture file", block)
        return block!!
    }

    fun testRegistryRoutesToNonSymbolHandlerWithNonNamedElement() {
        val element = codeBlockElement()
        assertFalse("the proof element must NOT be a PsiNamedElement", element is PsiNamedElement)
        val handler = NonSymbolHandler(element)
        val registry = RefactoringRegistry(listOf(handler))

        val resolved = registry.resolve(myFixture.file, 0)
        assertNotNull(resolved)
        assertSame(handler, resolved!!.first)
        assertSame(element, resolved.second.element)
        assertFalse(resolved.second.element is PsiNamedElement)
    }

    fun testEnvelopeUsesHandlerQuestionAndHandlerContextForNonSymbol() {
        val element = codeBlockElement()
        val handler = NonSymbolHandler(element)
        val target = handler.resolve(myFixture.file, 0)!!

        val (system, user) = PromptEnvelope.assemble(handler.promptContribution(target), target)

        assertTrue(user.contains("Should this selection be extracted into a method?"))
        assertTrue(user.contains("\"selection\""))
        assertTrue(system.contains("extract_method"))
    }
}
