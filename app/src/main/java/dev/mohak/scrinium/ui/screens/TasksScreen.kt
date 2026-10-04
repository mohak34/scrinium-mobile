package dev.mohak.scrinium.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mohak.scrinium.data.remote.CalendarEvent
import dev.mohak.scrinium.ui.eventDays
import dev.mohak.scrinium.ui.eventTimeLabel
import dev.mohak.scrinium.data.remote.TaskDto
import dev.mohak.scrinium.ui.DOING_LIMIT
import dev.mohak.scrinium.ui.MainViewModel
import dev.mohak.scrinium.ui.TaskArea
import dev.mohak.scrinium.ui.TaskPriority
import dev.mohak.scrinium.ui.TaskStatus
import dev.mohak.scrinium.ui.TasksViewModel
import dev.mohak.scrinium.ui.dueLabel
import dev.mohak.scrinium.ui.dueText
import dev.mohak.scrinium.ui.groupByDue
import dev.mohak.scrinium.ui.isDone
import dev.mohak.scrinium.ui.isOverdue
import dev.mohak.scrinium.ui.startOfDay
import dev.mohak.scrinium.ui.startOfToday
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private enum class TasksView(val label: String) { List("List"), Board("Board"), Calendar("Calendar") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(vm: MainViewModel, tasksVm: TasksViewModel) {
    val all by tasksVm.tasks.collectAsStateWithLifecycle()
    val loading by tasksVm.loading.collectAsStateWithLifecycle()
    val error by tasksVm.error.collectAsStateWithLifecycle()
    val askNotify = rememberNotificationAsk()
    LaunchedEffect(Unit) { askNotify() }

    var view by rememberSaveable { mutableStateOf(TasksView.List) }
    var areaKey by rememberSaveable { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    // Calendar's selected day; new tasks added from the calendar land on it.
    var selectedDay by rememberSaveable { mutableStateOf(startOfToday()) }

    LaunchedEffect(Unit) { tasksVm.refresh() }

    // Subtasks live inside their parent's detail screen, as on the web.
    val visible = all.filter { it.parentId == null && (areaKey == null || it.area == areaKey) }
    val subtaskCounts = all.filter { it.parentId != null }.groupingBy { it.parentId!! }.eachCount()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tasks") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = { vm.closeTasks() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "New task")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { ErrorBanner(it) { tasksVm.dismissError() } }
            SecondaryTabRow(
                selectedTabIndex = view.ordinal,
                containerColor = MaterialTheme.colorScheme.background
            ) {
                TasksView.entries.forEach { v ->
                    Tab(selected = view == v, onClick = { view = v }, text = { Text(v.label) })
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = areaKey == null, onClick = { areaKey = null }, label = { Text("All") })
                TaskArea.entries.forEach { a ->
                    FilterChip(
                        selected = areaKey == a.key,
                        onClick = { areaKey = if (areaKey == a.key) null else a.key },
                        label = { Text(a.label) },
                        leadingIcon = { AreaDot(a) }
                    )
                }
            }
            PullToRefreshBox(
                isRefreshing = loading,
                onRefresh = { tasksVm.refresh() },
                modifier = Modifier.fillMaxSize()
            ) {
                when (view) {
                    TasksView.List -> TaskList(visible, subtaskCounts, tasksVm)
                    TasksView.Board -> TaskBoard(visible, subtaskCounts, tasksVm)
                    TasksView.Calendar -> TaskCalendar(
                        tasks = visible,
                        selectedDay = selectedDay,
                        onSelectDay = { selectedDay = it },
                        subtaskCounts = subtaskCounts,
                        tasksVm = tasksVm
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddTaskDialog(
            initialArea = TaskArea.of(areaKey),
            initialDue = if (view == TasksView.Calendar) selectedDay else null,
            onDismiss = { showAdd = false },
            onAdd = { title, area, due ->
                showAdd = false
                tasksVm.create(title, area = area, dueAt = due)
            }
        )
    }
}

@Composable
private fun TaskList(tasks: List<TaskDto>, subtaskCounts: Map<String, Int>, tasksVm: TasksViewModel) {
    val groups = groupByDue(tasks)
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (groups.isEmpty()) {
            item { EmptyHint("No tasks") }
        }
        groups.forEach { group ->
            item(key = "h-${group.label}") { SectionHeader("${group.label}  ${group.rows.size}") }
            items(group.rows, key = { it.id }) { t ->
                TaskRow(
                    task = t,
                    subtasks = subtaskCounts[t.id] ?: 0,
                    onToggle = { tasksVm.toggleDone(t) },
                    onOpen = { tasksVm.openTask(t.id) }
                )
            }
        }
    }
}

// One column per status, scrolled sideways. Cards move with a long press;
// drag and drop across columns isn't worth it on a phone.
@Composable
private fun TaskBoard(tasks: List<TaskDto>, subtaskCounts: Map<String, Int>, tasksVm: TasksViewModel) {
    LazyRow(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        items(TaskStatus.entries.toList(), key = { it.key }) { status ->
            val rows = tasks.filter { it.status == status.key }
                .let { list -> if (status == TaskStatus.Done) list.sortedByDescending { it.updatedAt } else list }
            Column(
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(status.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    val over = status == TaskStatus.Doing && rows.size > DOING_LIMIT
                    Text(
                        if (status == TaskStatus.Doing) "${rows.size}/$DOING_LIMIT" else "${rows.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 8.dp, bottom = 88.dp)
                ) {
                    items(rows, key = { it.id }) { t ->
                        BoardCard(t, subtaskCounts[t.id] ?: 0, tasksVm)
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardCard(task: TaskDto, subtasks: Int, tasksVm: TasksViewModel) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .combinedClickable(
                    onClick = { tasksVm.openTask(task.id) },
                    onLongClick = { menu = true }
                )
                .padding(10.dp)
        ) {
            Text(
                task.title,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (task.isDone) TextDecoration.LineThrough else null
            )
            TaskMeta(task, subtasks)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            TaskStatus.entries.filter { it.key != task.status }.forEach { s ->
                DropdownMenuItem(
                    text = { Text("Move to ${s.label}") },
                    onClick = {
                        menu = false
                        tasksVm.setStatus(task.id, s)
                    }
                )
            }
        }
    }
}

// Month grid of due dates with the linked Google Calendar's events on top,
// read-only like the web.
@Composable
private fun TaskCalendar(
    tasks: List<TaskDto>,
    selectedDay: Long,
    onSelectDay: (Long) -> Unit,
    subtaskCounts: Map<String, Int>,
    tasksVm: TasksViewModel
) {
    var month by rememberSaveable {
        mutableStateOf(Calendar.getInstance().apply {
            timeInMillis = selectedDay
            set(Calendar.DAY_OF_MONTH, 1)
        }.let { startOfDay(it.timeInMillis) })
    }
    val byDay = tasks.filter { it.dueAt != null }.groupBy { startOfDay(it.dueAt!!) }
    val calendar by tasksVm.calendar.collectAsStateWithLifecycle()
    LaunchedEffect(month) {
        val next = Calendar.getInstance().apply {
            timeInMillis = month
            add(Calendar.MONTH, 1)
        }.timeInMillis
        tasksVm.loadEvents(month, next)
    }
    val eventsByDay = remember(calendar) {
        buildMap<Long, MutableList<CalendarEvent>> {
            for (e in calendar.events) for (d in eventDays(e)) getOrPut(d) { mutableListOf() } += e
        }
    }
    val cal = Calendar.getInstance().apply { timeInMillis = month }
    val title = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
    val firstDow = cal.firstDayOfWeek
    val lead = (cal.get(Calendar.DAY_OF_WEEK) - firstDow + 7) % 7
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val today = startOfToday()
    val weekdays = DateFormatSymbols.getInstance().shortWeekdays

    fun shiftMonth(by: Int) {
        month = Calendar.getInstance().apply {
            timeInMillis = month
            add(Calendar.MONTH, by)
        }.timeInMillis
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { shiftMonth(-1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
                }
                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    month = Calendar.getInstance().apply {
                        timeInMillis = today
                        set(Calendar.DAY_OF_MONTH, 1)
                    }.timeInMillis
                    onSelectDay(today)
                }) { Text("Today") }
                IconButton(onClick = { shiftMonth(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
                }
            }
        }
        item {
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (i in 0 until 7) {
                        val dow = (firstDow - 1 + i) % 7 + 1
                        Text(
                            weekdays[dow].take(2),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
                val cells = lead + daysInMonth
                val rows = (cells + 6) / 7
                for (r in 0 until rows) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        for (c in 0 until 7) {
                            val dayNum = r * 7 + c - lead + 1
                            Box(modifier = Modifier.weight(1f).aspectRatio(1f).padding(2.dp)) {
                                if (dayNum in 1..daysInMonth) {
                                    val dayMs = Calendar.getInstance().apply {
                                        timeInMillis = month
                                        set(Calendar.DAY_OF_MONTH, dayNum)
                                    }.timeInMillis
                                    DayCell(
                                        day = dayNum,
                                        tasks = byDay[dayMs].orEmpty(),
                                        events = eventsByDay[dayMs]?.size ?: 0,
                                        isToday = dayMs == today,
                                        selected = dayMs == selectedDay,
                                        onClick = { onSelectDay(dayMs) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        val dayTasks = byDay[selectedDay].orEmpty()
        item {
            SectionHeader(SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(selectedDay))
        }
        val dayEvents = eventsByDay[selectedDay].orEmpty()
        items(dayEvents, key = { "e-${it.id}" }) { e ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    eventTimeLabel(e),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(120.dp)
                )
                Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (dayTasks.isEmpty() && dayEvents.isEmpty()) item { EmptyHint("Nothing due") }
        if (calendar.needsConnect) item { EmptyHint("Connect Google Calendar on the web to see events here") }
        items(dayTasks, key = { it.id }) { t ->
            TaskRow(
                task = t,
                subtasks = subtaskCounts[t.id] ?: 0,
                onToggle = { tasksVm.toggleDone(t) },
                onOpen = { tasksVm.openTask(t.id) }
            )
        }
        item { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
private fun DayCell(day: Int, tasks: List<TaskDto>, events: Int, isToday: Boolean, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val open = tasks.filter { !it.isDone }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) colors.surfaceContainerHighest else colors.surfaceContainerLow)
            .then(if (isToday) Modifier.border(1.dp, colors.primary, RoundedCornerShape(6.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "$day",
            style = MaterialTheme.typography.labelMedium,
            color = if (isToday) colors.primary else colors.onSurface
        )
        if (tasks.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                open.take(3).forEach { t ->
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(TaskArea.of(t.area)?.color ?: colors.primary)
                    )
                }
            }
            if (open.size > 3) {
                Text("+${open.size - 3}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
        // Events: a thin neutral bar, kept apart from the area-colored task dots.
        if (events > 0) {
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.6f)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(colors.outline)
            )
        }
    }
}

@Composable
fun TaskRow(task: TaskDto, subtasks: Int, onToggle: () -> Unit, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = task.isDone, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                task.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                color = if (task.isDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
            TaskMeta(task, subtasks)
        }
    }
}

// Area, due date, status, priority, subtask and link counts on one line.
@Composable
private fun TaskMeta(task: TaskDto, subtasks: Int) {
    val colors = MaterialTheme.colorScheme
    val parts = buildList {
        if (task.dueAt != null) add(task.dueText() to if (task.isOverdue()) colors.error else colors.onSurfaceVariant)
        if (task.status != TaskStatus.Done.key) add(TaskStatus.of(task.status).label to colors.onSurfaceVariant)
        val p = TaskPriority.of(task.priority)
        if (p != TaskPriority.None) {
            add(p.label to if (p == TaskPriority.Urgent || p == TaskPriority.High) colors.tertiary else colors.onSurfaceVariant)
        }
        if (subtasks > 0) add("$subtasks subtasks" to colors.onSurfaceVariant)
        if (task.linkCount > 0) add("${task.linkCount} notes" to colors.onSurfaceVariant)
        if (task.remindAt != null) add("reminder" to colors.onSurfaceVariant)
    }
    val area = TaskArea.of(task.area)
    if (parts.isEmpty() && area == null) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 2.dp)
    ) {
        if (area != null) {
            AreaDot(area)
            Text(area.label, style = MaterialTheme.typography.labelSmall, color = area.color)
        }
        parts.forEach { (text, color) ->
            Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
        }
    }
}

@Composable
fun AreaDot(area: TaskArea) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(area.color))
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp)
    )
}

@Composable
fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun AddTaskDialog(
    initialArea: TaskArea?,
    initialDue: Long?,
    onDismiss: () -> Unit,
    onAdd: (String, TaskArea?, Long?) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var area by remember { mutableStateOf(initialArea) }
    var due by remember { mutableStateOf(initialDue) }
    var pickDate by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    placeholder = { Text("Title") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TaskArea.entries.forEach { a ->
                        FilterChip(
                            selected = area == a,
                            onClick = { area = if (area == a) null else a },
                            label = { Text(a.label) },
                            leadingIcon = { AreaDot(a) }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pickDate = true }) {
                        Text(if (due == null) "Add due date" else dueLabel(due))
                    }
                    if (due != null) {
                        IconButton(onClick = { due = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear due date")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(title, area, due) }, enabled = title.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (pickDate) {
        LocalDatePickerDialog(
            initial = due,
            onDismiss = { pickDate = false },
            onPick = {
                pickDate = false
                due = it
            }
        )
    }
}
