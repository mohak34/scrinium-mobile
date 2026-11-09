package dev.mohak.scrinium.sync

import dev.mohak.scrinium.data.local.NoteDao
import dev.mohak.scrinium.data.local.NoteEntity
import dev.mohak.scrinium.data.remote.NotesSyncApi

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

        val remote = api.fetchManifest().associateBy { it.path }
        // A snapshot: edits made while a request is out are caught by the
        // compare-and-set writes in NoteDao and pushed on the next sync.
        val local = dao.getAll()
        val localPaths = local.mapTo(HashSet()) { it.path }
        val liveContent = local.filter { !it.isDeleted }.mapTo(HashSet()) { it.content }
        var pulled = 0
        var pushed = 0
        var removed = 0
        var conflicts = 0
        var errors = 0
        val written = mutableListOf<Pair<String, String>>()
        val failures = mutableListOf<String>()

        for (note in local) {
            try {
                when {
                    note.isDeleted -> {
                        if (remote.containsKey(note.path)) {
                            api.deleteNote(note.path)
                        } else if (note.contentHash.isEmpty() && note.content.isNotBlank() && note.content !in liveContent) {
                            // Never reached the server, and no rename carried the
                            // text on: upload it into the trash so it can come back.
                            api.pushNote(note.path, note.content)
                            api.deleteNote(note.path)
                        }
                        dao.dropTombstone(note.path)
                        removed++
                    }
                    !remote.containsKey(note.path) -> {
                        if (note.localModifiedAt != null) {
                            api.pushNote(note.path, note.content)
                            dao.markPushed(note.path, note.content)
                            written += note.path to note.content
                            pushed++
                        } else {
                            dao.deleteIfClean(note.path)
                            removed++
                        }
                    }
                    note.localModifiedAt == null -> {
                        val meta = remote.getValue(note.path)
                        if (note.contentHash == meta.contentHash) continue
                        val content = api.fetchNote(note.path)
                        dao.applyPull(note.path, content, meta.contentHash, meta.updatedAt.toLong())
                        pulled++
                    }
                    else -> {
                        val meta = remote.getValue(note.path)
                        if (note.contentHash == meta.contentHash) {
                            api.pushNote(note.path, note.content)
                            dao.markPushed(note.path, note.content)
                            written += note.path to note.content
                            pushed++
                        } else {
                            val serverContent = api.fetchNote(note.path)
                            api.pushNote(conflictName(note.path), note.content)
                            dao.resolveConflict(note.path, note.content, serverContent, meta.contentHash, meta.updatedAt.toLong())
                            conflicts++
                        }
                    }
                }
            } catch (e: Exception) {
                errors++
                if (failures.size < 5) failures += "${note.path}: ${e.message}"
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
                        contentHash = meta.contentHash
                    )
                )
                pulled++
            } catch (e: Exception) {
                errors++
                if (failures.size < 5) failures += "${path}: ${e.message}"
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