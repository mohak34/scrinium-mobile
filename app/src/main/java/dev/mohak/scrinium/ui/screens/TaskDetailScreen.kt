package dev.mohak.scrinium.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mohak.scrinium.data.remote.TaskDto
import dev.mohak.scrinium.ui.MainViewModel
import dev.mohak.scrinium.ui.TaskArea
import dev.mohak.scrinium.ui.TaskPriority
import dev.mohak.scrinium.ui.TaskStatus
import dev.mohak.scrinium.ui.TasksViewModel
import dev.mohak.scrinium.ui.dueLabel
import dev.mohak.scrinium.ui.noteTitle
import dev.mohak.scrinium.ui.stampLabel
import dev.mohak.scrinium.ui.startOfDay
import kotlinx.coroutines.delay

/** Full-screen task editor, the phone's version of the web task drawer. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskDetailScreen(vm: MainViewModel, tasksVm: TasksViewModel, id: String) {
    val all by tasksVm.tasks.collectAsStateWithLifecycle()
    val links by tasksVm.openLinks.collectAsStateWithLifecycle()
    val error by tasksVm.error.collectAsStateWithLifecycle()
    val notes by vm.notesFlow.collectAsStateWithLifecycle()
    val task = all.firstOrNull { it.id == id }

    var confirmDelete by remember { mutableStateOf(false) }
    var pickDue by remember { mutableStateOf(false) }
    var pickDueTime by remember { mutableStateOf(false) }
    var pickRemindDay by remember { mutableStateOf<Long?>(null) }
    var pickRemindDate by remember { mutableStateOf(false) }
    var showLinkPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (task?.parentId != null) "Subtask" else "Task") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = { tasksVm.closeTask() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (task != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete task")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            error?.let { ErrorBanner(it) { tasksVm.dismissError() } }
            if (task == null) {
                EmptyHint("Loading task")
                return@Column
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                task.parentId?.let { pid ->
                    val parent = all.firstOrNull { it.id == pid }
                    TextButton(onClick = { tasksVm.openTask(pid) }) {
                        Text("Subtask of ${parent?.title ?: "parent"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                DebouncedField(
                    key = task.id,
                    value = task.title,
                    label = "Title",
                    singleLine = true,
                    onCommit = { tasksVm.setTitle(task.id, it) }
                )
                DebouncedField(
                    key = task.id,
                    value = task.detail,
                    label = "Notes",
                    singleLine = false,
                    onCommit = { tasksVm.setDetail(task.id, it) }
                )

                FieldLabel("Status")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TaskStatus.entries.forEach { s ->
                        FilterChip(
                            selected = task.status == s.key,
                            onClick = { tasksVm.setStatus(task.id, s) },
                            label = { Text(s.label) }
                        )
                    }
                }
                if (task.status == TaskStatus.Waiting.key) {
                    DebouncedField(
                        key = task.id,
                        value = task.waitingOn.orEmpty(),
                        label = "Waiting on",
                        singleLine = true,
                        onCommit = { tasksVm.setWaitingOn(task.id, it) }
                    )
                    task.waitingSince?.let {
                        Text(
                            "Waiting since ${stampLabel(it)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                FieldLabel("Area")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = task.area == null,
                        onClick = { tasksVm.setArea(task.id, null) },
                        label = { Text("None") }
                    )
                    TaskArea.entries.forEach { a ->
                        FilterChip(
                            selected = task.area == a.key,
                            onClick = { tasksVm.setArea(task.id, a) },
                            label = { Text(a.label) },
                            leadingIcon = { AreaDot(a) }
                        )
                    }
                }

                FieldLabel("Priority")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TaskPriority.entries.forEach { p ->
                        FilterChip(
                            selected = task.priority == p.key,
                            onClick = { tasksVm.setPriority(task.id, p) },
                            label = { Text(if (p == TaskPriority.None) "None" else p.label) }
                        )
                    }
                }

                FieldLabel("Due")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pickDue = true }) { Text(dueLabel(task.dueAt)) }
                    if (task.dueAt != null) {
                        TextButton(onClick = { pickDueTime = true }) {
                            Text(if (minutesOfDay(task.dueAt) == 0) "Add time" else "Change time")
                        }
                        IconButton(onClick = { tasksVm.setDue(task.id, null) }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear due date")
                        }
                    }
                }

                FieldLabel("Reminder")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pickRemindDate = true }) {
                        Text(task.remindAt?.let { stampLabel(it) } ?: "No reminder")
                    }
                    if (task.remindAt != null) {
                        IconButton(onClick = { tasksVm.setReminder(task.id, null) }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear reminder")
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                if (task.parentId == null) {
                    Subtasks(task, all, tasksVm)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                FieldLabel("Linked notes")
                links.forEach { path ->
                    val note = notes.firstOrNull { it.path == path }
                    val title = note?.let { noteTitle(it.path, it.content) }
                        ?: path.substringAfterLast('/').removeSuffix(".md")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                tasksVm.closeTask()
                                vm.openSearchHit(MainViewModel.SearchHit(path, title, null))
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { tasksVm.removeLink(task.id, path) }) {
                            Icon(Icons.Default.Close, contentDescription = "Unlink note")
                        }
                    }
                }
                TextButton(onClick = { showLinkPicker = true }) { Text("Link a note") }
                Text(
                    "Created ${stampLabel(task.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
    }

    if (task == null) return

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete task?") },
            text = { Text("Its subtasks are deleted too. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    tasksVm.delete(task.id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
    if (pickDue) {
        LocalDatePickerDialog(
            initial = task.dueAt,
            onDismiss = { pickDue = false },
            onPick = { day ->
                pickDue = false
                // Keep an existing due time when only the day changes.
                tasksVm.setDue(task.id, atMinutes(day, task.dueAt?.let(::minutesOfDay) ?: 0))
            }
        )
    }
    if (pickDueTime && task.dueAt != null) {
        TimePickerDialog(
            initialMinutes = minutesOfDay(task.dueAt).takeIf { it != 0 } ?: (9 * 60),
            onDismiss = { pickDueTime = false },
            onPick = { m ->
                pickDueTime = false
                tasksVm.setDue(task.id, atMinutes(startOfDay(task.dueAt), m))
            }
        )
    }
    // Reminder: pick a day, then a time.
    if (pickRemindDate) {
        LocalDatePickerDialog(
            initial = task.remindAt ?: task.dueAt,
            onDismiss = { pickRemindDate = false },
            onPick = { day ->
                pickRemindDate = false
                pickRemindDay = day
            }
        )
    }
    pickRemindDay?.let { day ->
        TimePickerDialog(
            initialMinutes = task.remindAt?.let(::minutesOfDay) ?: (9 * 60),
            onDismiss = { pickRemindDay = null },
            onPick = { m ->
                pickRemindDay = null
                tasksVm.setReminder(task.id, atMinutes(day, m))
            }
        )
    }
    if (showLinkPicker) {
        NotePickerDialog(
            paths = notes.map { it.path }.filter { it !in links },
            onDismiss = { showLinkPicker = false },
            onPick = {
                showLinkPicker = false
                tasksVm.addLink(task.id, it)
            }
        )
    }
}

@Composable
private fun Subtasks(task: TaskDto, all: List<TaskDto>, tasksVm: TasksViewModel) {
    val kids = all.filter { it.parentId == task.id }
        .sortedWith(compareBy({ it.position }, { it.createdAt }))
    var newTitle by remember(task.id) { mutableStateOf("") }
    FieldLabel("Subtasks${if (kids.isNotEmpty()) "  ${kids.count { it.status == "done" }}/${kids.size}" else ""}")
    kids.forEach { k ->
        TaskRow(task = k, subtasks = 0, onToggle = { tasksVm.toggleDone(k) }, onOpen = { tasksVm.openTask(k.id) })
    }
    OutlinedTextField(
        value = newTitle,
        onValueChange = { newTitle = it },
        singleLine = true,
        placeholder = { Text("Add subtask") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            // Subtasks inherit the parent's area and start in its column.
            tasksVm.create(
                newTitle,
                area = TaskArea.of(task.area),
                parentId = task.id,
                status = TaskStatus.of(task.status).takeIf { it != TaskStatus.Done } ?: TaskStatus.Todo
            )
            newTitle = ""
        }),
        modifier = Modifier.fillMaxWidth()
    )
}

// Text field that saves itself ~600ms after typing stops, so a task edit
// costs one PATCH per pause rather than one per keystroke.
@Composable
private fun DebouncedField(key: String, value: String, label: String, singleLine: Boolean, onCommit: (String) -> Unit) {
    var text by remember(key) { mutableStateOf(value) }
    LaunchedEffect(key, text) {
        if (text == value) return@LaunchedEffect
        delay(600)
        onCommit(text)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp)
    )
}

@Composable
fun NotePickerDialog(paths: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = paths
        .filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
        .sorted()
        .take(100)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link a note") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Filter") },
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(shown) { p ->
                        Text(
                            p,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(p) }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
