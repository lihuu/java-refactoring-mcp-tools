package com.example.airefactoring.refactoring.introduceparameterobject

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.refactoring.PackageWrapper
import com.intellij.refactoring.changeSignature.ParameterInfoImpl
import com.intellij.refactoring.introduceParameterObject.IntroduceParameterObjectProcessor
import com.intellij.refactoring.introduceparameterobject.JavaIntroduceParameterObjectClassDescriptor
import com.intellij.refactoring.introduceparameterobject.JavaIntroduceParameterObjectDelegate
import com.intellij.refactoring.move.moveClassesOrPackages.MultipleRootsMoveDestination

/**
 * Real platform-backed [IntroduceParameterObjectExecutor]. Drives IntelliJ's
 * [IntroduceParameterObjectProcessor] to fold a method's parameters into a new inner class.
 *
 * The real refactoring requires the platform refactoring infrastructure, so this class is NOT
 * unit-tested; it is exercised only via `runIde`, consistent with `IntellijExtractMethodExecutor`.
 */
class IntellijIntroduceParameterObjectExecutor : IntroduceParameterObjectExecutor {

    override fun introduce(project: Project, method: PsiMethod, className: String): String {
        // Capture the method name up front: the PSI may be modified by the refactoring.
        val methodName = method.name
        val app = ApplicationManager.getApplication()
        val command = Runnable {
            val delegate = JavaIntroduceParameterObjectDelegate()
            val allParams: List<ParameterInfoImpl> = delegate.getAllMethodParameters(method)
            val packageName = (method.containingFile as? PsiJavaFile)?.packageName ?: ""
            val moveDestination = MultipleRootsMoveDestination(PackageWrapper(method.manager, packageName))
            val descriptor = JavaIntroduceParameterObjectClassDescriptor(
                className,
                packageName,
                moveDestination,
                false,   // useExistingClass
                true,    // createInnerClass — build as inner class of the source class (most stable headless)
                "",      // existingClassName (unused)
                allParams.toTypedArray(),
                method,
                true,    // generateAccessors
            )
            val processor = IntroduceParameterObjectProcessor(method, descriptor, allParams, false) // keepMethodAsDelegate=false
            processor.setPreviewUsages(false)
            processor.run()
        }
        if (app.isUnitTestMode) {
            CommandProcessor.getInstance().executeCommand(project, command, "AI Introduce Parameter Object", null)
        } else {
            app.invokeAndWait {
                CommandProcessor.getInstance().executeCommand(project, command, "AI Introduce Parameter Object", null)
            }
        }
        return "Introduced parameter object '$className' for method '$methodName'."
    }
}
