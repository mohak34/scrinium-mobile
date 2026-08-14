package dev.mohak.scrinium.sync

import dev.mohak.scrinium.data.local.NoteDao
import dev.mohak.scrinium.data.local.NoteEntity
import dev.mohak.scrinium.data.remote.ScriniumApi

data class SyncReport(
    val pulled: Int,
    val pushed: Int,
    val removed: Int,
    val conflicts: Int,
    val errors: Int,
    val completedAt: Long
)

class SyncEngine(
    private val dao: NoteDao,
    private val api: ScriniumApi,
    private val tokenProvider: () -> String?
) {
    suspend fun syncOnce(): SyncReport {
        if (tokenProvider() == null) return emptyReport()

        val remote = api.fetchManifest().associateBy { it.path }
        val local = dao.getAll()
        var pulled = 0
        var pushed = 0
        var removed = 0
        var conflicts = 0
        var errors = 0
        val rehash = mutableListOf<String>()

        for (note in local) {
            try {
                when {
                    note.isDeleted -> {
                        if (remote.containsKey(note.path)) api.deleteNote(note.path)
                        dao.delete(note.path)
                        removed++
                    }
                    !remote.containsKey(note.path) -> {
                        if (note.localModifiedAt != null) {
                            api.pushNote(note.path, note.content)
                            dao.upsert(note.copy(localModifiedAt = null))
                            rehash += note.path
                            pushed++
                        } else {
                            dao.delete(note.path)
                            removed++
                        }
                    }
                    note.localModifiedAt == null -> {
                        val meta = remote.getValue(note.path)
                        if (note.contentHash == meta.contentHash) continue
                        val content = api.fetchNote(note.path)
                        dao.upsert(
                            note.copy(
                                content = content,
                                contentHash = meta.contentHash,
                                remoteUpdatedAt = meta.updatedAt
                            )
                        )
                        pulled++
                    }
                    else -> {
                        val meta = remote.getValue(note.path)
                        if (note.contentHash == meta.contentHash) {
                            api.pushNote(note.path, note.content)
                            dao.upsert(note.copy(localModifiedAt = null))
                            rehash += note.path
                            pushed++
                        } else {
                            val serverContent = api.fetchNote(note.path)
                            val conflictPath = conflictName(note.path)
                            api.pushNote(conflictPath, note.content)
                            dao.upsert(
                                note.copy(
                                    content = serverContent,
                                    contentHash = meta.contentHash,
                                    remoteUpdatedAt = meta.updatedAt,
                                    localModifiedAt = null
                                )
                            )
                            rehash += conflictPath
                            conflicts++
                        }
                    }
                }
            } catch (e: Exception) {
                errors++
            }
        }

        for ((path, meta) in remote) {
            if (local.any { it.path == path }) continue
            try {
                val content = api.fetchNote(path)
                dao.upsert(
                    NoteEntity(
                        path = path,
                        content = content,
                        remoteUpdatedAt = meta.updatedAt,
                        localModifiedAt = null,
                        contentHash = meta.contentHash
                    )
                )
                pulled++
            } catch (e: Exception) {
                errors++
            }
        }

        if (rehash.isNotEmpty()) {
            try {
                val fresh = api.fetchManifest().associateBy { it.path }
                for (path in rehash) {
                    val meta = fresh[path] ?: continue
                    val current = dao.get(path) ?: continue
                    dao.upsert(current.copy(contentHash = meta.contentHash, remoteUpdatedAt = meta.updatedAt))
                }
            } catch (e: Exception) {
                // stale hashes self-correct on the next sync
            }
        }

        return SyncReport(pulled, pushed, removed, conflicts, errors, System.currentTimeMillis())
    }

    private fun emptyReport() = SyncReport(0, 0, 0, 0, 0, System.currentTimeMillis())

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