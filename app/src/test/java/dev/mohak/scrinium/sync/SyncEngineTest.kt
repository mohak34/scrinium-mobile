package dev.mohak.scrinium.sync

import dev.mohak.scrinium.data.MemoryDao
import dev.mohak.scrinium.data.local.NoteEntity
import dev.mohak.scrinium.data.remote.ManifestEntry
import dev.mohak.scrinium.data.remote.NotesSyncApi
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The server is a map of path to text; [during] runs inside a request, to
// stand in for the user editing while it is out.
class SyncEngineTest {
    private val dao = MemoryDao()
    private val server = mutableMapOf<String, String>()
    private var hashes = mapOf<String, String>()
    private var during: suspend () -> Unit = {}
    private var holds: (String) -> Boolean = { true }

    private val api = object : NotesSyncApi {
        override suspend fun fetchManifest() = server.keys.map { ManifestEntry(it, 1.0, hashes[it] ?: "h0") }
        override suspend fun fetchNote(path: String): String { during(); return server.getValue(path) }
        override suspend fun pushNote(path: String, content: String) { during(); server[path] = content }
        override suspend fun deleteNote(path: String) { server -= path }
        override suspend fun serverHolds(path: String, content: String) = holds(path)
    }
    private val sync = SyncEngine(dao, api) { "token" }

    private suspend fun type(path: String, text: String) {
        dao.upsert(dao.get(path)!!.copy(content = text, localModifiedAt = 2L))
    }

    @Test
    fun typingDuringAPushStaysUnsynced() = runBlocking {
        server["a.md"] = "v0"
        dao.upsert(NoteEntity("a.md", "v1", 0L, 1L, "h0"))
        during = { during = {}; type("a.md", "v2") }
        sync.syncOnce()
        val row = dao.get("a.md")!!
        assertEquals("v2", row.content)
        assertNotNull(row.localModifiedAt)
        assertEquals("v1", server["a.md"])
    }

    @Test
    fun aPullNeverOverwritesTypingOrADelete() = runBlocking {
        server += mapOf("a.md" to "server", "b.md" to "server")
        hashes = mapOf("a.md" to "h1", "b.md" to "h1")
        dao.upsert(NoteEntity("a.md", "old", 0L, null, "h0"))
        dao.upsert(NoteEntity("b.md", "old", 0L, null, "h0"))
        during = {
            type("a.md", "typed")
            dao.upsert(dao.get("b.md")!!.copy(isDeleted = true, localModifiedAt = 2L))
        }
        sync.syncOnce()
        assertEquals("typed", dao.get("a.md")!!.content)
        assertTrue(dao.get("b.md")!!.isDeleted)
    }

    @Test
    fun aRemoteNoteNeverReplacesOneMadeDuringSync() = runBlocking {
        server["new.md"] = "server"
        during = { dao.upsert(NoteEntity("new.md", "mine", 0L, 2L, "")) }
        sync.syncOnce()
        assertEquals("mine", dao.get("new.md")!!.content)
    }

    @Test
    fun theFreshHashIsKeptOnlyWhenTheServerHoldsWhatWasPushed() = runBlocking {
        dao.upsert(NoteEntity("kept.md", "mine", 0L, 1L, ""))
        dao.upsert(NoteEntity("overwritten.md", "mine", 0L, 1L, ""))
        hashes = mapOf("kept.md" to "h1", "overwritten.md" to "h1")
        holds = { it == "kept.md" }
        sync.syncOnce()
        assertEquals("h1", dao.get("kept.md")!!.contentHash)
        assertEquals("", dao.get("overwritten.md")!!.contentHash)
    }
}
