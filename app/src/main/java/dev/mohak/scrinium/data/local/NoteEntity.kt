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
    val isDeleted: Boolean = false,
    // noteVersion() of the server text this row is based on: the If-Match of
    // its next PUT or DELETE. Empty when the server never had it.
    val version: String = ""
)

// A rename or move not yet sent to the server. The rows already sit at
// [toPath]; sync sends PATCH in [seq] order with [opId], so a retry after a
// lost reply never moves twice.
@Entity(tableName = "moves")
data class MoveEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val opId: String,
    val fromPath: String,
    val toPath: String
)
