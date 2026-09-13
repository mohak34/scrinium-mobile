package dev.mohak.scrinium.ui

import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.mohak.scrinium.data.NotesRepository
import dev.mohak.scrinium.data.SessionRepository
import dev.mohak.scrinium.data.local.NoteEntity
import dev.mohak.scrinium.data.remote.SearchResult
import dev.mohak.scrinium.data.remote.TagCount
import dev.mohak.scrinium.data.remote.TaggedHit
import dev.mohak.scrinium.data.remote.TrashEntry
import dev.mohak.scrinium.di.AppContainer
import dev.mohak.scrinium.sync.SyncEngine
import dev.mohak.scrinium.sync.SyncReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Notes : Screen
    data class Editor(val path: String) : Screen
    data object Search : Screen
    data object Settings : Screen
    data object Tags : Screen
    data object Trash : Screen
}

// Back history for the editor: note→note (wikilinks), search→note and
// tags→note all return where they came from instead of home.
private sealed interface History {
    data class Note(val path: String) : History
    data class Search(val query: String, val origin: Screen) : History
    data class Tags(val tag: String?) : History
}

data class SyncUiState(
    val syncing: Boolean = false,
    val lastSyncAt: Long? = null,
    val error: String? = null,
    val report: SyncReport? = null
)

data class EditorState(val path: String, val text: String)

class MainViewModel(
    private val session: SessionRepository,
    private val notes: NotesRepository,
    private val syncEngine: SyncEngine
) : ViewModel() {

    val signedIn: StateFlow<Boolean> = session.signedIn
    val email: StateFlow<String?> = session.email
    val signInBusy: StateFlow<Boolean> = session.busy
    val signInError: StateFlow<String?> = session.error

    private val _screen = MutableStateFlow<Screen>(Screen.Notes)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    val notesFlow: StateFlow<List<NoteEntity>> = notes.observeAll()
        .map { list -> list.filter { !it.isDeleted } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _unsyncedCount = MutableStateFlow(0)
    val unsyncedCount: StateFlow<Int> = _unsyncedCount.asStateFlow()

    private val _sync = MutableStateFlow(SyncUiState(lastSyncAt = session.lastSyncAt.value.let { if (it == 0L) null else it }))
    val sync: StateFlow<SyncUiState> = _sync.asStateFlow()

    private val _editor = MutableStateFlow<EditorState?>(null)
    val editor: StateFlow<EditorState?> = _editor.asStateFlow()

    private val _collapsedFolders = MutableStateFlow<Set<String>>(emptySet())
    val collapsedFolders: StateFlow<Set<String>> = _collapsedFolders.asStateFlow()

    fun toggleFolder(path: String) {
        _collapsedFolders.update { if (path in it) it - path else it + path }
    }

    private val editorEdits = MutableStateFlow<Pair<String, String>?>(null)

    // H1 -> filename runs on a longer debounce than the save itself, so an
    // in-progress heading doesn't rename the file on every keystroke.
    private val titleSyncEdits = MutableStateFlow<Pair<String, String>?>(null)

    private var signInAction: (suspend () -> Boolean)? = null

    // Push side of auto-sync: one quiet sync shortly after the last local
    // mutation settles. Replaces "edit, then wonder why it says unsynced".
    private var autoSyncJob: Job? = null

    private fun scheduleAutoSync() {
        autoSyncJob?.cancel()
        autoSyncJob = viewModelScope.launch {
            delay(AUTO_PUSH_DELAY_MS)
            if (!session.isSignedIn()) return@launch
            syncNow(force = true, quiet = true)
        }
    }

    val searchQuery = MutableStateFlow("")

    // Where back-from-search lands. Set on every entry into Search.
    private val _searchOrigin = MutableStateFlow<Screen>(Screen.Notes)

    private val history = ArrayDeque<History>()

    private fun pushHistory(entry: History) {
        if (history.lastOrNull() != entry) history.addLast(entry)
        while (history.size > 20) history.removeFirst()
    }

    // Remembers the current screen so leaving it for another note can come
    // back. Called before every note switch, never on restore.
    private fun pushHistoryForLeave() {
        when (val s = _screen.value) {
            is Screen.Editor -> {
                val p = _editor.value?.path ?: return
                pushHistory(History.Note(p))
            }
            Screen.Search -> pushHistory(History.Search(searchQuery.value, _searchOrigin.value))
            Screen.Tags -> pushHistory(History.Tags(_selectedTag.value))
            else -> Unit
        }
    }

    // Saves in-flight editor text to Room (with title sync + auto-push).
    // openNote used to drop text typed right before a wikilink tap.
    private suspend fun flushEditor() {
        val current = _editor.value ?: return
        val stored = notes.get(current.path)
        if (stored != null && !stored.isDeleted && stored.content != current.text) {
            notes.saveLocally(current.path, current.text)
            maybeSyncTitleToFilename(current.path, current.text)
            scheduleAutoSync()
        }
    }

    data class SearchHit(val path: String, val title: String, val snippet: String?)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val localHits = searchQuery
        .debounce(250)
        .distinctUntilChanged()
        .flatMapLatest { q ->
            if (q.isBlank()) notes.observeAll() else notes.search(q)
        }
        .map { list -> list.filter { !it.isDeleted } }

    // Server FTS runs alongside local search: better ranking plus notes the
    // phone hasn't synced yet. Read-only and quiet — offline just yields
    // local results.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val serverHits: StateFlow<List<SearchResult>> = searchQuery
        .debounce(400)
        .distinctUntilChanged()
        .filter { it.isNotBlank() }
        .flatMapLatest { q ->
            flow { emit(notes.networkSearch(q)) }.catch { emit(emptyList()) }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val searchResults: StateFlow<List<SearchHit>> =
        combine(localHits, serverHits, searchQuery) { local, server, q ->
            if (q.isBlank()) return@combine emptyList()
            val localPaths = local.map { it.path }.toSet()
            buildList {
                // Rank like the web client: exact/prefix title hits first,
                // body-only mentions last — not Room's alphabetical order.
                val ranked = local.sortedWith(
                    compareBy(
                        { searchRank(noteTitle(it.path, it.content), it.path, q) },
                        { noteTitle(it.path, it.content).lowercase() }
                    )
                )
                for (note in ranked) {
                    add(SearchHit(note.path, noteTitle(note.path, note.content), snippet(note.content, q)))
                }
                for (hit in server) {
                    if (hit.path !in localPaths) add(SearchHit(hit.path, hit.title, hit.snippet))
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            notes.observeUnsyncedCount().collect { _unsyncedCount.value = it }
        }
        viewModelScope.launch {
            editorEdits
                .filterNotNull()
                .debounce(500)
                .distinctUntilChanged()
                .collect { (path, text) ->
                    notes.saveLocally(path, text)
                    scheduleAutoSync()
                }
        }
        viewModelScope.launch {
            titleSyncEdits
                .filterNotNull()
                .debounce(1500)
                .distinctUntilChanged()
                .collect { (path, text) ->
                    maybeSyncTitleToFilename(path, text)
                }
        }
        viewModelScope.launch {
            session.signedIn.collect { signedIn ->
                if (signedIn) syncNow()
            }
        }
        // Pull side of auto-sync: while the app is in the foreground, pick up
        // server-side changes about once a minute. Gated on STARTED so nothing
        // ever runs while backgrounded.
        viewModelScope.launch {
            while (true) {
                delay(FOREGROUND_PULL_MS)
                if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(
                        androidx.lifecycle.Lifecycle.State.STARTED
                    )
                ) {
                    syncTick()
                }
            }
        }
    }

    fun registerSignInAction(action: suspend () -> Boolean) {
        signInAction = action
    }

    fun requestSignIn() {
        val action = signInAction ?: return
        viewModelScope.launch { action() }
    }

    fun clearSignInError() {
        session.clearError()
    }

    fun openNote(path: String) {
        viewModelScope.launch {
            flushEditor()
            if (_editor.value?.path == path) {
                _screen.value = Screen.Editor(path)
                return@launch
            }
            pushHistoryForLeave()
            val note = notes.get(path) ?: return@launch
            _editor.value = EditorState(note.path, note.content)
            editorEdits.value = null
            titleSyncEdits.value = null
            _screen.value = Screen.Editor(note.path)
        }
    }

    // Server-only hits aren't in Room yet — pull them in (with manifest hash
    // so sync stays consistent) and open straight from the pulled content.
    fun openSearchHit(hit: SearchHit) {
        viewModelScope.launch {
            val local = notes.get(hit.path)
            if (local != null && !local.isDeleted) {
                openNote(hit.path)
                return@launch
            }
            try {
                val pulled = notes.pullNote(hit.path)
                if (pulled == null) {
                    _sync.update { it.copy(error = "Note is no longer on the server") }
                    return@launch
                }
                flushEditor()
                if (_editor.value?.path == pulled.path) {
                    _screen.value = Screen.Editor(pulled.path)
                    return@launch
                }
                pushHistoryForLeave()
                _editor.value = EditorState(pulled.path, pulled.content)
                editorEdits.value = null
                titleSyncEdits.value = null
                _screen.value = Screen.Editor(pulled.path)
            } catch (e: Exception) {
                _sync.update { it.copy(error = "Couldn't open note: ${e.message}") }
            }
        }
    }

    fun createNote() = createNoteIn("")

    fun createNoteIn(folder: String) {
        viewModelScope.launch {
            val existing = notes.observeAll().first().map { it.path }
            try {
                val path = notes.createNote(existing, folder)
                _editor.value = EditorState(path, "# ${path.substringAfterLast('/').removeSuffix(".md")}\n\n")
                editorEdits.value = null
                titleSyncEdits.value = null
                _screen.value = Screen.Editor(path)
                scheduleAutoSync()
            } catch (e: Exception) {
                _sync.update { it.copy(error = "Create failed: ${e.message}") }
            }
        }
    }

    fun updateEditorText(text: String) {
        val current = _editor.value ?: return
        _editor.value = current.copy(text = text)
        editorEdits.value = current.path to text
        titleSyncEdits.value = current.path to text
    }

    // Web parity: editing the title renames the file — frontmatter `title:`
    // first, else the H1. Local-only tombstone + PUT like a manual rename.
    // Skips quietly on collision — the file keeps its name instead of
    // gaining a surprise suffix.
    private suspend fun maybeSyncTitleToFilename(path: String, text: String) {
        val dir = path.substringBeforeLast('/', "")
        val stem = path.substringAfterLast('/').removeSuffix(".md")
        val title = effectiveTitle(text, stem)
        if (title == stem) return
        val safe = sanitizeTitleForFilename(title) ?: return
        if (safe == stem) return
        val target = if (dir.isBlank()) "$safe.md" else "$dir/$safe.md"
        if (notes.get(target) != null) return
        try {
            val newPath = notes.renameNote(path, safe) ?: return
            if (_editor.value?.path == path) {
                _editor.value = _editor.value?.copy(path = newPath)
                val updatedText = _editor.value?.text ?: text
                editorEdits.value = newPath to updatedText
                _screen.value = Screen.Editor(newPath)
            }
            scheduleAutoSync()
        } catch (_: Exception) {
        }
    }

    fun closeEditor() {
        viewModelScope.launch {
            flushEditor()
            editorEdits.value = null
            titleSyncEdits.value = null
            when (val prev = history.removeLastOrNull()) {
                is History.Note -> {
                    val note = notes.get(prev.path)
                    if (note != null && !note.isDeleted) {
                        _editor.value = EditorState(note.path, note.content)
                        _screen.value = Screen.Editor(note.path)
                    } else {
                        _editor.value = null
                        _screen.value = Screen.Notes
                    }
                }
                is History.Search -> {
                    _editor.value = null
                    searchQuery.value = prev.query
                    _searchOrigin.value = prev.origin
                    _screen.value = Screen.Search
                }
                is History.Tags -> {
                    _editor.value = null
                    _screen.value = Screen.Tags
                    selectTag(prev.tag)
                }
                null -> {
                    _editor.value = null
                    _screen.value = Screen.Notes
                }
            }
        }
    }

    fun openSearch() {
        _searchOrigin.value = _screen.value
        _screen.update { Screen.Search }
    }
    fun openSettings() = _screen.update { Screen.Settings }

    // Tags are server-driven (vault-wide counts the phone can't compute
    // cheaply). Loaded on open, quiet offline failure leaves stale list.
    private val _tags = MutableStateFlow<List<TagCount>>(emptyList())
    val tags: StateFlow<List<TagCount>> = _tags.asStateFlow()
    private val _tagsLoading = MutableStateFlow(false)
    val tagsLoading: StateFlow<Boolean> = _tagsLoading.asStateFlow()
    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()
    private val _taggedHits = MutableStateFlow<List<TaggedHit>>(emptyList())
    val taggedHits: StateFlow<List<TaggedHit>> = _taggedHits.asStateFlow()
    private val _taggedLoading = MutableStateFlow(false)
    val taggedLoading: StateFlow<Boolean> = _taggedLoading.asStateFlow()

    fun openTags() {
        _screen.update { Screen.Tags }
        refreshTags()
    }

    fun closeTags() {
        _selectedTag.value = null
        _screen.update { Screen.Notes }
    }

    fun refreshTags() {
        viewModelScope.launch(Dispatchers.IO) {
            _tagsLoading.value = true
            try {
                _tags.value = notes.fetchTags()
            } catch (_: Exception) {
            } finally {
                _tagsLoading.value = false
            }
        }
    }

    // Trash is server-side (deletes move there on sync). Read-only list +
    // restore/purge calls, then a quiet sync to converge Room.
    private val _trash = MutableStateFlow<List<TrashEntry>>(emptyList())
    val trash: StateFlow<List<TrashEntry>> = _trash.asStateFlow()
    private val _trashLoading = MutableStateFlow(false)
    val trashLoading: StateFlow<Boolean> = _trashLoading.asStateFlow()

    fun openTrash() {
        _screen.update { Screen.Trash }
        refreshTrash()
    }

    fun closeTrash() = _screen.update { Screen.Notes }

    fun refreshTrash() {
        viewModelScope.launch(Dispatchers.IO) {
            _trashLoading.value = true
            try {
                _trash.value = notes.fetchTrash()
            } catch (e: Exception) {
                _sync.update { it.copy(error = "Couldn't load trash: ${e.message}") }
            } finally {
                _trashLoading.value = false
            }
        }
    }

    fun selectTag(tag: String?) {
        _selectedTag.value = tag
        if (tag == null) return
        viewModelScope.launch(Dispatchers.IO) {
            _taggedLoading.value = true
            try {
                _taggedHits.value = notes.fetchTagged(tag)
            } catch (e: Exception) {
                _sync.update { it.copy(error = "Couldn't load tag: ${e.message}") }
            } finally {
                _taggedLoading.value = false
            }
        }
    }

    fun restoreTrashEntry(entry: TrashEntry) {
        viewModelScope.launch {
            try {
                notes.restoreTrash(entry.trashName)
                refreshTrash()
                syncNow(force = true, quiet = true)
            } catch (e: Exception) {
                _sync.update { it.copy(error = "Restore failed: ${e.message}") }
            }
        }
    }

    fun purgeTrashEntry(entry: TrashEntry) {
        viewModelScope.launch {
            try {
                notes.purgeTrash(entry.trashName)
                _trash.update { it.filterNot { e -> e.trashName == entry.trashName } }
            } catch (e: Exception) {
                _sync.update { it.copy(error = "Delete failed: ${e.message}") }
            }
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            try {
                notes.emptyTrash()
                _trash.value = emptyList()
            } catch (e: Exception) {
                _sync.update { it.copy(error = "Empty trash failed: ${e.message}") }
            }
        }
    }

    fun openTaggedHit(hit: TaggedHit) {
        openSearchHit(SearchHit(hit.path, hit.title, hit.snippet))
    }

    fun closeSearch() {
        searchQuery.value = ""
        // Back from search returns where it came from: a tag tap in preview
        // goes back to that note, not home. Falls back to Notes when the
        // editor is gone (e.g. note deleted meanwhile).
        val origin = _searchOrigin.value
        _searchOrigin.value = Screen.Notes
        _screen.value =
            if (origin is Screen.Editor && _editor.value?.path == origin.path) origin
            else Screen.Notes
    }

    fun closeSettings() = _screen.update { Screen.Notes }

    fun setSearchQuery(q: String) {
        searchQuery.value = q
    }

    // Preview interactions (no-ops for plain text, wired in EditorScreen).
    fun openWikilink(targetRaw: String) {
        val target = targetRaw.substringBefore('#').trim()
        if (target.isEmpty()) return
        viewModelScope.launch {
            val all = notes.observeAll().first().filter { !it.isDeleted }
            val current = _editor.value
            val dir = current?.path?.substringBeforeLast('/', "") ?: ""
            val candidates = buildList {
                if (dir.isNotBlank()) add("$dir/$target")
                if (dir.isNotBlank()) add("$dir/$target.md")
                add(target)
                add(if (target.endsWith(".md", ignoreCase = true)) target else "$target.md")
            }
            val direct = candidates.firstOrNull { c -> all.any { it.path == c } }
            if (direct != null) {
                openNote(direct)
                return@launch
            }
            val stem = target.substringAfterLast('/').removeSuffix(".md")
            val matches = all.filter {
                it.path.substringAfterLast('/').removeSuffix(".md").equals(stem, ignoreCase = true)
            }
            if (matches.size == 1) {
                openNote(matches[0].path)
            } else {
                _sync.update {
                    it.copy(error = if (matches.isEmpty()) "Note not found: $target" else "Multiple notes match: $target")
                }
            }
        }
    }

    fun toggleTaskLine(lineIdx: Int) {
        val current = _editor.value ?: return
        val lines = current.text.lines()
        if (lineIdx !in lines.indices) return
        val line = lines[lineIdx]
        val toggled = when {
            "- [ ]" in line -> line.replaceFirst("- [ ]", "- [x]")
            "- [x]" in line -> line.replaceFirst("- [x]", "- [ ]")
            "- [X]" in line -> line.replaceFirst("- [X]", "- [ ]")
            else -> return
        }
        val out = lines.toMutableList()
        out[lineIdx] = toggled
        updateEditorText(out.joinToString("\n"))
    }

    fun searchTag(tag: String) {
        searchQuery.value = "#$tag"
        _searchOrigin.value = _screen.value
        _screen.update { Screen.Search }
    }

    fun deleteCurrentNote() {
        viewModelScope.launch {
            val current = _editor.value ?: return@launch
            notes.deleteLocally(current.path)
            scheduleAutoSync()
            _editor.value = null
            _screen.value = Screen.Notes
        }
    }

    fun renameCurrentNote(newName: String) {
        viewModelScope.launch {
            val current = _editor.value ?: return@launch
            try {
                val newPath = notes.renameNote(current.path, newName) ?: return@launch
                // Filename -> title: keep the H1/frontmatter in step with the
                // new name, same as the web client. Never injects when the
                // note has no title source.
                var text = current.text
                val stem = newPath.substringAfterLast('/').removeSuffix(".md")
                if (effectiveTitle(text, "") != stem) {
                    setEffectiveTitle(text, stem)?.let { updated ->
                        text = updated
                        notes.saveLocally(newPath, updated)
                    }
                }
                _editor.value = EditorState(newPath, text)
                editorEdits.value = null
                titleSyncEdits.value = null
                _screen.value = Screen.Editor(newPath)
                scheduleAutoSync()
            } catch (e: Exception) {
                _sync.update { it.copy(error = "Rename failed: ${e.message}") }
            }
        }
    }

    fun moveNote(path: String, newParent: String) {
        viewModelScope.launch {
            try {
                notes.moveNote(path, newParent) ?: return@launch
                scheduleAutoSync()
            } catch (e: Exception) {
                _sync.update { it.copy(error = "Move failed: ${e.message}") }
            }
        }
    }

    fun renameFolder(folder: String, newName: String) {
        viewModelScope.launch {
            try {
                val newPrefix = notes.renameFolder(folder, newName) ?: return@launch
                remapCollapsed(folder, newPrefix)
                scheduleAutoSync()
            } catch (e: Exception) {
                _sync.update { it.copy(error = "Rename failed: ${e.message}") }
            }
        }
    }

    fun moveFolder(folder: String, newParent: String) {
        viewModelScope.launch {
            try {
                val newPrefix = notes.moveFolder(folder, newParent) ?: return@launch
                remapCollapsed(folder, newPrefix)
                scheduleAutoSync()
            } catch (e: Exception) {
                _sync.update { it.copy(error = "Move failed: ${e.message}") }
            }
        }
    }

    // Follows collapsed state across a folder prefix rewrite so expanded
    // folders stay expanded after a rename/move.
    private fun remapCollapsed(oldPrefix: String, newPrefix: String) {
        _collapsedFolders.update { collapsed ->
            collapsed.map { path ->
                if (path == oldPrefix || path.startsWith("$oldPrefix/")) {
                    newPrefix + path.removePrefix(oldPrefix)
                } else path
            }.toSet()
        }
    }

    fun deleteFolder(folder: String) {
        viewModelScope.launch {
            notes.deleteFolder(folder)
            _collapsedFolders.update { it - folder }
            scheduleAutoSync()
        }
    }

    fun syncNow(force: Boolean = false, quiet: Boolean = false) {
        if (_sync.value.syncing) return
        if (!force) {
            // Null means "never synced" — that is exactly when a sync is due.
            // Only throttle when we have a previous timestamp.
            val last = _sync.value.lastSyncAt
            if (last != null && System.currentTimeMillis() - last < 30_000) return
        }
        viewModelScope.launch {
            _sync.update { it.copy(syncing = true, error = null) }
            try {
                val report = syncEngine.syncOnce()
                session.updateLastSyncAt(report.completedAt)
                _sync.update { it.copy(syncing = false, lastSyncAt = report.completedAt, report = report) }
            } catch (e: Exception) {
                // Automatic syncs stay quiet: offline just leaves the unsynced
                // count visible until the next attempt. Only user-triggered
                // syncs raise the error banner.
                _sync.update { it.copy(syncing = false, error = if (quiet) null else e.message ?: "Sync failed") }
            }
        }
    }

    // Minutely foreground pull. Quiet: never interrupts the user.
    fun syncTick() {
        if (!session.isSignedIn()) return
        if (_sync.value.syncing) return
        val last = _sync.value.lastSyncAt
            ?: session.lastSyncAt.value.let { if (it == 0L) null else it }
        if (last != null && System.currentTimeMillis() - last < FOREGROUND_PULL_MS) return
        syncNow(quiet = true)
    }

    fun syncOnForeground() {
        if (!session.isSignedIn()) return
        val last = _sync.value.lastSyncAt ?: session.lastSyncAt.value
        if (_sync.value.syncing) return
        if (last != 0L && System.currentTimeMillis() - last < 30 * 60_000) return
        syncNow()
    }

    fun signOut() {
        viewModelScope.launch { session.forceSignOut() }
    }

    fun dismissSyncError() {
        _sync.update { it.copy(error = null) }
    }

    companion object {
        private const val AUTO_PUSH_DELAY_MS = 5_000L
        private const val FOREGROUND_PULL_MS = 60_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MainViewModel(
                    session = container.sessionRepository,
                    notes = container.notesRepository,
                    syncEngine = container.syncEngine
                )
            }
        }
    }
}