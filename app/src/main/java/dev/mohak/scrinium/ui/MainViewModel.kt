package dev.mohak.scrinium.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.mohak.scrinium.data.NotesRepository
import dev.mohak.scrinium.data.SessionRepository
import dev.mohak.scrinium.data.local.NoteEntity
import dev.mohak.scrinium.di.AppContainer
import dev.mohak.scrinium.sync.SyncEngine
import dev.mohak.scrinium.sync.SyncReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Notes : Screen
    data class Editor(val path: String) : Screen
    data object Search : Screen
    data object Settings : Screen
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

    private val editorEdits = MutableStateFlow<Pair<String, String>?>(null)

    private var signInAction: (suspend () -> Boolean)? = null

    val searchQuery = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<NoteEntity>> = searchQuery
        .debounce(250)
        .distinctUntilChanged()
        .flatMapLatest { q ->
            if (q.isBlank()) notes.observeAll() else notes.search(q)
        }
        .map { list -> list.filter { !it.isDeleted } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            notes.observeUnsyncedCount().collect { _unsyncedCount.value = it }
        }
        viewModelScope.launch {
            editorEdits
                .filterNotNull()
                .debounce(500)
                .distinctUntilChanged()
                .collect { (path, text) -> notes.saveLocally(path, text) }
        }
        viewModelScope.launch {
            session.signedIn.collect { signedIn ->
                if (signedIn) syncNow()
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

    fun createNote() {
        viewModelScope.launch {
            val existing = notes.observeAll().first().map { it.path }
            val path = notes.createNote(existing)
            _editor.value = EditorState(path, "# ${path.removeSuffix(".md")}\n\n")
            editorEdits.value = null
            _screen.value = Screen.Editor(path)
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
                }
            }
        }
        editorEdits.value = null
        _editor.value = null
        _screen.value = Screen.Notes
    }

    fun openSearch() = _screen.update { Screen.Search }
    fun openSettings() = _screen.update { Screen.Settings }

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
            } catch (e: Exception) {
                _sync.update { it.copy(error = "Rename failed: ${e.message}") }
            }
        }
    }

    fun syncNow(force: Boolean = false) {
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
                _sync.update { it.copy(syncing = false, error = e.message ?: "Sync failed") }
            }
        }
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