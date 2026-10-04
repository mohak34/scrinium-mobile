package dev.mohak.scrinium.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mohak.scrinium.R
import dev.mohak.scrinium.data.remote.CalendarEvent
import dev.mohak.scrinium.data.remote.TaskDto
import dev.mohak.scrinium.ui.DOING_LIMIT
import dev.mohak.scrinium.ui.Divider
import dev.mohak.scrinium.ui.Dot
import dev.mohak.scrinium.ui.EmptyState
import dev.mohak.scrinium.ui.ErrorStrip
import dev.mohak.scrinium.ui.Fab
import dev.mohak.scrinium.ui.GroupHeader
import dev.mohak.scrinium.ui.IconBtn
import dev.mohak.scrinium.ui.InputBox
import dev.mohak.scrinium.ui.Menu
import dev.mohak.scrinium.ui.MenuRow
import dev.mohak.scrinium.ui.Sc
import dev.mohak.scrinium.ui.Segments
import dev.mohak.scrinium.ui.Sheet
import dev.mohak.scrinium.ui.SheetTitle
import dev.mohak.scrinium.ui.Sym
import dev.mohak.scrinium.ui.TaskArea
import dev.mohak.scrinium.ui.TaskCheck
import dev.mohak.scrinium.ui.TaskPriority
import dev.mohak.scrinium.ui.TaskStatus
import dev.mohak.scrinium.ui.TasksViewModel
import dev.mohak.scrinium.ui.TextAction
import dev.mohak.scrinium.ui.TextTabs
import dev.mohak.scrinium.ui.TopBar
import dev.mohak.scrinium.ui.Type
import dev.mohak.scrinium.ui.eventDays
import dev.mohak.scrinium.ui.groupByStatus
import dev.mohak.scrinium.ui.isDone
import dev.mohak.scrinium.ui.isOverdue
import dev.mohak.scrinium.ui.isDueToday
import dev.mohak.scrinium.ui.shortDue
import dev.mohak.scrinium.ui.startOfDay
import dev.mohak.scrinium.ui.startOfToday
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// The Tasks, Board and Calendar tabs. All three read TasksViewModel's list
// and share its area filter. Subtasks live inside their parent's detail
// screen, as on the web, so only top-level tasks show here.

private fun topLevel(all: List<TaskDto>, area: String?) =
    all.filter { it.parentId == null && (area == null || it.area == area) }

// parentId -> (done, total) for the "1/3" subtask label.
private fun subtaskProgress(all: List<TaskDto>): Map<String, Pair<Int, Int>> =
    all.filter { it.parentId != null }.groupBy { it.parentId!! }
        .mapValues { (_, kids) -> kids.count { it.isDone } to kids.size }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(tasksVm: TasksViewModel) {
    val all by tasksVm.tasks.collectAsStateWithLifecycle()
    val loading by tasksVm.loading.collectAsStateWithLifecycle()
    val error by tasksVm.error.collectAsStateWithLifecycle()
    val area by tasksVm.areaFilter.collectAsStateWithLifecycle()
    val askNotify = rememberNotificationAsk()
    LaunchedEffect(Unit) {
        askNotify()
        tasksVm.refresh()
    }
    var showAdd by remember { mutableStateOf(false) }
    var showDone by rememberSaveable { mutableStateOf(false) }

    val groups = groupByStatus(topLevel(all, area))
    val progress = subtaskProgress(all)
    val areaTabs = listOf<TaskArea?>(null) + TaskArea.entries

    Column(Modifier.fillMaxSize()) {
        TopBar("Tasks")
        TextTabs(areaTabs.map { it?.label ?: "All" }, areaTabs.indexOf(TaskArea.of(area))) {
            tasksVm.setAreaFilter(areaTabs[it]?.key)
        }
        error?.let { ErrorStrip(it) { tasksVm.dismissError() } }
        Box(Modifier.weight(1f)) {
            PullToRefreshBox(isRefreshing = loading, onRefresh = { tasksVm.refresh() }, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    if (groups.isEmpty() && !loading) item { EmptyState(R.drawable.ms_task_alt, "No tasks", "Tap + to add one.") }
                    groups.forEach { g ->
                        val done = g.status == TaskStatus.Done
                        item(key = "h-${g.status.key}") {
                            GroupHeader(
                                g.status.label,
                                if (g.status == TaskStatus.Doing) "${g.rows.size}/$DOING_LIMIT" else "${g.rows.size}",
                                color = if (done) Sc.text3 else if (g.status == TaskStatus.Doing && g.rows.size > DOING_LIMIT) Sc.red else Sc.text,
                                onClick = if (done) ({ showDone = !showDone }) else null
                            )
                        }
                        if (!done || showDone) {
                            items(g.rows, key = { it.id }) { t ->
                                TaskRow(t, progress[t.id], onToggle = { tasksVm.toggleDone(t) }, onOpen = { tasksVm.openTask(t.id) })
                            }
                        }
                    }
                }
            }
            Fab(R.drawable.ms_add, "New task", { showAdd = true }, Modifier.align(Alignment.BottomEnd))
        }
    }

    if (showAdd) {
        QuickAddSheet(TaskArea.of(area), null, onDismiss = { showAdd = false }) { title, a, due ->
            tasksVm.create(title, area = a, dueAt = due)
        }
    }
}

// Board: one status column per page, column names as tabs. Long-press a
// task to move it to another column.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(tasksVm: TasksViewModel) {
    val all by tasksVm.tasks.collectAsStateWithLifecycle()
    val loading by tasksVm.loading.collectAsStateWithLifecycle()
    val error by tasksVm.error.collectAsStateWithLifecycle()
    val area by tasksVm.areaFilter.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { tasksVm.refresh() }
    var showAdd by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<TaskDto?>(null) }

    val statuses = TaskStatus.entries
    val pager = rememberPagerState { statuses.size }
    val scope = rememberCoroutineScope()
    val tasks = topLevel(all, area)
    val progress = subtaskProgress(all)
    val columns = statuses.map { s ->
        tasks.filter { it.status == s.key }.let { rows ->
            if (s == TaskStatus.Done) rows.sortedByDescending { it.updatedAt }
            else rows.sortedWith(compareBy({ it.position }, { it.createdAt }))
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Board") { AreaFilterButton(area) { tasksVm.setAreaFilter(it) } }
        TextTabs(
            statuses.mapIndexed { i, s ->
                val n = columns[i].size
                if (s == TaskStatus.Doing) "${s.label} $n/$DOING_LIMIT" else "${s.label} $n"
            },
            pager.currentPage
        ) { scope.launch { pager.animateScrollToPage(it) } }
        error?.let { ErrorStrip(it) { tasksVm.dismissError() } }
        Box(Modifier.weight(1f)) {
            PullToRefreshBox(isRefreshing = loading, onRefresh = { tasksVm.refresh() }, modifier = Modifier.fillMaxSize()) {
                HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                    val rows = columns[page]
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)) {
                        if (rows.isEmpty()) item { EmptyState(R.drawable.ms_view_kanban, "Nothing in ${statuses[page].label}", "Long-press a task to move it.") }
                        items(rows, key = { it.id }) { t ->
                            TaskRow(
                                t,
                                progress[t.id],
                                onToggle = { tasksVm.toggleDone(t) },
                                onOpen = { tasksVm.openTask(t.id) },
                                onLongPress = { moving = t },
                                showStatus = false
                            )
                        }
                    }
                }
            }
            Fab(R.drawable.ms_add, "New task", { showAdd = true }, Modifier.align(Alignment.BottomEnd))
        }
    }

    moving?.let { t ->
        Sheet(onDismiss = { moving = null }) {
            SheetTitle(t.title, "Move to")
            Divider()
            statuses.filter { it.key != t.status }.forEach { s ->
                MenuRow(null, s.label, {
                    moving = null
                    tasksVm.setStatus(t.id, s)
                })
            }
        }
    }
    if (showAdd) {
        val status = statuses[pager.currentPage]
        QuickAddSheet(TaskArea.of(area), null, onDismiss = { showAdd = false }) { title, a, due ->
            tasksVm.create(title, area = a, dueAt = due, status = status)
        }
    }
}

// Month grid of due dates with the linked Google Calendar's events, and the
// selected day's agenda below. Events are read-only, like the web.
@Composable
fun CalendarScreen(tasksVm: TasksViewModel) {
    val all by tasksVm.tasks.collectAsStateWithLifecycle()
    val area by tasksVm.areaFilter.collectAsStateWithLifecycle()
    val error by tasksVm.error.collectAsStateWithLifecycle()
    val calendar by tasksVm.calendar.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { tasksVm.refresh() }
    var selectedDay by rememberSaveable { mutableStateOf(startOfToday()) }
    var month by rememberSaveable { mutableStateOf(monthStart(startOfToday())) }
    var showAdd by remember { mutableStateOf(false) }

    LaunchedEffect(month) {
        val next = Calendar.getInstance().apply {
            timeInMillis = month
            add(Calendar.MONTH, 1)
        }.timeInMillis
        tasksVm.loadEvents(month, next)
    }
    val tasks = topLevel(all, area)
    val progress = subtaskProgress(all)
    val byDay = tasks.filter { it.dueAt != null }.groupBy { startOfDay(it.dueAt!!) }
    val eventsByDay = remember(calendar) {
        buildMap<Long, MutableList<CalendarEvent>> {
            for (e in calendar.events) for (d in eventDays(e)) getOrPut(d) { mutableListOf() } += e
        }
    }
    val today = startOfToday()

    fun shiftMonth(by: Int) {
        month = Calendar.getInstance().apply {
            timeInMillis = month
            add(Calendar.MONTH, by)
        }.timeInMillis
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(month))) {
            AreaFilterButton(area) { tasksVm.setAreaFilter(it) }
            IconBtn(R.drawable.ms_today, "Today", {
                month = monthStart(today)
                selectedDay = today
            })
            IconBtn(R.drawable.ms_chevron_left, "Previous month", { shiftMonth(-1) })
            IconBtn(R.drawable.ms_chevron_right, "Next month", { shiftMonth(1) })
        }
        error?.let { ErrorStrip(it) { tasksVm.dismissError() } }
        MonthGrid(month, selectedDay, today, byDay, eventsByDay) { selectedDay = it }
        Divider()
        Box(Modifier.weight(1f)) {
            val dayTasks = byDay[selectedDay].orEmpty().sortedBy { it.dueAt }
            val dayEvents = eventsByDay[selectedDay].orEmpty().sortedWith(compareBy({ !it.allDay }, { it.start }))
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                item {
                    GroupHeader(
                        SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(selectedDay)),
                        "${dayTasks.size + dayEvents.size}"
                    )
                }
                items(dayEvents, key = { "e-${it.id}" }) { e -> EventRow(e) }
                items(dayTasks, key = { it.id }) { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            timeOrAllDay(t.dueAt!!),
                            style = Type.mono,
                            modifier = Modifier.padding(start = 16.dp).width(52.dp)
                        )
                        Box(Modifier.weight(1f)) {
                            TaskRow(t, progress[t.id], onToggle = { tasksVm.toggleDone(t) }, onOpen = { tasksVm.openTask(t.id) }, showDue = false, inset = 0.dp)
                        }
                    }
                }
                if (dayTasks.isEmpty() && dayEvents.isEmpty()) {
                    item { Text("Nothing on this day", style = Type.meta, modifier = Modifier.padding(16.dp)) }
                }
                if (calendar.needsConnect) {
                    item { Text("Connect Google Calendar on the web to see events here.", style = Type.meta, modifier = Modifier.padding(16.dp)) }
                }
            }
            Fab(R.drawable.ms_add, "New task", { showAdd = true }, Modifier.align(Alignment.BottomEnd))
        }
    }

    if (showAdd) {
        QuickAddSheet(TaskArea.of(area), selectedDay, onDismiss = { showAdd = false }) { title, a, due ->
            tasksVm.create(title, area = a, dueAt = due)
        }
    }
}

private fun monthStart(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.DAY_OF_MONTH, 1)
}.let { startOfDay(it.timeInMillis) }

private fun timeOrAllDay(ms: Long): String =
    if (minutesOfDay(ms) == 0) "all day" else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

@Composable
private fun MonthGrid(
    month: Long,
    selectedDay: Long,
    today: Long,
    byDay: Map<Long, List<TaskDto>>,
    eventsByDay: Map<Long, List<CalendarEvent>>,
    onSelect: (Long) -> Unit
) {
    val cal = Calendar.getInstance().apply { timeInMillis = month }
    val firstDow = cal.firstDayOfWeek
    val lead = (cal.get(Calendar.DAY_OF_WEEK) - firstDow + 7) % 7
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val weekdays = DateFormatSymbols.getInstance().shortWeekdays
    Column(Modifier.padding(horizontal = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            for (i in 0 until 7) {
                val dow = (firstDow - 1 + i) % 7 + 1
                Text(weekdays[dow].take(1), style = Type.meta, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        val rows = (lead + daysInMonth + 6) / 7
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val dayNum = r * 7 + c - lead + 1
                    Box(Modifier.weight(1f).height(46.dp), contentAlignment = Alignment.TopCenter) {
                        if (dayNum in 1..daysInMonth) {
                            val dayMs = Calendar.getInstance().apply {
                                timeInMillis = month
                                set(Calendar.DAY_OF_MONTH, dayNum)
                            }.timeInMillis
                            DayCell(dayNum, byDay[dayMs].orEmpty(), eventsByDay[dayMs].orEmpty().size, dayMs == today, dayMs == selectedDay) { onSelect(dayMs) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: Int, tasks: List<TaskDto>, events: Int, isToday: Boolean, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(top = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) Sc.fill else androidx.compose.ui.graphics.Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "$day",
                style = Type.body.copy(
                    color = when {
                        selected -> Sc.onFill
                        isToday -> Sc.accent
                        else -> Sc.text
                    },
                    fontWeight = if (selected || isToday) FontWeight.SemiBold else FontWeight.Normal
                )
            )
        }
        Spacer(Modifier.height(3.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            tasks.filter { !it.isDone }.take(3).forEach { Dot(TaskArea.of(it.area)?.color ?: Sc.accent, 4.dp) }
            if (events > 0) Dot(Sc.text3, 4.dp)
        }
    }
}

@Composable
private fun EventRow(e: CalendarEvent) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (e.allDay) "all day" else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.start)),
            style = Type.mono,
            modifier = Modifier.width(52.dp)
        )
        Box(Modifier.width(3.dp).height(32.dp).clip(RoundedCornerShape(2.dp)).background(Sc.blue))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(e.title, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Google Calendar", style = Type.meta)
        }
    }
}

/** Top-bar area filter for Board and Calendar (Tasks has area tabs instead). */
@Composable
private fun AreaFilterButton(area: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconBtn(R.drawable.ms_tune, "Filter by area", { open = true }, tint = if (area != null) Sc.accent else Sc.text2, active = open)
        Menu(open, { open = false }) {
            MenuRow(null, "All areas", { open = false; onPick(null) }, trailing = { if (area == null) Sym(R.drawable.ms_check, tint = Sc.accent, size = 18.dp) })
            TaskArea.entries.forEach { a ->
                MenuRow(null, a.label, { open = false; onPick(a.key) }, trailing = {
                    if (area == a.key) Sym(R.drawable.ms_check, tint = Sc.accent, size = 18.dp) else Dot(a.color)
                })
            }
        }
    }
}

/**
 * One task: round checkbox, title, and a meta line (area, due, priority,
 * subtasks, reminder, linked notes). [progress] is (done, total) subtasks.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskRow(
    task: TaskDto,
    progress: Pair<Int, Int>?,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    showDue: Boolean = true,
    showStatus: Boolean = false,
    inset: androidx.compose.ui.unit.Dp = 4.dp
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .heightIn(min = 48.dp)
            .padding(start = inset, end = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TaskCheck(task.isDone, onToggle)
        Column(Modifier.weight(1f).padding(start = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Text(
                task.title,
                style = Type.body.copy(
                    color = if (task.isDone) Sc.text3 else Sc.text,
                    textDecoration = if (task.isDone) TextDecoration.LineThrough else null
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            TaskMeta(task, progress, showDue, showStatus)
        }
    }
}

@Composable
private fun TaskMeta(task: TaskDto, progress: Pair<Int, Int>?, showDue: Boolean, showStatus: Boolean) {
    val area = TaskArea.of(task.area)
    val priority = TaskPriority.of(task.priority)
    val due = task.dueAt?.takeIf { showDue }
    val waitingDays = task.waitingSince?.takeIf { task.status == TaskStatus.Waiting.key }
        ?.let { ((System.currentTimeMillis() - it) / 86_400_000L).toInt() }
    val any = area != null || due != null || priority == TaskPriority.High || priority == TaskPriority.Urgent ||
        progress != null || task.remindAt != null || task.linkCount > 0 || waitingDays != null || showStatus
    if (!any) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(top = 2.dp)
    ) {
        if (area != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Dot(area.color)
                Text(area.label, style = Type.meta)
            }
        }
        if (showStatus) Text(TaskStatus.of(task.status).label, style = Type.meta)
        if (due != null) {
            val color = when {
                task.isOverdue() -> Sc.red
                task.isDueToday() && !task.isDone -> Sc.orange
                else -> Sc.text3
            }
            Text(shortDue(due), style = Type.meta.copy(color = color), maxLines = 1)
        }
        if (priority == TaskPriority.Urgent || priority == TaskPriority.High) {
            Sym(R.drawable.ms_flag_fill, tint = if (priority == TaskPriority.Urgent) Sc.red else Sc.orange, size = 14.dp, desc = priority.label)
        }
        if (progress != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Sym(R.drawable.ms_subdirectory_arrow_right, tint = Sc.text3, size = 14.dp)
                Text("${progress.first}/${progress.second}", style = Type.meta)
            }
        }
        task.remindAt?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Sym(R.drawable.ms_alarm, tint = Sc.text3, size = 14.dp, desc = "Reminder")
                if (it > System.currentTimeMillis()) Text(" " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)), style = Type.meta)
            }
        }
        if (task.linkCount > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Sym(R.drawable.ms_notes, tint = Sc.text3, size = 14.dp, desc = "Linked notes")
                Text(" ${task.linkCount}", style = Type.meta)
            }
        }
        if (waitingDays != null) Text("${waitingDays}d waiting", style = Type.meta)
    }
}

/** New task: title, area and an optional due day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(initialArea: TaskArea?, initialDue: Long?, onDismiss: () -> Unit, onAdd: (String, TaskArea?, Long?) -> Unit) {
    var title by remember { mutableStateOf("") }
    var area by remember { mutableStateOf(initialArea) }
    var due by remember { mutableStateOf(initialDue) }
    var pickDate by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    fun submit() {
        if (title.isBlank()) return
        onAdd(title, area, due)
        onDismiss()
    }
    Sheet(onDismiss) {
        InputBox(
            title,
            { title = it },
            "New task",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            focusRequester = focus,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() })
        )
        Spacer(Modifier.height(8.dp))
        Segments(TaskArea.entries, area, { it.label }, { area = if (area == it) null else it }, lead = { Dot(it.color) })
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { pickDate = true }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Sym(R.drawable.ms_event, tint = if (due != null) Sc.accent else Sc.text2, size = 18.dp)
                Text(due?.let { shortDue(it) } ?: "No date", style = Type.body.copy(color = Sc.text2), modifier = Modifier.padding(start = 8.dp))
            }
            if (due != null) IconBtn(R.drawable.ms_close, "Clear date", { due = null }, tint = Sc.text3)
            Spacer(Modifier.weight(1f))
            TextAction("Add", { submit() }, enabled = title.isNotBlank())
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    if (pickDate) {
        LocalDatePickerDialog(initial = due, onDismiss = { pickDate = false }, onPick = {
            pickDate = false
            due = it
        })
    }
}
