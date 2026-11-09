package dev.mohak.scrinium.sync

import dev.mohak.scrinium.data.MemoryDao
import dev.mohak.scrinium.data.local.NoteEntity
import dev.mohak.scrinium.data.remote.ApiException
import dev.mohak.scrinium.data.remote.ManifestEntry
import dev.mohak.scrinium.data.remote.MoveResult
import dev.mohak.scrinium.data.remote.NotesSyncApi
import dev.mohak.scrinium.data.remote.noteVersion
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The server is a map of path to text with the real preconditions; [during]
// runs inside a request, to stand in for the user editing while it is out.
class SyncEngineTest {
    private val dao = MemoryDao()
    private val server = mutableMapOf<String, String>()
    private var hashes = mapOf<String, String>()
    private var during: suspend () -> Unit = {}
    private var holds: (String) -> Boolean = { true }
    private val movesDone = mutableSetOf<String>()
    // Runs after the server applies a move; returns what it rewrote.
    private var afterMove: (String) -> MoveResult = { MoveResult() }

    private fun check(path: String, ifMatch: String?) {
        if (ifMatch != null && server[path]?.let(::noteVersion) != ifMatch) throw ApiException(412)
    }

    private val api = object : NotesSyncApi {
        override suspend fun fetchManifest() = server.keys.map { ManifestEntry(it, 1.0, hashes[it] ?: "h0") }
        override suspend fun fetchNote(path: String): String { during(); return server.getValue(path) }
        override suspend fun pushNote(path: String, content: String, ifMatch: String?, createOnly: Boolean) {
            during()
            if (createOnly && path in server) throw ApiException(409)
            check(path, ifMatch)
            server[path] = content
        }
        override suspend fun deleteNote(path: String, ifMatch: String?) {
            if (path !in server) throw ApiException(404)
            check(path, ifMatch)
            server -= path
        }
        override suspend fun moveNote(from: String, to: String, opId: String): MoveResult {
            if (opId in movesDone) return MoveResult()
            if (from !in server) throw ApiException(404)
            if (to in server) throw ApiException(409)
            server[to] = server.remove(from)!!
            movesDone += opId
            return afterMove(to)
        }
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

    private fun synced(path: String, text: String) = NoteEntity(path, text, 0L, null, "h0", version = noteVersion(text))

    @Test
    fun anEditOverOneMadeElsewhereKeepsBoth() = runBlocking {
        server["a.md"] = "theirs"
        dao.upsert(synced("a.md", "base").copy(content = "mine", localModifiedAt = 1L))
        val report = sync.syncOnce()
        assertEquals(1, report.conflicts)
        assertEquals("theirs", dao.get("a.md")!!.content)
        assertEquals(listOf("mine"), server.filterKeys { it.contains("(conflict, phone") }.values.toList())
    }

    @Test
    fun aDeleteLosesToAnEditMadeElsewhere() = runBlocking {
        server["a.md"] = "theirs"
        dao.upsert(synced("a.md", "base").copy(isDeleted = true, localModifiedAt = 1L))
        sync.syncOnce()
        assertEquals("theirs", server["a.md"])
        sync.syncOnce()
        assertEquals("theirs", dao.get("a.md")!!.content)
    }

    @Test
    fun aMoveWhoseReplyIsLostIsNotMadeTwice() = runBlocking {
        server["a.md"] = "x"
        dao.upsert(synced("a.md", "x"))
        dao.move("a.md", "b.md")
        afterMove = { throw IOException("reply lost") }
        assertFailsWith<IOException> { sync.syncOnce() }
        assertEquals("x", dao.get("b.md")!!.content)
        afterMove = { MoveResult() }
        val report = sync.syncOnce()
        assertEquals(0, report.errors)
        assertEquals(mapOf("b.md" to "x"), server.toMap())
        assertEquals(listOf("b.md"), dao.getAll().map { it.path })
        assertTrue(dao.pendingMoves().isEmpty())
    }

    @Test
    fun aRenamedNoteTakesTheServersRetitleWithoutAConflict() = runBlocking {
        server["a.md"] = "# a"
        dao.upsert(synced("a.md", "# a"))
        dao.move("a.md", "b.md")
        type("b.md", "# b\nmore")
        afterMove = { path ->
            server[path] = "# b"
            MoveResult(mapOf(path to noteVersion("# b")), mapOf(path to noteVersion("# a")))
        }
        val report = sync.syncOnce()
        assertEquals(0, report.conflicts)
        assertEquals("# b\nmore", server["b.md"])
        assertNull(dao.get("b.md")!!.localModifiedAt)
    }
}
