package com.example.airefactoring.refactoring.changesignature

import com.intellij.psi.CommonClassNames
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.changeSignature.ChangeSignatureProcessor
import com.intellij.refactoring.changeSignature.ParameterInfoImpl
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/**
 * Feasibility evidence for invoking Java Change Signature without an editor, action, or dialog.
 *
 * This is intentionally a platform-level spike rather than an MCP tool test. It proves that the
 * native processor can add a parameter and update a call site in another file when all choices
 * normally collected by the UI are supplied programmatically.
 */
class ChangeSignatureHeadlessSpikeTest : LightJavaCodeInsightFixtureTestCase() {

    fun testAddsParameterAndUpdatesCrossFileCallSiteWithoutUi() {
        val serviceFile = myFixture.addFileToProject(
            "example/GreetingService.java",
            """
                package example;

                public class GreetingService {
                    public String greet(String name) {
                        return "Hello, " + name;
                    }
                }
            """.trimIndent(),
        ) as PsiJavaFile
        val callerFile = myFixture.addFileToProject(
            "example/Caller.java",
            """
                package example;

                public class Caller {
                    public String call() {
                        GreetingService service = new GreetingService();
                        return service.greet("Ada");
                    }
                }
            """.trimIndent(),
        )

        val method = serviceFile.classes.single().findMethodsByName("greet", false).single()
        val originalCall = PsiTreeUtil.findChildOfType(callerFile, PsiMethodCallExpression::class.java)
        assertNotNull("fixture call was not parsed:\n${callerFile.text}", originalCall)
        assertSame("fixture call must resolve to the refactored method", method, originalCall!!.resolveMethod())
        val existingParameters = ParameterInfoImpl.fromMethod(method)
        val stringType = JavaPsiFacade.getElementFactory(project).createTypeByFQClassName(
            CommonClassNames.JAVA_LANG_STRING,
            method.resolveScope,
        )
        val parameters = arrayOf(
            existingParameters.single(),
            ParameterInfoImpl(-1, "punctuation", stringType, "\"!\""),
        )

        ChangeSignatureProcessor(
            project,
            method,
            false,
            PsiModifier.PUBLIC,
            method.name,
            method.returnType,
            parameters,
        ).run()
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val changedParameters = serviceFile.classes.single()
            .findMethodsByName("greet", false)
            .single()
            .parameterList
            .parameters
        assertEquals(2, changedParameters.size)
        assertEquals("name", changedParameters[0].name)
        assertEquals("punctuation", changedParameters[1].name)
        assertEquals(CommonClassNames.JAVA_LANG_STRING, changedParameters[1].type.canonicalText)

        val changedCall = PsiTreeUtil.findChildOfType(callerFile, PsiMethodCallExpression::class.java)
        assertNotNull("cross-file method call disappeared:\n${callerFile.text}", changedCall)
        val callArguments = changedCall!!.argumentList.expressions
        assertEquals("cross-file call site was not updated:\n${callerFile.text}", 2, callArguments.size)
        assertEquals("\"Ada\"", callArguments[0].text)
        assertEquals("\"!\"", callArguments[1].text)
    }
}
