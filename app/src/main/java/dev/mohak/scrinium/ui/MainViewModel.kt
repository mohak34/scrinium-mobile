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
                for (note in local) {
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
            val note = notes.get(path) ?: return@launch
            _editor.value = EditorState(note.path, note.content)
            editorEdits.value = null
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
                _editor.value = EditorState(pulled.path, pulled.content)
                editorEdits.value = null
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
    }

    fun closeEditor() {
        val current = _editor.value
        if (current != null) {
            viewModelScope.launch {
                val stored = notes.get(current.path)
                if (stored != null && !stored.isDeleted && stored.content != current.text) {
                    notes.saveLocally(current.path, current.text)
                    scheduleAutoSync()
                }
            }
        }
        editorEdits.value = null
        _editor.value = null
        _screen.value = Screen.Notes
    }

    fun openSearch() = _screen.update { Screen.Search }
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

    fun openTaggedHit(hit: TaggedHit) {
        openSearchHit(SearchHit(hit.path, hit.title, hit.snippet))
    }

    fun closeSearch() {
        searchQuery.value = ""
        _screen.update { Screen.Notes }
    }

    fun closeSettings() = _screen.update { Screen.Notes }

    fun setSearchQuery(q: String) {
        searchQuery.value = q
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
                _editor.value = current.copy(path = newPath)
                editorEdits.value = newPath to current.text
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