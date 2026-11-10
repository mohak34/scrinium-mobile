package dev.mohak.scrinium.data

import dev.mohak.scrinium.data.local.NoteDao
import dev.mohak.scrinium.data.local.NoteEntity
import dev.mohak.scrinium.data.local.NoteMeta
import dev.mohak.scrinium.data.remote.ScriniumApi
import dev.mohak.scrinium.data.remote.noteVersion
import kotlinx.coroutines.flow.Flow
import okhttp3.RequestBody

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

    fun observePending(): Flow<List<NoteMeta>> = noteDao.observePending()

    suspend fun allMeta(): List<NoteMeta> = noteDao.getAllMeta()

    suspend fun hasPendingMoves(): Boolean = noteDao.pendingMoves().isNotEmpty()

    fun observeUnsyncedCount(): Flow<Int> = noteDao.observeUnsyncedCount()

    fun search(q: String, prefix: String): Flow<List<NoteEntity>> = noteDao.search(q, prefix)

    suspend fun get(path: String): NoteEntity? = noteDao.get(path)

    fun observe(path: String): Flow<NoteEntity?> = noteDao.observe(path)

    // [base] is the text the edit was typed over. If a pull replaced it in
    // the meantime, the row keeps base's version so the push conflicts
    // instead of overwriting the pulled text (a clean row's version is its
    // content's).
    suspend fun saveLocally(path: String, content: String, base: String? = null) {
        val existing = noteDao.get(path)
        val pulledOver = base?.takeIf { existing != null && existing.localModifiedAt == null && existing.content != it }
        noteDao.upsert(
            NoteEntity(
                path = path,
                content = content,
                remoteUpdatedAt = existing?.remoteUpdatedAt ?: 0L,
                localModifiedAt = System.currentTimeMillis(),
                contentHash = existing?.contentHash ?: "",
                version = pulledOver?.let(::noteVersion) ?: existing?.version ?: ""
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
     * Local-only rename: the row moves now, and the next sync sends the
     * server move (NoteDao.move). Never touches the network, safe offline.
     * Returns the new path, or null when there is nothing to do (including a
     * tombstoned source). Throws [IllegalArgumentException] when the target
     * path already exists.
     */
    suspend fun renameNote(path: String, newName: String): String? {
        val existing = noteDao.get(path)?.takeIf { !it.isDeleted } ?: return null
        val safe = newName.trim().replace("/", "")
        if (safe.isBlank()) return null
        val base = if (safe.endsWith(".md", ignoreCase = true)) safe else "$safe.md"
        val parent = path.substringBeforeLast('/', "")
        val newPath = if (parent.isBlank()) base else "$parent/$base"
        if (newPath == path) return null
        if (noteDao.get(newPath) != null) {
            throw IllegalArgumentException("A note already exists at $newPath")
        }
        noteDao.move(path, newPath)
        return newPath
    }

    suspend fun networkSearch(q: String, folder: String?) = api.search(q, folder)

    suspend fun searchArchive(q: String) = api.searchArchive(q)

    // Archived notes are read-only and never enter Room; reads reach them.
    suspend fun archivedText(path: String) = api.fetchNote(path)

    suspend fun fetchTags() = api.fetchTags()

    suspend fun fetchTagged(tag: String) = api.fetchTagged(tag)

    suspend fun fetchTrash() = api.fetchTrash()

    suspend fun restoreTrash(trashName: String) = api.restoreTrash(trashName)

    suspend fun purgeTrash(trashName: String) = api.purgeTrash(trashName)

    suspend fun emptyTrash() = api.emptyTrash()

    suspend fun fetchArchive() = api.fetchArchive()

    suspend fun archive(path: String) = api.archive(path)

    suspend fun restoreArchive(name: String) = api.restoreArchive(name)

    suspend fun archiveToTrash(name: String) = api.archiveToTrash(name)

    suspend fun fetchReview(days: Int) = api.fetchReview(days)

    suspend fun fetchUnused() = api.fetchUnused()

    // Trashes any vault file on the server; for files Room doesn't hold.
    suspend fun trashRemote(path: String) = api.deleteNote(path)

    suspend fun history(path: String) = api.history(path)

    suspend fun historyText(path: String, id: Long) = api.historyText(path, id)

    // Puts [content] back on the server over the clean row's text, then into
    // Room unless typing began meanwhile (that edit then conflicts on push).
    // The blank hash makes the next sync take the server's fingerprint.
    suspend fun restoreVersion(path: String, content: String) {
        val row = noteDao.get(path)?.takeIf { it.localModifiedAt == null && !it.isDeleted }
            ?: throw IllegalStateException("the note has unsynced edits")
        api.restoreNote(path, content, row.version.ifEmpty { noteVersion(row.content) })
        noteDao.applyPull(path, content, "", System.currentTimeMillis(), noteVersion(content))
    }

    suspend fun fetchShares(path: String?) = api.fetchShares(path)

    suspend fun setSharePassword(id: String, password: String?) = api.setSharePassword(id, password)

    suspend fun createShare(path: String, password: String?, expiresInDays: Int?) = api.createShare(path, password, expiresInDays)

    suspend fun deleteShare(id: String) = api.deleteShare(id)

    suspend fun fetchAsset(path: String): ByteArray = api.fetchAsset(path)

    suspend fun uploadAttachment(body: RequestBody, fileName: String, folder: String) =
        api.uploadAttachment(body, fileName, folder)

    /**
     * Local-only move, synced like [renameNote]; the server creates missing
     * folders. Never touches the network. A colliding destination gets a "name (N)" suffix instead of failing
     * (same convention as the web client's uniquePath): a drag never typed
     * a name, so there is nothing to correct.
     * Returns the new path, or null when there is nothing to do.
     */
    suspend fun moveNote(path: String, newParentRaw: String): String? {
        val existing = noteDao.get(path)?.takeIf { !it.isDeleted } ?: return null
        val newParent = normalizeFolder(newParentRaw)
        val base = path.substringAfterLast('/')
        var newPath = if (newParent.isBlank()) base else "$newParent/$base"
        if (newPath == path) return null
        if (noteDao.get(newPath) != null) {
            newPath = uniqueNotePath(newParent, base)
        }
        noteDao.move(path, newPath)
        return newPath
    }

    /**
     * Local-only folder rename: rewrites the path prefix of every note under
     * [folder] and queues one server move for the folder. Returns the new
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

    // Tombstones move with the folder, so their deletes follow it.
    private suspend fun rewritePrefix(folder: String, newPrefix: String): String? {
        val all = noteDao.getAllMeta()
        if (all.none { !it.isDeleted && it.path.startsWith("$folder/") }) return null
        if (all.any { it.path == newPrefix || it.path.startsWith("$newPrefix/") }) {
            throw IllegalArgumentException("A folder already exists at $newPrefix")
        }
        noteDao.move(folder, newPrefix)
        return newPrefix
    }

    /** Local-only folder delete: tombstones every note under [folder] in one transaction. */
    suspend fun deleteFolder(folder: String) {
        noteDao.tombstoneFolder(folder, System.currentTimeMillis())
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

    // First free "base (N)" for a colliding folder destination. Tombstones
    // count: a row can't move onto one.
    private suspend fun uniqueFolderPrefix(want: String): String {
        val all = noteDao.getAllMeta()
        fun taken(prefix: String) = all.any { it.path == prefix || it.path.startsWith("$prefix/") }
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