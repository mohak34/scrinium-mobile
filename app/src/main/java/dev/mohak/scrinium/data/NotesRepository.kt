package dev.mohak.scrinium.data

import dev.mohak.scrinium.data.local.NoteDao
import dev.mohak.scrinium.data.local.NoteEntity
import dev.mohak.scrinium.data.remote.ScriniumApi
import kotlinx.coroutines.flow.Flow

class NotesRepository(
    private val noteDao: NoteDao,
    private val api: ScriniumApi
) {
    fun observeAll(): Flow<List<NoteEntity>> = noteDao.observeAll()

    fun observeUnsyncedCount(): Flow<Int> = noteDao.observeUnsyncedCount()

    fun search(q: String): Flow<List<NoteEntity>> = noteDao.search(q)

    suspend fun get(path: String): NoteEntity? = noteDao.get(path)

    suspend fun saveLocally(path: String, content: String) {
        val existing = noteDao.get(path)
        noteDao.upsert(
            NoteEntity(
                path = path,
                content = content,
                remoteUpdatedAt = existing?.remoteUpdatedAt ?: 0L,
                localModifiedAt = System.currentTimeMillis(),
                contentHash = existing?.contentHash ?: ""
            )
        )
    }

    suspend fun createNote(existingPaths: List<String>): String {
        val name = generateName(existingPaths)
        noteDao.upsert(
            NoteEntity(
                path = name,
                content = "# ${name.removeSuffix(".md")}\n\n",
                remoteUpdatedAt = 0L,
                localModifiedAt = System.currentTimeMillis(),
                contentHash = ""
            )
        )
        return name
    }

    suspend fun deleteLocally(path: String) {
        val existing = noteDao.get(path) ?: return
        noteDao.upsert(existing.copy(isDeleted = true, localModifiedAt = System.currentTimeMillis()))
    }

    suspend fun renameNote(path: String, newName: String) {
        val existing = noteDao.get(path) ?: return
        val parent = path.substringBeforeLast('/', "")
        val newPath = if (parent.isBlank()) newName else "$parent/$newName"
        api.renameNote(path, newPath)
        noteDao.delete(path)
        noteDao.upsert(existing.copy(path = newPath))
    }

    suspend fun networkSearch(q: String) = api.search(q)

    private fun generateName(existing: List<String>): String {
        val base = "Untitled.md"
        var name = base
        var i = 1
        while (existing.any { it == name }) {
            i++
            name = "Untitled $i.md"
        }
        return name
    }
}