package dev.mohak.scrinium.data.local

import androidx.room.Dao
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

    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Upsert
    suspend fun upsertAll(notes: List<NoteEntity>)

    @Query("DELETE FROM notes WHERE path = :path")
    suspend fun delete(path: String)

    @Query(
        "SELECT * FROM notes WHERE path LIKE '%' || :q || '%' OR content LIKE '%' || :q || '%' " +
            "ORDER BY path LIMIT 50"
    )
    fun search(q: String): Flow<List<NoteEntity>>

    @Query("SELECT COUNT(*) FROM notes WHERE localModifiedAt IS NOT NULL")
    fun observeUnsyncedCount(): Flow<Int>
}