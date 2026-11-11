package dev.mohak.scrinium.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY path")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes")
    suspend fun getAll(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE path = :path")
    suspend fun get(path: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE path = :path")
    fun observe(path: String): Flow<NoteEntity?>

    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Upsert
    suspend fun upsertAll(notes: List<NoteEntity>)

    @Query("DELETE FROM notes WHERE path = :path")
    suspend fun delete(path: String)

    // Sync writes below are compare-and-set: each one applies only if the row
    // is still in the state sync read before its network call, so an edit,
    // delete or new note made meanwhile is kept for the next sync.

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(note: NoteEntity)

    @Query(
        "UPDATE notes SET content = :content, contentHash = :hash, remoteUpdatedAt = :updatedAt " +
            "WHERE path = :path AND localModifiedAt IS NULL AND isDeleted = 0"
    )
    suspend fun applyPull(path: String, content: String, hash: String, updatedAt: Long): Int

    @Query("UPDATE notes SET localModifiedAt = NULL WHERE path = :path AND content = :pushed AND isDeleted = 0")
    suspend fun markPushed(path: String, pushed: String): Int

    @Query(
        "UPDATE notes SET content = :server, contentHash = :hash, remoteUpdatedAt = :updatedAt, localModifiedAt = NULL " +
            "WHERE path = :path AND content = :local AND isDeleted = 0"
    )
    suspend fun resolveConflict(path: String, local: String, server: String, hash: String, updatedAt: Long): Int

    @Query("UPDATE notes SET contentHash = :hash, remoteUpdatedAt = :updatedAt WHERE path = :path")
    suspend fun stampRemote(path: String, hash: String, updatedAt: Long)

    @Query("DELETE FROM notes WHERE path = :path AND isDeleted = 1")
    suspend fun dropTombstone(path: String)

    @Query("DELETE FROM notes WHERE path = :path AND localModifiedAt IS NULL")
    suspend fun deleteIfClean(path: String)

    @Query(
        "SELECT * FROM notes WHERE path LIKE '%' || :q || '%' OR content LIKE '%' || :q || '%' " +
            "ORDER BY path LIMIT 50"
    )
    fun search(q: String): Flow<List<NoteEntity>>

    @Query("SELECT COUNT(*) FROM notes WHERE localModifiedAt IS NOT NULL")
    fun observeUnsyncedCount(): Flow<Int>
}