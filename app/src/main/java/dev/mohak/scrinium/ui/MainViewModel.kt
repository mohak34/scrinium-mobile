package dev.mohak.scrinium.ui

import androidx.lifecycle.ProcessLifecycleOwner
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.mohak.scrinium.BuildConfig
import android.util.Base64
import dev.mohak.scrinium.data.ImageLoader
import dev.mohak.scrinium.data.ImageSource
import dev.mohak.scrinium.data.NotesRepository
import dev.mohak.scrinium.data.Prefs
import dev.mohak.scrinium.data.noteRelative
import dev.mohak.scrinium.data.resolveImage
import dev.mohak.scrinium.data.SessionRepository
import dev.mohak.scrinium.data.local.NoteEntity
import dev.mohak.scrinium.data.remote.ApiTokenDto
import dev.mohak.scrinium.data.remote.PublicShare
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
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Notes : Screen
    data class Editor(val path: String) : Screen
    data object Search : Screen
    data object Settings : Screen
    data object Tags : Screen
    data object Trash : Screen
    data object Tasks : Screen
    data object Board : Screen
    data object Calendar : Screen
}

/** The five screens behind the bottom tabs, in tab order. */
val TabScreens: List<Screen> = listOf(Screen.Notes, Screen.Tasks, Screen.Search, Screen.Board, Screen.Calendar)

// Back history for the editor: note→note (wikilinks), search→note and
// tags→note all return where they came from instead of home.
private sealed interface History {
    data class Note(val path: String) : History
    data class Search(val query: String, val origin: Screen) : History
    data class Tags(val tag: String?) : History
    data class Tab(val screen: Screen) : History
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
    private val syncEngine: SyncEngine,
    private val images: ImageLoader,
    private val prefs: Prefs
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

    fun collapseAll() {
        _collapsedFolders.value = folderPaths(notesFlow.value).toSet()
    }

    // Tags for editor autocomplete, from Room so they work offline.
    val vaultTags: StateFlow<List<String>> = notesFlow
        .map { list -> vaultTags(list.map { it.content }) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Pinned notes and folders sort first at their level of the tree.
    val pinned: StateFlow<Set<String>> = prefs.pinned
    val template: StateFlow<String> = prefs.template

    fun togglePin(path: String) {
        val now = prefs.pinned.value
        prefs.setPinned(if (path in now) now - path else now + path)
    }

    fun setTemplate(value: String) = prefs.setTemplate(value)

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
            Screen.Tasks, Screen.Board, Screen.Calendar -> pushHistory(History.Tab(s))
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
        // Both debounced writers re-resolve their path and prefer the open
        // editor's latest text: a rename or move can land while an edit is
        // still pending, and writing to the old path would revive it.
        viewModelScope.launch {
            editorEdits
                .filterNotNull()
                .debounce(500)
                .distinctUntilChanged()
                .collect { (path, text) ->
                    val target = currentPath(path)
                    val stored = notes.get(target)
                    if (stored?.isDeleted == true) return@collect
                    val latest = _editor.value?.takeIf { it.path == target }?.text ?: text
                    if (stored?.content == latest) return@collect
                    notes.saveLocally(target, latest)
                    scheduleAutoSync()
                }
        }
        viewModelScope.launch {
            titleSyncEdits
                .filterNotNull()
                .debounce(1500)
                .distinctUntilChanged()
                .collect { (path, text) ->
                    val target = currentPath(path)
                    maybeSyncTitleToFilename(target, _editor.value?.takeIf { it.path == target }?.text ?: text)
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
                val note = notes.createNote(existing, folder, prefs::newNoteBody)
                _editor.value = EditorState(note.path, note.content)
                editorEdits.value = null
                titleSyncEdits.value = null
                _screen.value = Screen.Editor(note.path)
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
            followMove(path, newPath)
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
                    val note = notes.get(currentPath(prev.path))
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
                is History.Tab -> {
                    _editor.value = null
                    _screen.value = prev.screen
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

    /** Bottom-tab switch. Search remembers the tab it was opened from. */
    fun openTab(tab: Screen) {
        if (tab == Screen.Search && _screen.value != Screen.Search) _searchOrigin.value = _screen.value
        _screen.value = tab
    }

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

    // Trash opens from Notes and from Settings; back returns there.
    private var trashOrigin: Screen = Screen.Notes

    fun openTrash() {
        trashOrigin = _screen.value
        _screen.update { Screen.Trash }
        refreshTrash()
    }

    fun closeTrash() = _screen.update { trashOrigin }

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

    fun restoreAllTrash() {
        viewModelScope.launch {
            try {
                for (entry in _trash.value) notes.restoreTrash(entry.trashName)
            } catch (e: Exception) {
                _sync.update { it.copy(error = "Restore failed: ${e.message}") }
            }
            refreshTrash()
            syncNow(force = true, quiet = true)
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
        _screen.value = when {
            origin is Screen.Editor && _editor.value?.path == origin.path -> origin
            origin in TabScreens && origin != Screen.Search -> origin
            else -> Screen.Notes
        }
    }

    fun closeSettings() = _screen.update { Screen.Notes }

    fun setSearchQuery(q: String) {
        searchQuery.value = q
    }

    // Last few queries that led to an opened result, newest first.
    val recentSearches: StateFlow<List<String>> = prefs.recentSearches

    fun rememberSearch(q: String) = prefs.addRecentSearch(q)

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

    // Public share links for the open note. Server holds the links; the
    // public URL is base + /s/<id>, same as the web client builds.
    private val _shares = MutableStateFlow<List<PublicShare>>(emptyList())
    val shares: StateFlow<List<PublicShare>> = _shares.asStateFlow()
    private val _sharesLoading = MutableStateFlow(false)
    val sharesLoading: StateFlow<Boolean> = _sharesLoading.asStateFlow()

    fun shareUrl(id: String): String =
        "${BuildConfig.SCRINIUM_API_URL.trimEnd('/')}/s/$id"

    fun loadShares(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _sharesLoading.value = true
            try {
                _shares.value = notes.fetchShares(path)
            } catch (e: Exception) {
                _sync.update { it.copy(error = "Couldn't load links: ${e.message}") }
            } finally {
                _sharesLoading.value = false
            }
        }
    }

    fun createShareLink(path: String, password: String?) {
        if (password != null && password.isNotBlank() && password.length < 4) {
            _sync.update { it.copy(error = "Password needs at least 4 characters") }
            return
        }
        viewModelScope.launch {
            try {
                val share = notes.createShare(path, password)
                _shares.update { listOf(share) + it }
            } catch (e: Exception) {
                _sync.update { it.copy(error = "Share failed: ${e.message}") }
            }
        }
    }

    fun deleteShareLink(id: String) {
        viewModelScope.launch {
            try {
                notes.deleteShare(id)
                _shares.update { it.filterNot { s -> s.id == id } }
            } catch (e: Exception) {
                _sync.update { it.copy(error = "Unshare failed: ${e.message}") }
            }
        }
    }

    // Backlinks for the open note, computed from Room when its info panel
    // opens (not on every keystroke).
    private val _backlinks = MutableStateFlow<NoteBacklinks?>(null)
    val backlinks: StateFlow<NoteBacklinks?> = _backlinks.asStateFlow()

    fun loadBacklinks() {
        val path = _editor.value?.path ?: return
        _backlinks.value = null
        viewModelScope.launch {
            val all = notes.observeAll().first().filter { !it.isDeleted }.map { it.path to it.content }
            _backlinks.value = withContext(Dispatchers.Default) { findBacklinks(path, all) }
        }
    }

    // Turns the first plain mention of the open note in [sourcePath] into a
    // wikilink. A local edit like any other: saved to Room, pushed on sync.
    fun linkMention(sourcePath: String) {
        val target = _editor.value?.path ?: return
        viewModelScope.launch {
            val source = notes.get(sourcePath) ?: return@launch
            val updated = linkFirstMention(source.content, target) ?: return@launch
            notes.saveLocally(sourcePath, updated)
            scheduleAutoSync()
            loadBacklinks()
        }
    }

    // Image in the open note's preview. Null (alt text shown) when offline
    // and not cached, or when the reference doesn't resolve.
    suspend fun loadImage(notePath: String, raw: String): ImageBitmap? {
        val source = resolveImage(raw, notePath) ?: return null
        return try {
            images.load(source)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Uploads a picked image to the vault's attachments folder (the web
     * default) and returns the markdown to insert, path relative to the note.
     * Online only; failures surface in the error banner.
     */
    suspend fun uploadImage(notePath: String, bytes: ByteArray, mimeType: String?, name: String): String? {
        return try {
            val (body, mime) = withContext(Dispatchers.Default) { ImageLoader.prepareUpload(bytes, mimeType) }
                ?: throw IllegalArgumentException("Unsupported image")
            val ext = mime.substringAfter('/').replace("jpeg", "jpg")
            val stored = notes.uploadAttachment(body, mime, "image.$ext", ATTACHMENTS_FOLDER)
            "![${name.substringBeforeLast('.').ifBlank { "image" }}](${noteRelative(notePath, stored)})"
        } catch (e: Exception) {
            _sync.update { it.copy(error = "Image upload failed: ${e.message}") }
            null
        }
    }

    /**
     * The open note as a printable HTML page. Vault images are inlined as
     * data URIs (the print WebView has no API token); external images load
     * by URL; images that fail to load are left out.
     */
    suspend fun printableHtml(notePath: String, text: String): String {
        val refs = text.lineSequence()
            .mapNotNull { Regex("""^!\[[^\]]*]\(\s*(<[^>]+>|[^)\s]+)""").find(it.trim())?.groupValues?.get(1) }
            .map { it.removeSurrounding("<", ">") }
            .toSet()
        val sources = refs.associateWith { raw ->
            when (val src = resolveImage(raw, notePath)) {
                is ImageSource.External -> src.url
                is ImageSource.Vault -> try {
                    val bytes = notes.fetchAsset(src.path)
                    val mime = when (src.path.substringAfterLast('.').lowercase()) {
                        "png" -> "image/png"
                        "gif" -> "image/gif"
                        "webp" -> "image/webp"
                        "svg" -> "image/svg+xml"
                        else -> "image/jpeg"
                    }
                    "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                } catch (_: Exception) {
                    null
                }
                null -> null
            }
        }
        val title = effectiveTitle(text, notePath.substringAfterLast('/').removeSuffix(".md"))
        return withContext(Dispatchers.Default) { noteHtml(title, text) { sources[it] } }
    }

    fun deleteCurrentNote() {
        _editor.value?.let { deleteNote(it.path) }
    }

    // Deleting the open note also closes it.
    fun deleteNote(path: String) {
        viewModelScope.launch {
            val target = currentPath(path)
            notes.deleteLocally(target)
            scheduleAutoSync()
            if (_editor.value?.path == target) {
                _editor.value = null
                _screen.value = Screen.Notes
            }
        }
    }

    fun renameCurrentNote(newName: String) {
        _editor.value?.let { renameNote(it.path, newName) }
    }

    // Renames a note, open or not. Filename -> title: the H1/frontmatter
    // follows the new name, same as the web client; never injects a title
    // into a note that has none.
    fun renameNote(path: String, newName: String) {
        viewModelScope.launch {
            flushEditor()
            try {
                val source = currentPath(path)
                val newPath = notes.renameNote(source, newName) ?: return@launch
                followMove(source, newPath)
                val open = _editor.value?.takeIf { it.path == newPath }
                val text = open?.text ?: notes.get(newPath)?.content ?: return@launch
                val stem = newPath.substringAfterLast('/').removeSuffix(".md")
                if (effectiveTitle(text, "") != stem) {
                    setEffectiveTitle(text, stem)?.let { updated ->
                        notes.saveLocally(newPath, updated)
                        if (open != null) _editor.value = open.copy(text = updated)
                    }
                }
                scheduleAutoSync()
            } catch (e: Exception) {
                _sync.update { it.copy(error = "Rename failed: ${e.message}") }
            }
        }
    }

    fun moveNote(path: String, newParent: String) {
        viewModelScope.launch {
            flushEditor()
            try {
                val source = currentPath(path)
                val newPath = notes.moveNote(source, newParent) ?: return@launch
                followMove(source, newPath)
                scheduleAutoSync()
            } catch (e: Exception) {
                _sync.update { it.copy(error = "Move failed: ${e.message}") }
            }
        }
    }

    fun renameFolder(folder: String, newName: String) {
        viewModelScope.launch {
            try {
                flushEditor()
                val newPrefix = notes.renameFolder(folder, newName) ?: return@launch
                followMove(folder, newPrefix)
                scheduleAutoSync()
            } catch (e: Exception) {
                _sync.update { it.copy(error = "Rename failed: ${e.message}") }
            }
        }
    }

    fun moveFolder(folder: String, newParent: String) {
        viewModelScope.launch {
            try {
                flushEditor()
                val newPrefix = notes.moveFolder(folder, newParent) ?: return@launch
                followMove(folder, newPrefix)
                scheduleAutoSync()
            } catch (e: Exception) {
                _sync.update { it.copy(error = "Move failed: ${e.message}") }
            }
        }
    }

    // Where the open note went on each rename or move this session. Dialogs,
    // history and pending saves can hold a path an automatic title rename
    // has since tombstoned; [currentPath] follows the chain to the live note.
    private val movedTo = mutableMapOf<String, String>()

    private suspend fun currentPath(path: String): String {
        var p = path
        repeat(32) {
            val next = movedTo[p] ?: return p
            if (notes.get(p)?.isDeleted != true) return p
            p = next
        }
        return p
    }

    // Points pins, collapsed folders and the open editor at the new path
    // after a note or folder prefix rewrite.
    private fun followMove(oldPrefix: String, newPrefix: String) {
        remapPaths(oldPrefix, newPrefix)
        val open = _editor.value ?: return
        if (open.path != oldPrefix && !open.path.startsWith("$oldPrefix/")) return
        val moved = newPrefix + open.path.removePrefix(oldPrefix)
        movedTo[open.path] = moved
        _editor.value = open.copy(path = moved)
        if (_screen.value == Screen.Editor(open.path)) _screen.value = Screen.Editor(moved)
    }

    // Follows collapsed and pinned state across a path or folder prefix
    // rewrite so both survive a rename/move.
    private fun remapPaths(oldPrefix: String, newPrefix: String) {
        fun remap(paths: Set<String>) = paths.map { path ->
            if (path == oldPrefix || path.startsWith("$oldPrefix/")) newPrefix + path.removePrefix(oldPrefix) else path
        }.toSet()
        _collapsedFolders.update(::remap)
        prefs.pinned.value.let { if (it.any { p -> p == oldPrefix || p.startsWith("$oldPrefix/") }) prefs.setPinned(remap(it)) }
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

    // Settings > Devices: every phone signed in to this account.
    private val _devices = MutableStateFlow<List<ApiTokenDto>>(emptyList())
    val devices: StateFlow<List<ApiTokenDto>> = _devices.asStateFlow()
    val currentDeviceHash: String? get() = session.currentTokenHash()

    fun loadDevices() {
        viewModelScope.launch {
            try {
                _devices.value = session.fetchDevices().sortedByDescending { it.lastUsedAt ?: it.createdAt }
            } catch (e: Exception) {
                _sync.update { it.copy(error = "Couldn't load devices: ${e.message}") }
            }
        }
    }

    // Revoking this phone's own token is a sign-out.
    fun revokeDevice(tokenHash: String) {
        viewModelScope.launch {
            try {
                session.revokeDevice(tokenHash)
                _devices.update { list -> list.filterNot { it.tokenHash == tokenHash } }
                if (tokenHash == session.currentTokenHash()) session.forceSignOut()
            } catch (e: Exception) {
                _sync.update { it.copy(error = "Couldn't revoke device: ${e.message}") }
            }
        }
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
        private const val ATTACHMENTS_FOLDER = "attachments"

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MainViewModel(
                    session = container.sessionRepository,
                    notes = container.notesRepository,
                    syncEngine = container.syncEngine,
                    images = container.imageLoader,
                    prefs = container.prefs
                )
            }
        }
    }
}