package dev.mohak.scrinium.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mohak.scrinium.R
import dev.mohak.scrinium.data.remote.TaskDto
import dev.mohak.scrinium.ui.ConfirmDialog
import dev.mohak.scrinium.ui.Dot
import dev.mohak.scrinium.ui.ErrorStrip
import dev.mohak.scrinium.ui.GroupHeader
import dev.mohak.scrinium.ui.IconBtn
import dev.mohak.scrinium.ui.InputBox
import dev.mohak.scrinium.ui.MainViewModel
import dev.mohak.scrinium.ui.Sc
import dev.mohak.scrinium.ui.Segments
import dev.mohak.scrinium.ui.Sheet
import dev.mohak.scrinium.ui.SheetTitle
import dev.mohak.scrinium.ui.Sym
import dev.mohak.scrinium.ui.TaskArea
import dev.mohak.scrinium.ui.TaskPriority
import dev.mohak.scrinium.ui.TaskStatus
import dev.mohak.scrinium.ui.TasksViewModel
import dev.mohak.scrinium.ui.TextAction
import dev.mohak.scrinium.ui.TopBar
import dev.mohak.scrinium.ui.Type
import dev.mohak.scrinium.ui.dueLabel
import dev.mohak.scrinium.ui.isDone
import dev.mohak.scrinium.ui.noteTitle
import dev.mohak.scrinium.ui.stampLabel
import dev.mohak.scrinium.ui.startOfDay

/** Full-screen task editor, the phone's version of the web task drawer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskDetailScreen(vm: MainViewModel, tasksVm: TasksViewModel, id: String) {
    val all by tasksVm.tasks.collectAsStateWithLifecycle()
    val links by tasksVm.openLinks.collectAsStateWithLifecycle()
    val error by tasksVm.error.collectAsStateWithLifecycle()
    val askNotify = rememberNotificationAsk()
    val notes by vm.notesFlow.collectAsStateWithLifecycle()
    val task = all.firstOrNull { it.id == id }

    var confirmDelete by remember { mutableStateOf(false) }
    var pickDue by remember { mutableStateOf(false) }
    var pickDueTime by remember { mutableStateOf(false) }
    var pickRemindDay by remember { mutableStateOf<Long?>(null) }
    var pickRemindDate by remember { mutableStateOf(false) }
    var showLinkPicker by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(if (task?.parentId != null) "Subtask" else "", onBack = { tasksVm.closeTask() }) {
            if (task != null) IconBtn(R.drawable.ms_delete, "Delete task", { confirmDelete = true })
        }
        error?.let { ErrorStrip(it) { tasksVm.dismissError() } }
        if (task == null) {
            Text("Loading task", style = Type.meta, modifier = Modifier.padding(16.dp))
            return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            task.parentId?.let { pid ->
                val parent = all.firstOrNull { it.id == pid }
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextAction("Subtask of ${parent?.title ?: "parent"}", { tasksVm.openTask(pid) })
                }
            }
            DraftField(tasksVm, task.id, TasksViewModel.TextField.Title, task.title, "Title", Type.title.copy(fontSize = 20.sp), singleLine = true)
            DraftField(tasksVm, task.id, TasksViewModel.TextField.Detail, task.detail, "Add notes", Type.body.copy(color = Sc.text2), singleLine = false)

            FieldLabel("Status")
            Segments(TaskStatus.entries, TaskStatus.of(task.status), { it.label }, { tasksVm.setStatus(task.id, it) })
            if (task.status == TaskStatus.Waiting.key) {
                Spacer(Modifier.height(8.dp))
                DraftField(tasksVm, task.id, TasksViewModel.TextField.WaitingOn, task.waitingOn.orEmpty(), "Waiting on whom?", Type.body, singleLine = true, boxed = true)
                task.waitingSince?.let {
                    Text("Waiting since ${stampLabel(it)}", style = Type.meta, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }

            FieldLabel("Area")
            Segments(listOf<TaskArea?>(null) + TaskArea.entries, TaskArea.of(task.area), { it?.label ?: "None" }, { tasksVm.setArea(task.id, it) }, lead = { a ->
                if (a != null) Dot(a.color)
            })

            FieldLabel("Priority")
            Segments(TaskPriority.entries, TaskPriority.of(task.priority), { if (it == TaskPriority.None) "None" else it.label }, { tasksVm.setPriority(task.id, it) })

            Spacer(Modifier.height(10.dp))
            DateRow(
                R.drawable.ms_event,
                task.dueAt?.let { dueLabel(it) } ?: "No due date",
                set = task.dueAt != null,
                onPick = { pickDue = true },
                extra = if (task.dueAt != null) ({ TextAction(if (minutesOfDay(task.dueAt) == 0) "Add time" else "Change time", { pickDueTime = true }) }) else null,
                onClear = { tasksVm.setDue(task.id, null) }
            )
            DateRow(
                R.drawable.ms_alarm,
                task.remindAt?.let { stampLabel(it) } ?: "No reminder",
                set = task.remindAt != null,
                onPick = { pickRemindDate = true },
                onClear = { tasksVm.setReminder(task.id, null) }
            )

            if (task.parentId == null) Subtasks(task, all, tasksVm)

            val linked = links.size
            GroupHeader("Linked notes", if (linked > 0) "$linked" else null)
            links.forEach { path ->
                val note = notes.firstOrNull { it.path == path }
                val title = note?.let { noteTitle(it.path, it.content) } ?: path.substringAfterLast('/').removeSuffix(".md")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            tasksVm.closeTask()
                            vm.openSearchHit(MainViewModel.SearchHit(path, title, null))
                        }
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Sym(R.drawable.ms_description, tint = Sc.accent, size = 17.dp)
                    Column(Modifier.weight(1f).padding(start = 10.dp, top = 6.dp, bottom = 6.dp)) {
                        Text(title, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(path.substringBeforeLast('/', "").ifBlank { "/" }, style = Type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconBtn(R.drawable.ms_close, "Unlink note", { tasksVm.removeLink(task.id, path) }, tint = Sc.text3)
                }
            }
            Row(Modifier.padding(horizontal = 8.dp)) { TextAction("Link a note", { showLinkPicker = true }) }
            Text("created ${stampLabel(task.createdAt)}", style = Type.mono, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }

    if (task == null) return

    if (confirmDelete) {
        ConfirmDialog(
            "Delete task?",
            "Its subtasks are deleted too. This can't be undone.",
            "Delete",
            onConfirm = { tasksVm.delete(task.id) },
            onDismiss = { confirmDelete = false }
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
                askNotify()
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
    GroupHeader("Subtasks", if (kids.isNotEmpty()) "${kids.count { it.isDone }}/${kids.size}" else null)
    kids.forEach { k ->
        TaskRow(k, null, onToggle = { tasksVm.toggleDone(k) }, onOpen = { tasksVm.openTask(k.id) })
    }
    InputBox(
        newTitle,
        { newTitle = it },
        "Add subtask",
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        icon = R.drawable.ms_add,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            // Subtasks inherit the parent's area and start in its column.
            val title = newTitle
            tasksVm.create(
                title,
                area = TaskArea.of(task.area),
                parentId = task.id,
                status = TaskStatus.of(task.status).takeIf { it != TaskStatus.Done } ?: TaskStatus.Todo,
                // Kept on failure for a retry; cleared unless more was typed.
                onCreated = { if (newTitle == title) newTitle = "" }
            )
        })
    )
}

@Composable
private fun DateRow(icon: Int, label: String, set: Boolean, onPick: () -> Unit, onClear: () -> Unit, extra: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onPick).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Sym(icon, tint = if (set) Sc.accent else Sc.text3, size = 18.dp)
        Text(label, style = Type.body.copy(color = if (set) Sc.text else Sc.text3), modifier = Modifier.weight(1f).padding(start = 12.dp))
        extra?.invoke()
        if (set) IconBtn(R.drawable.ms_close, "Clear", onClear, tint = Sc.text3)
    }
}

// Text field whose edits go to the ViewModel as drafts: saved one PATCH per
// typing pause, and saved at once when the task screen is left.
@Composable
private fun DraftField(
    tasksVm: TasksViewModel,
    id: String,
    field: TasksViewModel.TextField,
    value: String,
    placeholder: String,
    style: TextStyle,
    singleLine: Boolean,
    boxed: Boolean = false
) {
    var text by remember(id, field) { mutableStateOf(tasksVm.draftOf(id, field) ?: value) }
    val onChange = { next: String ->
        text = next
        tasksVm.draft(id, field, next)
    }
    if (boxed) {
        InputBox(text, onChange, placeholder, Modifier.fillMaxWidth().padding(horizontal = 16.dp), style = style)
        return
    }
    BasicTextField(
        value = text,
        onValueChange = onChange,
        singleLine = singleLine,
        textStyle = style,
        cursorBrush = SolidColor(Sc.accent),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        decorationBox = { inner ->
            if (text.isEmpty()) Text(placeholder, style = style.copy(color = Sc.text3))
            inner()
        }
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = Type.meta, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp))
}

/** Pick a note to link: type to filter. */
@Composable
fun NotePickerDialog(paths: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = paths
        .filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
        .sorted()
        .take(100)
    Sheet(onDismiss) {
        SheetTitle("Link a note")
        InputBox(query, { query = it }, "Filter", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), icon = R.drawable.ms_search)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp).padding(top = 4.dp)) {
            items(shown) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(p) }.padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Sym(R.drawable.ms_description, tint = Sc.text3, size = 17.dp)
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(p.substringAfterLast('/').removeSuffix(".md"), style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(p.substringBeforeLast('/', "").ifBlank { "/" }, style = Type.meta, maxLines = 1)
                    }
                }
            }
        }
    }
}
