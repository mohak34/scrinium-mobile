package dev.mohak.scrinium.data

import dev.mohak.scrinium.data.local.NoteEntity
import dev.mohak.scrinium.data.remote.ScriniumApi
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotesRepositoryTest {
    private val dao = MemoryDao()
    private val repo = NotesRepository(dao, ScriniumApi("http://localhost", { null }, {}, Files.createTempDirectory("api").toFile()))

    private fun live() = dao.rows.value.values.filter { !it.isDeleted }.map { it.path }.sorted()

    // A stale path (e.g. held by a dialog across an automatic title rename)
    // must not revive the tombstone as a second live note.
    @Test
    fun tombstonedNotesCannotBeRenamedOrMoved() = runBlocking {
        dao.upsert(NoteEntity("old.md", "# new heading", 0L, null, ""))
        assertEquals("new heading.md", repo.renameNote("old.md", "new heading"))
        assertNull(repo.renameNote("old.md", "manual"))
        assertNull(repo.moveNote("old.md", "dest"))
        assertEquals(listOf("new heading.md"), live())
    }
}
