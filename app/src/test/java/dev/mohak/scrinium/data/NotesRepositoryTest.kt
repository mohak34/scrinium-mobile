package dev.mohak.scrinium.data

import dev.mohak.scrinium.data.local.NoteDao
import dev.mohak.scrinium.data.local.NoteEntity
import dev.mohak.scrinium.data.remote.ScriniumApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotesRepositoryTest {
    private class MemoryDao : NoteDao {
        val rows = MutableStateFlow(mapOf<String, NoteEntity>())
        override fun observeAll(): Flow<List<NoteEntity>> = rows.map { it.values.sortedBy(NoteEntity::path) }
        override suspend fun getAll() = rows.value.values.toList()
        override suspend fun get(path: String) = rows.value[path]
        override suspend fun upsert(note: NoteEntity) { rows.value += note.path to note }
        override suspend fun upsertAll(notes: List<NoteEntity>) = notes.forEach { upsert(it) }
        override suspend fun delete(path: String) { rows.value -= path }
        override fun search(q: String): Flow<List<NoteEntity>> = observeAll()
        override fun observeUnsyncedCount(): Flow<Int> = rows.map { r -> r.values.count { it.localModifiedAt != null } }
    }

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
