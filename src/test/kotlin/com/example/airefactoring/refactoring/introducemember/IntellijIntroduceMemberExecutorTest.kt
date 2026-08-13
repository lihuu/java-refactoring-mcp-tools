package com.example.airefactoring.refactoring.introducemember

import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.dispatchAllEventsInIdeEventQueue
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IntellijIntroduceMemberExecutorTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = IntroduceMemberSelectionResolver()
    private val executor = IntellijIntroduceMemberExecutor()

    // --- Step 2: profile tests ---

    fun testConstantProfileCreatesPrivateStaticFinalFieldSelectedOnly() {
        val selection = resolveFirstExpression(
            "ConstantMember.java",
            "class ConstantMember { int value() { return 12 + 12; } }",
            "12",
        )

        val result = runExecutor {
            executor.introduce(project, selection, "BASE", IntroduceMemberProfile.Constant)
        }
        PsiDocumentManager.getInstance(project).commitDocument(selection.document)

        assertEquals("BASE", result.requestedFieldName)
        assertEquals("BASE", result.actualFieldName)
        assertEquals("int", result.fieldType)
        assertEquals(listOf("private", "static", "final"), result.fieldModifiers)
        assertEquals("FIELD_DECLARATION", result.initializationPlace)
        assertEquals("Introduced constant 'BASE'.", result.summary)
        val field = selection.containingClass.fields.single { it.name == "BASE" }
        assertTrue("constant must be private", field.hasModifierProperty(PsiModifier.PRIVATE))
        assertTrue("constant must be static", field.hasModifierProperty(PsiModifier.STATIC))
        assertTrue("constant must be final", field.hasModifierProperty(PsiModifier.FINAL))
        assertEquals("12", field.initializer!!.text)
        assertEquals(
            "selected-only replacement: one new initializer plus one untouched original occurrence",
            2,
            Regex("\\b12\\b").findAll(selection.document.text).count(),
        )
    }

    fun testInstanceFinalFieldProfileCreatesPrivateFinalNonStaticFieldInDeclaration() {
        val selection = resolveFirstExpression(
            "FieldMember.java",
            "class FieldMember { int value() { return compute() + compute(); } int compute() { return 2; } }",
            "compute()",
        )

        val result = runExecutor {
            executor.introduce(project, selection, "result", IntroduceMemberProfile.InstanceFinalField)
        }
        PsiDocumentManager.getInstance(project).commitDocument(selection.document)

        assertEquals("result", result.actualFieldName)
        assertEquals("int", result.fieldType)
        assertEquals(listOf("private", "final"), result.fieldModifiers)
        assertEquals("FIELD_DECLARATION", result.initializationPlace)
        assertEquals("Introduced field 'result'.", result.summary)
        val field = selection.containingClass.fields.single { it.name == "result" }
        assertTrue("field must be private", field.hasModifierProperty(PsiModifier.PRIVATE))
        assertTrue("field must be final", field.hasModifierProperty(PsiModifier.FINAL))
        assertFalse("field must not be static", field.hasModifierProperty(PsiModifier.STATIC))
        assertEquals("compute()", field.initializer!!.text)
        val calls = PsiTreeUtil.findChildrenOfType(selection.file, PsiMethodCallExpression::class.java)
            .count { it.text == "compute()" }
        assertEquals(
            "selected-only replacement: one new initializer plus one untouched original call",
            2,
            calls,
        )
    }

    fun testReportsReferenceFieldType() {
        val selection = resolveFirstExpression(
            "ReferenceMember.java",
            "class ReferenceMember { String greet() { return \"hello\"; } }",
            "\"hello\"",
        )

        val result = runExecutor {
            executor.introduce(project, selection, "greeting", IntroduceMemberProfile.InstanceFinalField)
        }

        assertEquals("java.lang.String", result.fieldType)
        assertEquals("greeting", result.actualFieldName)
    }

    fun testReportsGenericFieldType() {
        val selection = resolveFirstExpression(
            "GenericMember.java",
            "import java.util.ArrayList; class GenericMember { ArrayList<String> names() { return new ArrayList<String>(); } }",
            "new ArrayList<String>()",
        )

        val result = runExecutor {
            executor.introduce(project, selection, "names", IntroduceMemberProfile.InstanceFinalField)
        }

        assertEquals("ArrayList<String>", result.fieldType)
    }

    fun testLiteralCanBeIntroducedAsFieldWithoutPluginRerouting() {
        val selection = resolveFirstExpression(
            "LiteralFieldMember.java",
            "class LiteralFieldMember { int value() { return 12; } }",
            "12",
        )

        val result = runExecutor {
            executor.introduce(project, selection, "twelve", IntroduceMemberProfile.InstanceFinalField)
        }
        PsiDocumentManager.getInstance(project).commitDocument(selection.document)

        val field = selection.containingClass.fields.single { it.name == "twelve" }
        assertFalse(
            "literal introduced as Field must not be rerouted to a static constant",
            field.hasModifierProperty(PsiModifier.STATIC),
        )
        assertTrue(field.hasModifierProperty(PsiModifier.PRIVATE))
        assertTrue(field.hasModifierProperty(PsiModifier.FINAL))
        assertEquals("12", field.initializer!!.text)
        assertEquals(listOf("private", "final"), result.fieldModifiers)
    }

    // --- Step 3: safety tests ---

    fun testCollisionRenamingUsesIntellijUniqueName() {
        val selection = resolveFirstExpression(
            "CollisionMember.java",
            "class CollisionMember { private int count; int value() { return 12; } }",
            "12",
        )

        val result = runExecutor {
            executor.introduce(project, selection, "count", IntroduceMemberProfile.Constant)
        }
        PsiDocumentManager.getInstance(project).commitDocument(selection.document)

        assertFalse(
            "conflict must change the requested name",
            result.actualFieldName == "count",
        )
        assertTrue(result.actualFieldName.startsWith("count"))
        assertTrue(
            selection.containingClass.fields.any { it.name == result.actualFieldName },
        )
    }

    fun testUnsupportedNativeInitializationThrowsPreparationWithoutUiOrMutation() {
        val selection = selectionForExpression(
            "EnumLabelMember.java",
            """
                enum Color { RED, GREEN }
                class EnumLabelMember {
                    int shade(Color color) {
                        switch (color) {
                            case RED: return 1;
                            default: return 0;
                        }
                    }
                }
            """.trimIndent(),
        )
        val original = selection.document.text
        val throwingDialog = object : TestDialog {
            override fun show(message: String): Int =
                throw AssertionError("Introduce Member must not open a dialog: $message")
        }
        val previousDialog = TestDialogManager.setTestDialog(throwingDialog)

        try {
            try {
                runExecutor {
                    executor.introduce(
                        project,
                        selection,
                        "red",
                        IntroduceMemberProfile.InstanceFinalField,
                    )
                }
                fail("expected native refusal to surface as a typed exception")
            } catch (e: IntroduceMemberPreparationException) {
                assertTrue("preparation message must not be blank", e.message!!.isNotBlank())
            }
        } finally {
            TestDialogManager.setTestDialog(previousDialog)
        }
        assertEquals(original, selection.document.text)
    }

    fun testStaleExpressionThrowsPreparationExceptionWithoutMutation() {
        val selection = resolveFirstExpression(
            "StaleMember.java",
            "class StaleMember { int value() { return 12; } }",
            "12",
        )
        val invalidated = "class StaleMember {}"
        WriteCommandAction.runWriteCommandAction(project) {
            selection.document.setText(invalidated)
        }
        PsiDocumentManager.getInstance(project).commitDocument(selection.document)

        try {
            runExecutor {
                executor.introduce(project, selection, "stale", IntroduceMemberProfile.Constant)
            }
            fail("expected stale-expression preparation failure")
        } catch (e: IntroduceMemberPreparationException) {
            assertTrue("preparation message must not be blank", e.message!!.isNotBlank())
        }
        assertEquals(invalidated, selection.document.text)
    }

    fun testDoesNotSaveUnrelatedDirtyDocument() {
        val selection = resolveFirstExpression(
            "SaveTargetMember.java",
            "class SaveTargetMember { int value() { return 12; } }",
            "12",
        )
        val otherDocument = createAndDirtyRealJavaFile("OtherDirtyMember.java")
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(otherDocument))

        runExecutor {
            executor.introduce(project, selection, "BASE", IntroduceMemberProfile.Constant)
        }

        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(otherDocument))
    }

    fun testConstantProfileUndoRestoresExactOriginalSource() {
        val selection = resolveFirstExpression(
            "UndoConstantMember.java",
            "class UndoConstantMember { int value() { return 12; } }",
            "12",
        )
        val original = selection.document.text

        runExecutor {
            executor.introduce(project, selection, "BASE", IntroduceMemberProfile.Constant)
        }

        val undoManager = UndoManager.getInstance(project)
        assertTrue("Introduce Constant must be available as one global Undo", undoManager.isUndoAvailable(null))
        val previousDialog = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undoManager.undo(null)
        } finally {
            TestDialogManager.setTestDialog(previousDialog)
        }
        PsiDocumentManager.getInstance(project).commitDocument(selection.document)
        assertEquals(original, selection.document.text)
    }

    fun testInstanceFinalFieldProfileUndoRestoresExactOriginalSource() {
        val selection = resolveFirstExpression(
            "UndoFieldMember.java",
            "class UndoFieldMember { int value() { return compute(); } int compute() { return 2; } }",
            "compute()",
        )
        val original = selection.document.text

        runExecutor {
            executor.introduce(project, selection, "computed", IntroduceMemberProfile.InstanceFinalField)
        }

        val undoManager = UndoManager.getInstance(project)
        assertTrue("Introduce Field must be available as one global Undo", undoManager.isUndoAvailable(null))
        val previousDialog = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undoManager.undo(null)
        } finally {
            TestDialogManager.setTestDialog(previousDialog)
        }
        PsiDocumentManager.getInstance(project).commitDocument(selection.document)
        assertEquals(original, selection.document.text)
    }

    // --- helpers ---

    private fun resolveFirstExpression(
        fileName: String,
        source: String,
        expressionText: String,
    ): IntroduceMemberSelection {
        val virtualFile = mirrorRealFile(fileName, source)
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        val startOffset = document.text.indexOf(expressionText)
        assertTrue("expression '$expressionText' missing", startOffset >= 0)
        val result = resolver.resolve(
            project,
            fileName,
            range(document, startOffset, startOffset + expressionText.length),
        )
        assertTrue(
            "expected successful selection but was $result",
            result is IntroduceMemberSelectionResolution.Success,
        )
        return (result as IntroduceMemberSelectionResolution.Success).selection
    }

    private fun selectionForExpression(
        fileName: String,
        source: String,
    ): IntroduceMemberSelection {
        val virtualFile = mirrorRealFile(fileName, source)
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val file = PsiManager.getInstance(project).findFile(virtualFile) as PsiJavaFile
        val expression = PsiTreeUtil.findChildrenOfType(file, PsiReferenceExpression::class.java)
            .first { it.text == "RED" }
        val containingClass = PsiTreeUtil.getParentOfType(expression, PsiClass::class.java, false)!!
        return IntroduceMemberSelection(
            file,
            document,
            expression,
            expression.type!!,
            containingClass,
        )
    }

    private fun createAndDirtyRealJavaFile(fileName: String): Document {
        val virtualFile = mirrorRealFile(fileName, "class OtherDirtyMember { int value = 1; }")
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(document.textLength, "\n// dirty")
        }
        return document
    }

    private fun mirrorRealFile(fileName: String, text: String): VirtualFile {
        val target = Path.of(project.basePath!!, fileName)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(target.toString())!!
    }

    private fun range(document: Document, startOffset: Int, endOffset: Int): SourceRange {
        fun position(offset: Int): Pair<Int, Int> {
            val line = document.getLineNumber(offset)
            return (line + 1) to (offset - document.getLineStartOffset(line) + 1)
        }

        val (startLine, startColumn) = position(startOffset)
        val (endLine, endColumn) = position(endOffset)
        return SourceRange(startLine, startColumn, endLine, endColumn)
    }

    private fun <T> runExecutor(block: suspend () -> T): T {
        val pool = Executors.newSingleThreadExecutor()
        return try {
            val future = pool.submit<T> { runBlocking { block() } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (System.nanoTime() < deadline && !future.isDone) {
                dispatchAllEventsInIdeEventQueue()
                Thread.sleep(1)
            }
            try {
                future.get(1, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
