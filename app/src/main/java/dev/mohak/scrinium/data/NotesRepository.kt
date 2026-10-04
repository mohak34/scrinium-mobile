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

    // [body] builds the initial text from the note's title (the template).
    suspend fun createNote(existingPaths: List<String>, folder: String, body: (String) -> String): NoteEntity {
        val dir = normalizeFolder(folder)
        val name = generateName(existingPaths, dir)
        val note = NoteEntity(
            path = name,
            content = body(name.substringAfterLast('/').removeSuffix(".md")),
            remoteUpdatedAt = 0L,
            localModifiedAt = System.currentTimeMillis(),
            contentHash = ""
        )
        noteDao.upsert(note)
        return note
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

    suspend fun fetchTags() = api.fetchTags()

    suspend fun fetchTagged(tag: String) = api.fetchTagged(tag)

    suspend fun fetchTrash() = api.fetchTrash()

    suspend fun restoreTrash(trashName: String) = api.restoreTrash(trashName)

    suspend fun purgeTrash(trashName: String) = api.purgeTrash(trashName)

    suspend fun emptyTrash() = api.emptyTrash()

    suspend fun fetchShares(path: String) = api.fetchShares(path)

    suspend fun createShare(path: String, password: String?) = api.createShare(path, password)

    suspend fun deleteShare(id: String) = api.deleteShare(id)

    suspend fun fetchAsset(path: String): ByteArray = api.fetchAsset(path)

    suspend fun uploadAttachment(bytes: ByteArray, mimeType: String, fileName: String, folder: String) =
        api.uploadAttachment(bytes, mimeType, fileName, folder)

    /**
     * Pulls a server-side note into Room with its manifest hash, so the next
     * sync treats it as up to date instead of re-pulling or — worse —
     * conflict-duplicating it. Returns null when the note is already gone
     * server-side.
     */
    suspend fun pullNote(path: String): NoteEntity? {
        val content = api.fetchNote(path)
        val meta = api.fetchManifest().firstOrNull { it.path == path } ?: return null
        val entity = NoteEntity(
            path = path,
            content = content,
            remoteUpdatedAt = meta.updatedAt.toLong(),
            localModifiedAt = null,
            contentHash = meta.contentHash
        )
        noteDao.upsert(entity)
        return entity
    }

    /**
     * Local-only move. Same tombstone + PUT mechanism as [renameNote], so the
     * next sync deletes the old path and pushes the new one — the server
     * creates intermediate directories on PUT. Never touches the network.
     * A colliding destination gets a "name (N)" suffix instead of failing
     * (same convention as the web client's uniquePath): a drag never typed
     * a name, so there is nothing to correct.
     * Returns the new path, or null when there is nothing to do.
     */
    suspend fun moveNote(path: String, newParentRaw: String): String? {
        val existing = noteDao.get(path) ?: return null
        val newParent = normalizeFolder(newParentRaw)
        val base = path.substringAfterLast('/')
        var newPath = if (newParent.isBlank()) base else "$newParent/$base"
        if (newPath == path) return null
        if (noteDao.get(newPath) != null) {
            newPath = uniqueNotePath(newParent, base)
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
        return rewritePrefix(folder, newPrefix)
    }

    /**
     * Local-only folder move: reparents [folder] under [newParentRaw]
     * (blank = vault root), keeping its name. Same sync mechanics as
     * [renameFolder].
     */
    suspend fun moveFolder(folder: String, newParentRaw: String): String? {
        val newParent = normalizeFolder(newParentRaw)
        val base = folder.substringAfterLast('/')
        var newPrefix = if (newParent.isBlank()) base else "$newParent/$base"
        if (newPrefix == folder) return null
        if (newPrefix.startsWith("$folder/")) {
            throw IllegalArgumentException("Can't move a folder into itself")
        }
        return rewritePrefix(folder, uniqueFolderPrefix(newPrefix))
    }

    private suspend fun rewritePrefix(folder: String, newPrefix: String): String? {
        val all = noteDao.getAll().filter { !it.isDeleted }
        val affected = all.filter { it.path.startsWith("$folder/") }
        if (affected.isEmpty()) return null
        if (all.any { it.path.startsWith("$newPrefix/") }) {
            throw IllegalArgumentException("A folder already exists at $newPrefix")
        }
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

    // First free "stem (N).ext" for a colliding note destination.
    private suspend fun uniqueNotePath(dir: String, base: String): String {
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val ext = if (dot > 0) base.substring(dot) else ""
        val prefix = if (dir.isBlank()) "" else "$dir/"
        var i = 1
        while (true) {
            val candidate = "$prefix$stem ($i)$ext"
            if (noteDao.get(candidate) == null) return candidate
            i++
        }
    }

    // First free "base (N)" for a colliding folder destination. Only live
    // notes count — tombstones are already on their way out.
    private suspend fun uniqueFolderPrefix(want: String): String {
        val live = noteDao.getAll().filter { !it.isDeleted }
        fun taken(prefix: String) = live.any { it.path == prefix || it.path.startsWith("$prefix/") }
        if (!taken(want)) return want
        var i = 1
        while (true) {
            val candidate = "$want ($i)"
            if (!taken(candidate)) return candidate
            i++
        }
    }

    private fun generateName(existing: List<String>, dir: String): String {
        var i = 0
        while (true) {
            i++
            val base = if (i == 1) "Untitled.md" else "Untitled ($i).md"
            val full = if (dir.isBlank()) base else "$dir/$base"
            if (existing.none { it == full }) return full
        }
    }
}