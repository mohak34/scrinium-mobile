package dev.mohak.scrinium.ui

import dev.mohak.scrinium.data.local.NoteEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class TreeModelTest {
    private val notes = listOf("Ideas.md", "Daily/Mon.md", "Jobs/Prep.md", "Jobs/Apps/Waymo.md")
        .map { NoteEntity(it, "", 0L, null, "") }
    private val projects = setOf("Jobs")

    private fun rows(view: ProjectView) = buildTree(notes, emptySet(), view = view, projects = projects).map {
        when (it) {
            is TreeItem.Folder -> "${it.depth}:${it.path}/"
            is TreeItem.Note -> "${it.depth}:${it.note.path}"
        }
    }

    @Test
    fun allPutsProjectFoldersFirst() {
        assertEquals(
            listOf("0:Jobs/", "1:Jobs/Apps/", "2:Jobs/Apps/Waymo.md", "1:Jobs/Prep.md", "0:Daily/", "1:Daily/Mon.md", "0:Ideas.md"),
            rows(ProjectView.All)
        )
    }

    @Test
    fun projectTabStartsAtItsFolder() {
        assertEquals(listOf("0:Jobs/Apps/", "1:Jobs/Apps/Waymo.md", "0:Jobs/Prep.md"), rows(ProjectView.Project("Jobs")))
    }

    @Test
    fun quotidianLeavesProjectsOut() {
        assertEquals(listOf("0:Daily/", "1:Daily/Mon.md", "0:Ideas.md"), rows(ProjectView.Project(null)))
    }
}
