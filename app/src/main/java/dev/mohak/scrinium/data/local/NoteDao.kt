package dev.mohak.scrinium.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.util.UUID

private const val META = "path, remoteUpdatedAt, localModifiedAt, contentHash, isDeleted, version"

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY path")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT $META FROM notes")
    suspend fun getAllMeta(): List<NoteMeta>

    // Everything waiting to upload, newest first.
    @Query("SELECT $META FROM notes WHERE localModifiedAt IS NOT NULL ORDER BY localModifiedAt DESC")
    fun observePending(): Flow<List<NoteMeta>>

    // Live notes, last edited first; a local edit counts as newer than the
    // server time.
    @Query("SELECT $META FROM notes WHERE isDeleted = 0 ORDER BY MAX(COALESCE(localModifiedAt, 0), remoteUpdatedAt) DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<NoteMeta>>

    @Query("SELECT $META FROM notes WHERE isDeleted = 0 ORDER BY MAX(COALESCE(localModifiedAt, 0), remoteUpdatedAt) DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<NoteMeta>

    @Query("SELECT * FROM notes WHERE path = :path")
    suspend fun get(path: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE path = :path")
    fun observe(path: String): Flow<NoteEntity?>

    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Query("DELETE FROM notes WHERE path = :path")
    suspend fun delete(path: String)

    @Query(
        "UPDATE notes SET isDeleted = 1, localModifiedAt = :now " +
            "WHERE isDeleted = 0 AND substr(path, 1, length(:folder) + 1) = :folder || '/'"
    )
    suspend fun tombstoneFolder(folder: String, now: Long)

    // Sync writes below are compare-and-set: each one applies only if the row
    // is still in the state sync read before its network call, so an edit,
    // delete or new note made meanwhile is kept for the next sync.

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(note: NoteEntity)

    @Query(
        "UPDATE notes SET content = :content, contentHash = :hash, remoteUpdatedAt = :updatedAt, version = :version " +
            "WHERE path = :path AND localModifiedAt IS NULL AND isDeleted = 0"
    )
    suspend fun applyPull(path: String, content: String, hash: String, updatedAt: Long, version: String): Int

    // The server holds [pushed] whatever happened to the row meanwhile, so the
    // version always moves on; the row is clean only if it still holds it.
    @Query(
        "UPDATE notes SET version = :version, localModifiedAt = CASE WHEN content = :pushed AND isDeleted = 0 " +
            "THEN NULL ELSE localModifiedAt END WHERE path = :path"
    )
    suspend fun markPushed(path: String, pushed: String, version: String): Int

    @Query(
        "UPDATE notes SET content = :server, contentHash = :hash, remoteUpdatedAt = :updatedAt, localModifiedAt = NULL, " +
            "version = :version WHERE path = :path AND content = :local AND isDeleted = 0"
    )
    suspend fun resolveConflict(path: String, local: String, server: String, hash: String, updatedAt: Long, version: String): Int

    // A server move rewrote this note's text: the row now builds on that
    // text, if it was based on what the move started from.
    @Query("UPDATE notes SET version = :now WHERE path = :path AND version = :was")
    suspend fun rebase(path: String, was: String, now: String)

    @Query("UPDATE notes SET contentHash = :hash, remoteUpdatedAt = :updatedAt WHERE path = :path")
    suspend fun stampRemote(path: String, hash: String, updatedAt: Long)

    @Query("DELETE FROM notes WHERE path = :path AND isDeleted = 1")
    suspend fun dropTombstone(path: String)

    @Query("DELETE FROM notes WHERE path = :path AND localModifiedAt IS NULL")
    suspend fun deleteIfClean(path: String)

    // Moves a note, or every row under a folder, to [to] and queues the
    // server PATCH, in one transaction.
    @Transaction
    suspend fun move(from: String, to: String) {
        movePaths(from, to)
        queueMove(MoveEntity(opId = UUID.randomUUID().toString(), fromPath = from, toPath = to))
    }

    @Query(
        "UPDATE notes SET path = :to || substr(path, length(:from) + 1) " +
            "WHERE path = :from OR substr(path, 1, length(:from) + 1) = :from || '/'"
    )
    suspend fun movePaths(from: String, to: String)

    @Insert
    suspend fun queueMove(move: MoveEntity)

    @Query("SELECT * FROM moves ORDER BY seq")
    suspend fun pendingMoves(): List<MoveEntity>

    @Query("DELETE FROM moves WHERE seq = :seq")
    suspend fun dropMove(seq: Long)

    @Query(
        "SELECT * FROM notes WHERE isDeleted = 0 AND substr(path, 1, length(:prefix)) = :prefix " +
            "AND (path LIKE '%' || :q || '%' OR content LIKE '%' || :q || '%') ORDER BY path LIMIT 50"
    )
    /** Live notes under [prefix] ("" for the vault) whose path or text holds [q]. */
    fun search(q: String, prefix: String): Flow<List<NoteEntity>>

    @Query("SELECT COUNT(*) FROM notes WHERE localModifiedAt IS NOT NULL")
    fun observeUnsyncedCount(): Flow<Int>
}