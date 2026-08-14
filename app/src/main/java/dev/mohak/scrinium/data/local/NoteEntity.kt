package dev.mohak.scrinium.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val path: String,
    val content: String,
    val remoteUpdatedAt: Long,
    val localModifiedAt: Long?,
    val contentHash: String,
    val isDeleted: Boolean = false
)