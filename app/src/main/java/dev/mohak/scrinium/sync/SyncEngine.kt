package dev.mohak.scrinium.sync

import dev.mohak.scrinium.data.local.NoteDao
import dev.mohak.scrinium.data.local.NoteEntity
import dev.mohak.scrinium.data.remote.ApiException
import dev.mohak.scrinium.data.remote.ManifestEntry
import dev.mohak.scrinium.data.remote.NotesSyncApi
import dev.mohak.scrinium.data.remote.noteVersion

data class SyncReport(
    val pulled: Int,
    val pushed: Int,
    val removed: Int,
    val conflicts: Int,
    val errors: Int,
    val failures: List<String> = emptyList(),
    val completedAt: Long
)

class SyncEngine(
    private val dao: NoteDao,
    private val api: NotesSyncApi,
    private val tokenProvider: () -> String?
) {
    suspend fun syncOnce(): SyncReport {
        if (tokenProvider() == null) return emptyReport()
        var errors = 0
        val failures = mutableListOf<String>()
        fun fail(what: String, e: Exception) {
            errors++
            if (failures.size < 5) failures += "$what: ${e.message}"
        }

        // Moves go first, in order, so the manifest already shows them. One
        // that can never apply (source gone, target taken) is dropped and the
        // diff below settles its notes; any other failure ends the sync, so
        // the diff never sees the rows at paths the server doesn't have yet.
        for (move in dao.pendingMoves()) {
            try {
                val result = api.moveNote(move.fromPath, move.toPath, move.opId)
                for ((path, now) in result.versions) dao.rebase(path, result.previous[path] ?: continue, now)
            } catch (e: ApiException) {
                if (e.status !in GONE) throw e
                fail("move ${move.fromPath}", e)
            }
            dao.dropMove(move.seq)
        }

        val remote = api.fetchManifest().associateBy { it.path }
        // A snapshot: edits made while a request is out are caught by the
        // compare-and-set writes in NoteDao and pushed on the next sync.
        val local = dao.getAll()
        val localPaths = local.mapTo(HashSet()) { it.path }
        var pulled = 0
        var pushed = 0
        var removed = 0
        var conflicts = 0
        val written = mutableListOf<Pair<String, String>>()

        // Keeps both: the server text in the row, the local text as a copy.
        suspend fun conflict(note: NoteEntity, meta: ManifestEntry?) {
            val server = api.fetchNote(note.path)
            api.pushNote(conflictName(note.path), note.content, createOnly = true)
            dao.resolveConflict(
                note.path, note.content, server, meta?.contentHash ?: "",
                meta?.updatedAt?.toLong() ?: 0L, noteVersion(server)
            )
            conflicts++
        }

        // [ifMatch] null creates; a 412 or 409 means the server holds text
        // this edit never saw.
        suspend fun push(note: NoteEntity, ifMatch: String?, meta: ManifestEntry?) {
            try {
                api.pushNote(note.path, note.content, ifMatch, createOnly = ifMatch == null)
            } catch (e: ApiException) {
                if (e.status != 412 && e.status != 409) throw e
                return conflict(note, meta)
            }
            dao.markPushed(note.path, note.content, noteVersion(note.content))
            written += note.path to note.content
            pushed++
        }

        for (note in local) {
            val meta = remote[note.path]
            try {
                when {
                    note.isDeleted -> {
                        if (meta != null && note.contentHash.isNotEmpty()) {
                            try {
                                api.deleteNote(note.path, note.version.ifEmpty { null })
                            } catch (e: ApiException) {
                                // 412: edited elsewhere since this phone saw
                                // it; that edit wins and comes back next sync.
                                if (e.status != 412 && e.status != 404) throw e
                            }
                        } else if (meta == null && note.contentHash.isEmpty() && note.content.isNotBlank()) {
                            // Never reached the server: upload it into the
                            // trash so it can come back.
                            api.pushNote(note.path, note.content, createOnly = true)
                            api.deleteNote(note.path)
                        }
                        dao.dropTombstone(note.path)
                        removed++
                    }
                    meta == null -> {
                        if (note.localModifiedAt != null) {
                            push(note, null, null)
                        } else {
                            dao.deleteIfClean(note.path)
                            removed++
                        }
                    }
                    note.localModifiedAt == null -> {
                        if (note.contentHash == meta.contentHash) continue
                        val content = api.fetchNote(note.path)
                        dao.applyPull(note.path, content, meta.contentHash, meta.updatedAt.toLong(), noteVersion(content))
                        pulled++
                    }
                    // The server checks the version, so a changed fingerprint
                    // with the same text is no conflict.
                    note.version.isNotEmpty() -> push(note, note.version, meta)
                    note.contentHash == meta.contentHash -> {
                        // Rows from before versions: unchecked, as they were.
                        api.pushNote(note.path, note.content)
                        dao.markPushed(note.path, note.content, noteVersion(note.content))
                        written += note.path to note.content
                        pushed++
                    }
                    else -> conflict(note, meta)
                }
            } catch (e: Exception) {
                fail(note.path, e)
            }
        }

        for ((path, meta) in remote) {
            if (path in localPaths) continue
            try {
                val content = api.fetchNote(path)
                dao.insertIfAbsent(
                    NoteEntity(
                        path = path,
                        content = content,
                        remoteUpdatedAt = meta.updatedAt.toLong(),
                        localModifiedAt = null,
                        contentHash = meta.contentHash,
                        version = noteVersion(content)
                    )
                )
                pulled++
            } catch (e: Exception) {
                fail(path, e)
            }
        }

        // The PUT doesn't return the manifest fingerprint, so read it back.
        // It is kept only when the server still holds what we wrote; if
        // another device wrote in between, the old hash makes the next sync
        // fetch that change.
        if (written.isNotEmpty()) {
            try {
                val fresh = api.fetchManifest().associateBy { it.path }
                for ((path, content) in written) {
                    val meta = fresh[path] ?: continue
                    if (api.serverHolds(path, content)) dao.stampRemote(path, meta.contentHash, meta.updatedAt.toLong())
                }
            } catch (e: Exception) {
                // stale hashes self-correct on the next sync
            }
        }

        return SyncReport(pulled, pushed, removed, conflicts, errors, failures, System.currentTimeMillis())
    }

    private fun emptyReport() = SyncReport(0, 0, 0, 0, 0, emptyList(), System.currentTimeMillis())

    private companion object {
        // A move the server refuses for good: bad target, source gone, target taken.
        val GONE = setOf(400, 404, 409)
    }

    private fun conflictName(path: String): String {
        val dot = path.lastIndexOf('.')
        val stamp = System.currentTimeMillis()
        return if (dot > 0) {
            "${path.substring(0, dot)} (conflict, phone, $stamp)${path.substring(dot)}"
        } else {
            "$path (conflict, phone, $stamp)"
        }
    }
}