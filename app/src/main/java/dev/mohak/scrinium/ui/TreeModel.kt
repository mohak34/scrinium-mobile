package dev.mohak.scrinium.ui

import dev.mohak.scrinium.data.local.NoteEntity

// Flat render model for the vault hierarchy. Folders are derived from note
// paths — there is no folder table. An empty folder has no row: the manifest
// only lists .md files, so empty folders are invisible to sync anyway.
sealed interface TreeItem {
    data class Folder(
        val path: String,
        val depth: Int,
        val noteCount: Int,
        val hasUnsynced: Boolean,
        // Set when another folder shares this leaf name — the UI shows it so
        // same-named folders in different parents stay distinguishable.
        val pathHint: String? = null
    ) : TreeItem

    data class Note(val note: NoteEntity, val depth: Int) : TreeItem
}

val TreeItem.Folder.name: String
    get() = path.substringAfterLast('/')

// Every ancestor directory implied by the note paths, sorted.
fun folderPaths(notes: List<NoteEntity>): List<String> {
    val out = sortedSetOf<String>()
    for (note in notes) {
        var dir = note.path.substringBeforeLast('/', "")
        while (dir.isNotBlank()) {
            out += dir
            dir = dir.substringBeforeLast('/', "")
        }
    }
    return out.toList()
}

// Directories first then files at each level, alphabetical — same order as
// the web client's tree. Collapsed subtrees emit only their folder row.
fun buildTree(notes: List<NoteEntity>, collapsed: Set<String>): List<TreeItem> {
    val byParent = notes.groupBy { it.path.substringBeforeLast('/', "") }
    val allFolders = folderPaths(notes)
    val childDirs: Map<String, List<String>> =
        allFolders.groupBy { it.substringBeforeLast('/', "") }
    val leafCounts = allFolders.groupingBy { it.substringAfterLast('/') }.eachCount()

    fun countNotes(dir: String): Int {
        var c = byParent[dir]?.size ?: 0
        for (child in childDirs[dir].orEmpty()) c += countNotes(child)
        return c
    }

    fun hasUnsynced(dir: String): Boolean {
        if (byParent[dir].orEmpty().any { it.localModifiedAt != null }) return true
        return childDirs[dir].orEmpty().any { hasUnsynced(it) }
    }

    val out = mutableListOf<TreeItem>()
    fun emit(dir: String, depth: Int) {
        for (child in childDirs[dir].orEmpty().sortedBy { it.lowercase() }) {
            val hint = if ((leafCounts[child.substringAfterLast('/')] ?: 0) > 1) {
                child.substringBeforeLast('/', "").ifBlank { "All notes" }
            } else null
            out += TreeItem.Folder(child, depth, countNotes(child), hasUnsynced(child), hint)
            if (child !in collapsed) emit(child, depth + 1)
        }
        for (note in byParent[dir].orEmpty().sortedBy { it.path.substringAfterLast('/').lowercase() }) {
            out += TreeItem.Note(note, depth)
        }
    }
    emit("", 0)
    return out
}
