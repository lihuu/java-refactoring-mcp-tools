package com.example.airefactoring.refactoring

import com.example.airefactoring.mcp.McpRefactoringErrorCode
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import java.nio.file.Path

class JavaSourceTarget(
    val file: PsiJavaFile,
    val document: Document,
    val startOffset: Int,
    val endOffset: Int,
)

class JavaSourcePointTarget(
    val file: PsiJavaFile,
    val document: Document,
    val offset: Int,
)

sealed class JavaSourceTargetResolution {
    data class Success(val target: JavaSourceTarget) : JavaSourceTargetResolution()

    data class Failure(
        val code: McpRefactoringErrorCode,
        val message: String,
    ) : JavaSourceTargetResolution()
}

sealed class JavaSourcePointTargetResolution {
    data class Success(val target: JavaSourcePointTarget) : JavaSourcePointTargetResolution()

    data class Failure(
        val code: McpRefactoringErrorCode,
        val message: String,
    ) : JavaSourcePointTargetResolution()
}

private sealed class JavaFileResolution {
    data class Success(
        val file: PsiJavaFile,
        val document: Document,
    ) : JavaFileResolution()

    data class Failure(
        val code: McpRefactoringErrorCode,
        val message: String,
    ) : JavaFileResolution()
}

/** Resolves a project-relative Java file and validated 1-based coordinates to current offsets. */
class JavaSourceTargetResolver {

    fun resolve(
        project: Project,
        pathInProject: String,
        range: SourceRange,
    ): JavaSourceTargetResolution {
        val source = when (val resolution = resolveFile(project, pathInProject)) {
            is JavaFileResolution.Failure -> return failure(
                resolution.code,
                resolution.message,
            )
            is JavaFileResolution.Success -> resolution
        }
        val startOffset = offset(source.document, range.startLine, range.startColumn)
            ?: return failure(
                McpRefactoringErrorCode.INVALID_RANGE,
                "Invalid start position (${range.startLine}, ${range.startColumn}).",
            )
        val endOffset = offset(source.document, range.endLine, range.endColumn)
            ?: return failure(
                McpRefactoringErrorCode.INVALID_RANGE,
                "Invalid end position (${range.endLine}, ${range.endColumn}).",
            )
        if (startOffset >= endOffset) {
            return failure(
                McpRefactoringErrorCode.INVALID_RANGE,
                "The selection must be non-empty and ordered.",
            )
        }
        return JavaSourceTargetResolution.Success(
            JavaSourceTarget(source.file, source.document, startOffset, endOffset),
        )
    }

    fun resolvePoint(
        project: Project,
        pathInProject: String,
        line: Int,
        column: Int,
    ): JavaSourcePointTargetResolution {
        val source = when (val resolution = resolveFile(project, pathInProject)) {
            is JavaFileResolution.Failure -> return JavaSourcePointTargetResolution.Failure(
                resolution.code,
                resolution.message,
            )
            is JavaFileResolution.Success -> resolution
        }
        val offset = offset(source.document, line, column)
            ?: return JavaSourcePointTargetResolution.Failure(
                McpRefactoringErrorCode.INVALID_RANGE,
                "Invalid position ($line, $column).",
            )
        return JavaSourcePointTargetResolution.Success(
            JavaSourcePointTarget(source.file, source.document, offset),
        )
    }

    private fun resolveFile(
        project: Project,
        pathInProject: String,
    ): JavaFileResolution {
        val base = project.basePath?.let(Path::of)?.toAbsolutePath()?.normalize()
            ?: return fileFailure(
                McpRefactoringErrorCode.FILE_NOT_FOUND,
                "The project has no base path.",
            )
        val relative = Path.of(pathInProject)
        if (relative.isAbsolute) {
            return fileFailure(
                McpRefactoringErrorCode.OUTSIDE_PROJECT,
                "The path must be project-relative.",
            )
        }
        val candidate = base.resolve(relative).normalize()
        if (!candidate.startsWith(base)) {
            return fileFailure(
                McpRefactoringErrorCode.OUTSIDE_PROJECT,
                "The path resolves outside the project.",
            )
        }

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(candidate.toString())
            ?: return fileFailure(
                McpRefactoringErrorCode.FILE_NOT_FOUND,
                "The file does not exist: $pathInProject",
            )
        if (!virtualFile.isWritable) {
            return fileFailure(
                McpRefactoringErrorCode.READ_ONLY,
                "The file is read-only: $pathInProject",
            )
        }
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
            ?: return fileFailure(
                McpRefactoringErrorCode.FILE_NOT_FOUND,
                "Unable to obtain a document for: $pathInProject",
            )
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val file = PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile
            ?: return fileFailure(
                McpRefactoringErrorCode.NOT_JAVA_FILE,
                "The file is not a Java file: $pathInProject",
            )
        return JavaFileResolution.Success(file, document)
    }

    private fun offset(document: Document, line: Int, column: Int): Int? {
        if (line !in 1..document.lineCount || column < 1) return null
        val lineIndex = line - 1
        val value = document.getLineStartOffset(lineIndex) + column - 1
        return value.takeIf { it <= document.getLineEndOffset(lineIndex) }
    }

    private fun failure(
        code: McpRefactoringErrorCode,
        message: String,
    ): JavaSourceTargetResolution = JavaSourceTargetResolution.Failure(code, message)

    private fun fileFailure(
        code: McpRefactoringErrorCode,
        message: String,
    ): JavaFileResolution = JavaFileResolution.Failure(code, message)
}
