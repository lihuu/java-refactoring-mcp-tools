package com.example.airefactoring.refactoring.changesignature

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Ref
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.refactoring.changeSignature.ChangeSignatureProcessor
import com.intellij.refactoring.changeSignature.ChangeSignatureProcessorBase
import com.intellij.refactoring.changeSignature.ChangeSignatureUsageProcessor
import com.intellij.refactoring.changeSignature.ParameterInfoImpl
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.IncorrectOperationException
import com.intellij.util.VisibilityUtil
import com.intellij.util.containers.MultiMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** Executes one fully prepared Java Change Signature request through IntelliJ's native processor. */
class IntellijChangeSignatureExecutor : ChangeSignatureExecutor {

    override suspend fun addParameter(
        project: Project,
        preparation: ChangeSignaturePreparation,
    ): ChangeSignatureExecutionResult = withContext(Dispatchers.EDT) {
        val method = preparation.methodPointer.element
            ?.takeIf { it.isValid }
            ?: throw ChangeSignaturePreparationException(
                "The target method changed before Change Signature could run.",
            )
        if (method.parameterList.text != preparation.originalParameterListText) {
            throw ChangeSignaturePreparationException(
                "The target method signature changed before Change Signature could run.",
            )
        }
        val parameterType = try {
            JavaPsiFacade.getElementFactory(project)
                .createTypeFromText(preparation.parameterTypeText, method)
        } catch (e: IncorrectOperationException) {
            throw ChangeSignaturePreparationException(
                "The requested parameter type is no longer valid in the target method.",
                e,
            )
        }
        val parameters = ParameterInfoImpl.fromMethod(method).toMutableList()
        parameters.add(
            preparation.parameterPosition - 1,
            ParameterInfoImpl(
                -1,
                preparation.parameterName,
                parameterType,
                preparation.defaultCallSiteExpression,
            ),
        )
        val processor = HeadlessChangeSignatureProcessor(
            project = project,
            method = method,
            visibility = VisibilityUtil.getVisibilityModifier(method.modifierList),
            parameters = parameters.toTypedArray(),
        )
        processor.setPreviewUsages(false)
        processor.run()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        saveAffectedDocuments(project, preparation.affectedFiles)

        ChangeSignatureExecutionResult(
            methodName = preparation.methodName,
            declarationFilePath = preparation.declarationFilePath,
            parameterName = preparation.parameterName,
            parameterType = preparation.canonicalParameterType,
            parameterPosition = preparation.parameterPosition,
            defaultCallSiteExpression = preparation.defaultCallSiteExpression,
            updatedCallSiteCount = preparation.updatedCallSiteCount,
            affectedFiles = preparation.affectedFiles,
            summary = "Added parameter '${preparation.parameterName}' at position " +
                "${preparation.parameterPosition} and updated " +
                "${preparation.updatedCallSiteCount} call sites.",
        )
    }

    private fun saveAffectedDocuments(project: Project, affectedFiles: List<String>) {
        val basePath = project.basePath ?: return
        val fileDocumentManager = FileDocumentManager.getInstance()
        affectedFiles.forEach { relativePath ->
            val absolutePath = Path.of(basePath).resolve(relativePath).normalize().toString()
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
                ?: return@forEach
            fileDocumentManager.getDocument(virtualFile)?.let(fileDocumentManager::saveDocument)
        }
    }

    private class HeadlessChangeSignatureProcessor(
        project: Project,
        method: PsiMethod,
        visibility: String,
        parameters: Array<ParameterInfoImpl>,
    ) : ChangeSignatureProcessor(
        project,
        method,
        false,
        visibility,
        method.name,
        method.returnType,
        parameters,
    ) {
        override fun preprocessUsages(usages: Ref<Array<UsageInfo>>): Boolean {
            for (usageProcessor in ChangeSignatureUsageProcessor.EP_NAME.extensionList) {
                if (!usageProcessor.setupDefaultValues(changeInfo, usages, myProject)) {
                    return false
                }
            }

            val conflicts = MultiMap<PsiElement, String>()
            ChangeSignatureProcessorBase.collectConflictsFromExtensions(
                usages,
                conflicts,
                changeInfo,
            )
            RenameUtil.addConflictDescriptions(usages.get(), conflicts)
            if (!conflicts.isEmpty) {
                throw ChangeSignatureConflictException(
                    conflicts.values().distinct().joinToString(separator = "; "),
                )
            }

            // V1 keeps visibility and return type unchanged and rejects method hierarchies during
            // preparation, so the remaining platform preprocessing branches cannot apply here.
            val executableUsages = usages.get().toMutableSet()
            RenameUtil.removeConflictUsages(executableUsages)
            usages.set(executableUsages.toTypedArray())
            prepareSuccessful()
            return true
        }
    }
}
