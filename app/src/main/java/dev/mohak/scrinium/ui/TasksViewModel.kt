package dev.mohak.scrinium.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.mohak.scrinium.data.remote.CalendarResponse
import dev.mohak.scrinium.data.remote.NewTaskRequest
import dev.mohak.scrinium.data.remote.TasksApi
import dev.mohak.scrinium.data.remote.TaskDto
import dev.mohak.scrinium.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tasks are server-only (SQLite on the server, not vault files), so this
 * talks to the API directly and holds no Room state. Edits paint
 * optimistically and roll back on failure, like the web store. Offline means
 * a visible error, never a silent queue.
 *
 * [syncReminders] makes the phone's alarms match a full task list.
 */
class TasksViewModel(
    private val api: TasksApi,
    private val syncReminders: (List<TaskDto>) -> Unit
) : ViewModel() {

    private val _tasks = MutableStateFlow<List<TaskDto>>(emptyList())
    val tasks: StateFlow<List<TaskDto>> = _tasks.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // The task open in the detail screen. Shown over whatever screen opened
    // it (task list or a note), so closing it lands back there.
    private val _openTaskId = MutableStateFlow<String?>(null)
    val openTaskId: StateFlow<String?> = _openTaskId.asStateFlow()

    private val _openLinks = MutableStateFlow<List<String>>(emptyList())
    val openLinks: StateFlow<List<String>> = _openLinks.asStateFlow()

    // Tasks linked to the note open in the editor (its info panel).
    private val _noteTasks = MutableStateFlow<List<TaskDto>>(emptyList())
    val noteTasks: StateFlow<List<TaskDto>> = _noteTasks.asStateFlow()

    // Google Calendar events for the month the calendar shows. Read-only and
    // quiet: offline or unlinked just shows no events.
    private val _calendar = MutableStateFlow(CalendarResponse())
    val calendar: StateFlow<CalendarResponse> = _calendar.asStateFlow()

    private var eventsJob: Job? = null

    fun loadEvents(from: Long, to: Long) {
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch {
            _calendar.value = try {
                api.fetchCalendarEvents(from, to)
            } catch (_: Exception) {
                CalendarResponse()
            }
        }
    }

    fun dismissError() {
        _error.value = null
    }

    // Phone alarms follow the task list, but only once a full list has
    // loaded: a partial list (one task added from a note) must not cancel
    // every other reminder. Every write goes through here so alarms are
    // reconciled even when a write leaves the list equal (an empty first load).
    private var loaded = false

    private fun setTasks(transform: (List<TaskDto>) -> List<TaskDto>) {
        _tasks.update(transform)
        if (loaded) syncReminders(_tasks.value)
    }

    private suspend fun load() {
        val list = api.fetchTasks()
        loaded = true
        for (row in list) if (row.id in confirmed) confirmed[row.id] = row
        setTasks { list.map(::withPending) }
    }

    // [quiet] is the app-foreground refresh that keeps reminders current:
    // offline then is not an error worth showing.
    fun refresh(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) _loading.value = true
            try {
                load()
            } catch (e: Exception) {
                if (!quiet) _error.value = "Couldn't load tasks: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    private var openJob: Job? = null

    fun openTask(id: String) {
        flushDrafts()
        _openTaskId.value = id
        _openLinks.value = emptyList()
        openJob?.cancel()
        openJob = viewModelScope.launch {
            // A task opened from a note panel may not be in the list yet.
            if (_tasks.value.none { it.id == id }) {
                try {
                    load()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _error.value = "Couldn't load task: ${e.message}"
                }
            }
            try {
                val links = api.fetchTaskLinks(id)
                if (_openTaskId.value == id) _openLinks.value = links
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun closeTask() {
        flushDrafts()
        openJob?.cancel()
        _openTaskId.value = null
    }

    // Typed text fields (title, notes, waiting on) save ~600ms after typing
    // stops. The pending text lives here, not in the screen, so leaving the
    // task saves it at once instead of dropping it with the composition.
    enum class TextField { Title, Detail, WaitingOn }

    private class Draft(val text: String, val job: Job)

    private val drafts = mutableMapOf<Pair<String, TextField>, Draft>()

    fun draft(id: String, field: TextField, text: String) {
        val key = id to field
        drafts.remove(key)?.job?.cancel()
        drafts[key] = Draft(text, viewModelScope.launch {
            delay(DRAFT_DELAY_MS)
            drafts.remove(key)
            commit(id, field, text)
        })
    }

    /** Unsaved text for a field, so a recreated screen shows what was typed. */
    fun draftOf(id: String, field: TextField): String? = drafts[id to field]?.text

    fun flushDrafts() {
        val pending = drafts.toMap()
        drafts.clear()
        for ((key, d) in pending) {
            d.job.cancel()
            commit(key.first, key.second, d.text)
        }
    }

    private fun commit(id: String, field: TextField, text: String) = when (field) {
        TextField.Title -> setTitle(id, text)
        TextField.Detail -> setDetail(id, text)
        TextField.WaitingOn -> setWaitingOn(id, text)
    }

    // Undated tasks land in the Inbox, dated ones are already planned:
    // the web quick-add rule. [onCreated] runs once the server has the task,
    // so a form can keep its text for a retry when creation fails.
    fun create(
        title: String,
        area: TaskArea? = null,
        dueAt: Long? = null,
        parentId: String? = null,
        status: TaskStatus? = null,
        linkPath: String? = null,
        open: Boolean = false,
        onCreated: () -> Unit = {}
    ) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            try {
                val row = api.createTask(
                    NewTaskRequest(
                        title = clean,
                        status = (status ?: if (dueAt == null) TaskStatus.Inbox else TaskStatus.Todo).key,
                        area = area?.key,
                        dueAt = dueAt,
                        parentId = parentId
                    )
                )
                setTasks { it + row }
                onCreated()
                if (linkPath != null) {
                    api.addTaskLink(row.id, linkPath)
                    setLinkCount(row.id, 1)
                    // The panel may show another note by now.
                    if (notePath == linkPath) _noteTasks.update { it + row.copy(linkCount = 1) }
                }
                if (open) openTask(row.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Couldn't add task: ${e.message}"
            }
        }
    }

    fun setTitle(id: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        patch(id, "title" to JsonPrimitive(clean)) { it.copy(title = clean) }
    }

    fun setDetail(id: String, detail: String) =
        patch(id, "detail" to JsonPrimitive(detail)) { it.copy(detail = detail) }

    fun setStatus(id: String, status: TaskStatus) =
        patch(id, "status" to JsonPrimitive(status.key)) { it.copy(status = status.key) }

    fun toggleDone(task: TaskDto) =
        setStatus(task.id, if (task.isDone) TaskStatus.Todo else TaskStatus.Done)

    fun setArea(id: String, area: TaskArea?) =
        patch(id, "area" to (area?.let { JsonPrimitive(it.key) } ?: JsonNull)) { it.copy(area = area?.key) }

    fun setPriority(id: String, priority: TaskPriority) =
        patch(id, "priority" to JsonPrimitive(priority.key)) { it.copy(priority = priority.key) }

    fun setWaitingOn(id: String, who: String) {
        val clean = who.trim().ifEmpty { null }
        patch(id, "waiting_on" to (clean?.let { JsonPrimitive(it) } ?: JsonNull)) { it.copy(waitingOn = clean) }
    }

    fun setDue(id: String, dueAt: Long?) =
        patch(id, "due_at" to (dueAt?.let { JsonPrimitive(it) } ?: JsonNull)) { it.copy(dueAt = dueAt) }

    fun setReminder(id: String, remindAt: Long?) =
        patch(id, "remind_at" to (remindAt?.let { JsonPrimitive(it) } ?: JsonNull)) { it.copy(remindAt = remindAt) }

    fun delete(id: String) {
        val before = _tasks.value
        // The server deletes the whole subtree; mirror that so subtasks vanish too.
        val doomed = mutableSetOf(id)
        var grew = true
        while (grew) {
            grew = false
            for (t in before) {
                if (t.parentId != null && t.parentId in doomed && doomed.add(t.id)) grew = true
            }
        }
        // Typing into a doomed task would PATCH a row that is gone.
        drafts.keys.filter { it.first in doomed }.forEach { drafts.remove(it)?.job?.cancel() }
        val removed = before.filter { it.id in doomed }
        val removedNote = _noteTasks.value.filter { it.id in doomed }
        setTasks { list -> list.filterNot { it.id in doomed } }
        _noteTasks.update { list -> list.filterNot { it.id in doomed } }
        if (_openTaskId.value in doomed) _openTaskId.value = null
        viewModelScope.launch {
            try {
                api.deleteTask(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Put back only these rows; anything else that changed
                // meanwhile stays as it is.
                setTasks { list -> list + removed.filter { r -> list.none { it.id == r.id } } }
                _noteTasks.update { list -> list + removedNote.filter { r -> list.none { it.id == r.id } } }
                _error.value = "Couldn't delete task: ${e.message}"
            }
        }
    }

    fun addLink(id: String, notePath: String) {
        viewModelScope.launch {
            try {
                val links = api.addTaskLink(id, notePath)
                if (_openTaskId.value == id) _openLinks.value = links
                setLinkCount(id, links.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Couldn't link note: ${e.message}"
            }
        }
    }

    fun removeLink(id: String, notePath: String) {
        viewModelScope.launch {
            try {
                val links = api.removeTaskLink(id, notePath)
                if (_openTaskId.value == id) _openLinks.value = links
                setLinkCount(id, links.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Couldn't unlink note: ${e.message}"
            }
        }
    }

    // The note whose tasks [noteTasks] holds; a late reply for another note
    // is dropped.
    private var notePath: String? = null
    private var noteJob: Job? = null

    // Quiet: the panel just shows nothing when offline.
    fun loadNoteTasks(path: String) {
        notePath = path
        _noteTasks.value = emptyList()
        noteJob?.cancel()
        noteJob = viewModelScope.launch {
            try {
                val list = api.fetchTasksForNote(path)
                if (notePath == path) _noteTasks.value = list.map(::withPending)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    // Field edits not yet answered by the server, per task in send order,
    // and the last row the server returned for that task. The shown row is
    // always confirmed + pending, so a reply or a failure for one edit never
    // clobbers another, and other tasks are never touched.
    private class Edit(val apply: (TaskDto) -> TaskDto)

    private val pending = mutableMapOf<String, MutableList<Edit>>()
    private val confirmed = mutableMapOf<String, TaskDto>()

    // One PATCH in flight per task: replies then arrive in the order the
    // server applied them, so the last one is the newest server row.
    private val patchLocks = mutableMapOf<String, Mutex>()

    private fun patch(id: String, field: Pair<String, JsonElement>, local: (TaskDto) -> TaskDto) {
        val shown = _tasks.value.firstOrNull { it.id == id } ?: _noteTasks.value.firstOrNull { it.id == id }
        if (id !in confirmed && shown != null) confirmed[id] = shown
        val edit = Edit(local)
        pending.getOrPut(id) { mutableListOf() } += edit
        mapRow(id, local)
        val lock = patchLocks.getOrPut(id) { Mutex() }
        viewModelScope.launch {
            try {
                confirmed[id] = lock.withLock { api.patchTask(id, JsonObject(mapOf(field))) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Couldn't save task: ${e.message}"
            }
            pending[id]?.remove(edit)
            settle(id)
        }
    }

    private fun withPending(row: TaskDto): TaskDto =
        pending[row.id].orEmpty().fold(row) { r, edit -> edit.apply(r) }

    private fun settle(id: String) {
        confirmed[id]?.let { base ->
            val row = withPending(base)
            mapRow(id) { row }
        }
        if (pending[id].isNullOrEmpty()) {
            pending.remove(id)
            confirmed.remove(id)
        }
    }

    private fun setLinkCount(id: String, count: Int) {
        confirmed[id]?.let { confirmed[id] = it.copy(linkCount = count) }
        mapRow(id) { it.copy(linkCount = count) }
    }

    private fun mapRow(id: String, f: (TaskDto) -> TaskDto) {
        setTasks { list -> list.map { if (it.id == id) f(it) else it } }
        _noteTasks.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    companion object {
        private const val DRAFT_DELAY_MS = 600L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { TasksViewModel(container.api, container.reminders::sync) }
        }
    }
}
