package dev.mohak.scrinium.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.mohak.scrinium.data.remote.CalendarResponse
import dev.mohak.scrinium.data.remote.NewTaskRequest
import dev.mohak.scrinium.data.remote.ScriniumApi
import dev.mohak.scrinium.data.remote.TaskDto
import dev.mohak.scrinium.di.AppContainer
import dev.mohak.scrinium.reminders.Reminders
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tasks are server-only (SQLite on the server, not vault files), so this
 * talks to the API directly and holds no Room state. Edits paint
 * optimistically and roll back on failure, like the web store. Offline means
 * a visible error, never a silent queue.
 */
class TasksViewModel(private val api: ScriniumApi, private val reminders: Reminders) : ViewModel() {

    private val _tasks = MutableStateFlow<List<TaskDto>>(emptyList())
    val tasks: StateFlow<List<TaskDto>> = _tasks.asStateFlow()

    // Area filter shared by the Tasks, Board and Calendar tabs. Null is all.
    private val _areaFilter = MutableStateFlow<String?>(null)
    val areaFilter: StateFlow<String?> = _areaFilter.asStateFlow()

    fun setAreaFilter(key: String?) {
        _areaFilter.value = key
    }

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

    // Settings > Reminders: the switch and what is scheduled right now.
    private val _remindersOn = MutableStateFlow(reminders.enabled)
    val remindersOn: StateFlow<Boolean> = _remindersOn.asStateFlow()

    val scheduledReminders: StateFlow<List<Reminders.Entry>> = reminders.observeScheduled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), reminders.scheduled())

    fun setRemindersOn(on: Boolean) {
        reminders.enabled = on
        _remindersOn.value = on
        if (on && loaded) reminders.sync(_tasks.value)
    }

    fun dismissError() {
        _error.value = null
    }

    // Phone alarms follow the task list, but only once a full list has
    // loaded: a partial list (one task added from a note) must not cancel
    // every other reminder.
    private var loaded = false

    init {
        viewModelScope.launch {
            _tasks.collect { if (loaded) reminders.sync(it) }
        }
    }

    private suspend fun load() {
        val list = api.fetchTasks()
        loaded = true
        _tasks.value = list
    }

    // [quiet] is the app-foreground refresh that keeps reminders current:
    // offline then is not an error worth showing.
    fun refresh(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) {
                _loading.value = true
                _error.value = null
            }
            try {
                load()
            } catch (e: Exception) {
                if (!quiet) _error.value = "Couldn't load tasks: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    fun openTask(id: String) {
        _openTaskId.value = id
        _openLinks.value = emptyList()
        viewModelScope.launch {
            // A task opened from a note panel may not be in the list yet.
            if (_tasks.value.none { it.id == id }) {
                try {
                    load()
                } catch (e: Exception) {
                    _error.value = "Couldn't load task: ${e.message}"
                }
            }
            try {
                _openLinks.value = api.fetchTaskLinks(id)
            } catch (_: Exception) {
            }
        }
    }

    fun closeTask() {
        _openTaskId.value = null
    }

    // Undated tasks land in the Inbox, dated ones are already planned:
    // the web quick-add rule.
    fun create(
        title: String,
        area: TaskArea? = null,
        dueAt: Long? = null,
        parentId: String? = null,
        status: TaskStatus? = null,
        linkPath: String? = null,
        open: Boolean = false
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
                _tasks.update { it + row }
                if (linkPath != null) {
                    api.addTaskLink(row.id, linkPath)
                    val linked = row.copy(linkCount = 1)
                    replace(linked)
                    _noteTasks.update { it + linked }
                }
                if (open) openTask(row.id)
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
        val beforeNote = _noteTasks.value
        // The server deletes the whole subtree; mirror that so subtasks vanish too.
        val doomed = mutableSetOf(id)
        var grew = true
        while (grew) {
            grew = false
            for (t in before) {
                if (t.parentId != null && t.parentId in doomed && doomed.add(t.id)) grew = true
            }
        }
        _tasks.value = before.filterNot { it.id in doomed }
        _noteTasks.update { list -> list.filterNot { it.id in doomed } }
        if (_openTaskId.value in doomed) _openTaskId.value = null
        viewModelScope.launch {
            try {
                api.deleteTask(id)
            } catch (e: Exception) {
                _tasks.value = before
                _noteTasks.value = beforeNote
                _error.value = "Couldn't delete task: ${e.message}"
            }
        }
    }

    fun addLink(id: String, notePath: String) {
        viewModelScope.launch {
            try {
                val links = api.addTaskLink(id, notePath)
                if (_openTaskId.value == id) _openLinks.value = links
                _tasks.value.firstOrNull { it.id == id }?.let { replace(it.copy(linkCount = links.size)) }
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
                _tasks.value.firstOrNull { it.id == id }?.let { replace(it.copy(linkCount = links.size)) }
            } catch (e: Exception) {
                _error.value = "Couldn't unlink note: ${e.message}"
            }
        }
    }

    // Quiet: the panel just shows nothing when offline.
    fun loadNoteTasks(path: String) {
        _noteTasks.value = emptyList()
        viewModelScope.launch {
            try {
                _noteTasks.value = api.fetchTasksForNote(path)
            } catch (_: Exception) {
            }
        }
    }

    private fun patch(id: String, field: Pair<String, JsonElement>, local: (TaskDto) -> TaskDto) {
        val before = _tasks.value
        val beforeNote = _noteTasks.value
        _tasks.update { list -> list.map { if (it.id == id) local(it) else it } }
        _noteTasks.update { list -> list.map { if (it.id == id) local(it) else it } }
        viewModelScope.launch {
            try {
                replace(api.patchTask(id, JsonObject(mapOf(field))))
            } catch (e: Exception) {
                _tasks.value = before
                _noteTasks.value = beforeNote
                _error.value = "Couldn't save task: ${e.message}"
            }
        }
    }

    private fun replace(row: TaskDto) {
        _tasks.update { list -> list.map { if (it.id == row.id) row else it } }
        _noteTasks.update { list -> list.map { if (it.id == row.id) row else it } }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { TasksViewModel(container.api, container.reminders) }
        }
    }
}
