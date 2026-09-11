package dev.mohak.scrinium.data

import dev.mohak.scrinium.data.local.NoteDao
import dev.mohak.scrinium.data.local.NoteEntity
import dev.mohak.scrinium.data.remote.ScriniumApi
import kotlinx.coroutines.flow.Flow

// One path segment cleaned the same way the web client sanitizes titles for
// filenames. Null means "nothing usable left".
fun sanitizePathSegment(raw: String): String? {
    var s = raw.trim().replace(Regex("[\\\\/:*?\"<>|]"), "").replace(Regex("^\\.+"), "").trim()
    if (s.isEmpty() || s == "." || s == "..") return null
    if (s.length > 100) s = s.take(100).trim()
    return s.ifEmpty { null }
}

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

    suspend fun createNote(existingPaths: List<String>, folder: String = ""): String {
        val dir = normalizeFolder(folder)
        val name = generateName(existingPaths, dir)
        noteDao.upsert(
            NoteEntity(
                path = name,
                content = "# ${name.substringAfterLast('/').removeSuffix(".md")}\n\n",
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

    /**
     * Local-only rename. The old path is tombstoned ([isDeleted]) so the next
     * sync pushes DELETE, and the new path carries [localModifiedAt] so the
     * same sync pushes PUT. Never touches the network — safe offline.
     * Returns the new path, or null when there is nothing to do.
     * Throws [IllegalArgumentException] when the target path already exists.
     */
    suspend fun renameNote(path: String, newName: String): String? {
        val existing = noteDao.get(path) ?: return null
        val safe = newName.trim().replace("/", "")
        if (safe.isBlank()) return null
        val base = if (safe.endsWith(".md", ignoreCase = true)) safe else "$safe.md"
        val parent = path.substringBeforeLast('/', "")
        val newPath = if (parent.isBlank()) base else "$parent/$base"
        if (newPath == path) return null
        if (noteDao.get(newPath) != null) {
            throw IllegalArgumentException("A note already exists at $newPath")
        }
        val now = System.currentTimeMillis()
        noteDao.upsert(existing.copy(isDeleted = true, localModifiedAt = now))
        noteDao.upsert(
            NoteEntity(
                path = newPath,
                content = existing.content,
                remoteUpdatedAt = existing.remoteUpdatedAt,
                localModifiedAt = now,
                contentHash = existing.contentHash
            )
        )
        return newPath
    }

    suspend fun networkSearch(q: String) = api.search(q)

    /**
     * Local-only move. Same tombstone + PUT mechanism as [renameNote], so the
     * next sync deletes the old path and pushes the new one — the server
     * creates intermediate directories on PUT. Never touches the network.
     * Returns the new path, or null when there is nothing to do.
     */
    suspend fun moveNote(path: String, newParentRaw: String): String? {
        val existing = noteDao.get(path) ?: return null
        val newParent = normalizeFolder(newParentRaw)
        val base = path.substringAfterLast('/')
        val newPath = if (newParent.isBlank()) base else "$newParent/$base"
        if (newPath == path) return null
        if (noteDao.get(newPath) != null) {
            throw IllegalArgumentException("A note already exists at $newPath")
        }
        val now = System.currentTimeMillis()
        noteDao.upsert(existing.copy(isDeleted = true, localModifiedAt = now))
        noteDao.upsert(
            NoteEntity(
                path = newPath,
                content = existing.content,
                remoteUpdatedAt = existing.remoteUpdatedAt,
                localModifiedAt = now,
                contentHash = existing.contentHash
            )
        )
        return newPath
    }

    /**
     * Local-only folder rename: rewrites the path prefix of every note under
     * [folder]. Sync pushes one DELETE + PUT per note. Returns the new
     * folder path, or null when there is nothing to do.
     */
    suspend fun renameFolder(folder: String, newName: String): String? {
        val segment = sanitizePathSegment(newName) ?: return null
        val parent = folder.substringBeforeLast('/', "")
        val newPrefix = if (parent.isBlank()) segment else "$parent/$segment"
        if (newPrefix == folder) return null
        val affected = noteDao.getAll()
            .filter { !it.isDeleted && it.path.startsWith("$folder/") }
        if (affected.isEmpty()) return null
        for (note in affected) {
            val target = newPrefix + note.path.removePrefix(folder)
            if (noteDao.get(target) != null) {
                throw IllegalArgumentException("A note already exists at $target")
            }
        }
        val now = System.currentTimeMillis()
        for (note in affected) {
            val target = newPrefix + note.path.removePrefix(folder)
            noteDao.upsert(note.copy(isDeleted = true, localModifiedAt = now))
            noteDao.upsert(
                NoteEntity(
                    path = target,
                    content = note.content,
                    remoteUpdatedAt = note.remoteUpdatedAt,
                    localModifiedAt = now,
                    contentHash = note.contentHash
                )
            )
        }
        return newPrefix
    }

    /** Local-only folder delete: tombstones every note under [folder]. */
    suspend fun deleteFolder(folder: String) {
        val now = System.currentTimeMillis()
        for (note in noteDao.getAll().filter { !it.isDeleted && it.path.startsWith("$folder/") }) {
            noteDao.upsert(note.copy(isDeleted = true, localModifiedAt = now))
        }
    }

    private fun normalizeFolder(raw: String): String {
        if (raw.isBlank()) return ""
        val parts = raw.split('/').mapNotNull { sanitizePathSegment(it) }
        if (parts.isEmpty()) throw IllegalArgumentException("Invalid folder name")
        return parts.joinToString("/")
    }

    private fun generateName(existing: List<String>, dir: String): String {
        var i = 0
        while (true) {
            i++
            val base = if (i == 1) "Untitled.md" else "Untitled $i.md"
            val full = if (dir.isBlank()) base else "$dir/$base"
            if (existing.none { it == full }) return full
        }
    }
}