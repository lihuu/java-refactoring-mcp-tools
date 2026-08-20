package com.example.airefactoring.refactoring

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.junit.Assert.assertEquals
import java.nio.file.Path

/** Records the exact document inventory handed to an executor's persistence boundary. */
internal class RecordingNativeRefactoringDocumentPersister : NativeRefactoringDocumentPersister {
    private val persistedInventories = mutableListOf<Set<String>>()

    override fun persist(project: Project, affectedFiles: Collection<VirtualFile>) {
        val projectRoot = Path.of(requireNotNull(project.basePath))
        persistedInventories += affectedFiles.mapTo(linkedSetOf()) { file ->
            projectRoot.relativize(Path.of(file.path)).toString()
        }
    }

    fun assertPersistedExactly(vararg expectedRelativePaths: String) {
        assertEquals(
            expectedRelativePaths.toSet(),
            persistedInventories.single(),
        )
    }
}
