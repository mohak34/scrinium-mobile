package dev.mohak.scrinium.data

import dev.mohak.scrinium.data.local.MoveEntity
import dev.mohak.scrinium.data.local.NoteDao
import dev.mohak.scrinium.data.local.NoteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

// Room in memory for JVM tests; the sync writes mirror NoteDao's SQL conditions.
class MemoryDao : NoteDao {
    val rows = MutableStateFlow(mapOf<String, NoteEntity>())
    val moves = mutableListOf<MoveEntity>()
    override fun observeAll(): Flow<List<NoteEntity>> = rows.map { it.values.sortedBy(NoteEntity::path) }
    override suspend fun getAll() = rows.value.values.toList()
    override suspend fun get(path: String) = rows.value[path]
    override fun observe(path: String): Flow<NoteEntity?> = rows.map { it[path] }
    override suspend fun upsert(note: NoteEntity) { rows.value += note.path to note }
    override suspend fun upsertAll(notes: List<NoteEntity>) = notes.forEach { upsert(it) }
    override suspend fun delete(path: String) { rows.value -= path }
    override fun search(q: String): Flow<List<NoteEntity>> = observeAll()
    override suspend fun insertIfAbsent(note: NoteEntity) { if (note.path !in rows.value) upsert(note) }
    private fun swap(path: String, ok: (NoteEntity) -> Boolean, f: (NoteEntity) -> NoteEntity): Int {
        val row = rows.value[path]?.takeIf(ok) ?: return 0
        rows.value += path to f(row)
        return 1
    }
    override suspend fun applyPull(path: String, content: String, hash: String, updatedAt: Long, version: String) =
        swap(path, { it.localModifiedAt == null && !it.isDeleted }) { it.copy(content = content, contentHash = hash, remoteUpdatedAt = updatedAt, version = version) }
    override suspend fun markPushed(path: String, pushed: String, version: String) = swap(path, { true }) {
        it.copy(version = version, localModifiedAt = if (it.content == pushed && !it.isDeleted) null else it.localModifiedAt)
    }
    override suspend fun resolveConflict(path: String, local: String, server: String, hash: String, updatedAt: Long, version: String) =
        swap(path, { it.content == local && !it.isDeleted }) {
            it.copy(content = server, contentHash = hash, remoteUpdatedAt = updatedAt, localModifiedAt = null, version = version)
        }
    override suspend fun rebase(path: String, was: String, now: String) {
        swap(path, { it.version == was }) { it.copy(version = now) }
    }
    override suspend fun movePaths(from: String, to: String) {
        rows.value = rows.value.values.associate { r ->
            val p = if (r.path == from || r.path.startsWith("$from/")) to + r.path.removePrefix(from) else r.path
            p to r.copy(path = p)
        }
    }
    override suspend fun queueMove(move: MoveEntity) { moves += move.copy(seq = moves.size + 1L) }
    override suspend fun pendingMoves() = moves.toList()
    override suspend fun dropMove(seq: Long) { moves.removeAll { it.seq == seq } }
    override suspend fun stampRemote(path: String, hash: String, updatedAt: Long) {
        swap(path, { true }) { it.copy(contentHash = hash, remoteUpdatedAt = updatedAt) }
    }
    override suspend fun dropTombstone(path: String) { if (rows.value[path]?.isDeleted == true) delete(path) }
    override suspend fun deleteIfClean(path: String) { if (rows.value[path]?.localModifiedAt == null) delete(path) }
    override fun observeUnsyncedCount(): Flow<Int> = rows.map { r -> r.values.count { it.localModifiedAt != null } }
}
