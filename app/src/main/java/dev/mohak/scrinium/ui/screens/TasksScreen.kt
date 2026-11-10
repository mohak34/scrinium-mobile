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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.derivedStateOf
import dev.mohak.scrinium.data.remote.ProjectDto
import dev.mohak.scrinium.ui.DueGroup
import dev.mohak.scrinium.ui.DueKind
import dev.mohak.scrinium.ui.TaskCheck
import dev.mohak.scrinium.ui.addDays
import dev.mohak.scrinium.ui.LONG_WAIT_DAYS
import dev.mohak.scrinium.ui.STALE_DAYS
import dev.mohak.scrinium.ui.daysSince
import dev.mohak.scrinium.ui.dueGroups
import dev.mohak.scrinium.ui.projectStyle
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
import dev.mohak.scrinium.ui.TaskPriority
import dev.mohak.scrinium.ui.TaskStatus
import dev.mohak.scrinium.ui.TasksViewModel
import dev.mohak.scrinium.ui.TextAction
import dev.mohak.scrinium.ui.TopBar
import dev.mohak.scrinium.ui.Type
import dev.mohak.scrinium.ui.eventDays
import dev.mohak.scrinium.ui.isDone
import dev.mohak.scrinium.ui.isOverdue
import dev.mohak.scrinium.ui.isDueToday
import dev.mohak.scrinium.ui.onMainBoard
import dev.mohak.scrinium.ui.ProjectView
import dev.mohak.scrinium.ui.QUOTIDIAN
import dev.mohak.scrinium.ui.projectName
import dev.mohak.scrinium.ui.shows
import dev.mohak.scrinium.ui.shortDue
import dev.mohak.scrinium.ui.startOfDay
import dev.mohak.scrinium.ui.startOfToday
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.produceState
import dev.mohak.scrinium.ui.relativeTime
import dev.mohak.scrinium.ui.snoozeChoices
import dev.mohak.scrinium.ui.firedReminders
import java.text.DateFormat
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// The Tasks, Board and Calendar tabs. All three read TasksViewModel's list
// and share its area filter. Subtasks live inside their parent's detail
// screen, as on the web, so only top-level tasks show here. List and Board
// leave project backlogs out (onMainBoard); the calendar shows every date.

private fun topLevel(all: List<TaskDto>, area: String?) =
    all.filter { it.parentId == null && (area == null || it.area == area) }

// parentId -> (done, total) for the "1/3" subtask label.
private fun subtaskProgress(all: List<TaskDto>): Map<String, Pair<Int, Int>> =
    all.filter { it.parentId != null }.groupBy { it.parentId!! }
        .mapValues { (_, kids) -> kids.count { it.isDone } to kids.size }

// One section of the list or the board: a header, then its rows.
private class Section(
    val key: String,
    val label: String,
    val rows: List<TaskDto>,
    val date: String? = null,
    val count: String = "${rows.size}",
    val color: androidx.compose.ui.graphics.Color = Sc.text,
    val icon: Pair<Int, androidx.compose.ui.graphics.Color>? = null
)

private fun dueSection(g: DueGroup, today: Long): Section {
    val dayFmt = SimpleDateFormat("EEE d", Locale.getDefault())
    return when (g.kind) {
        DueKind.Overdue -> Section(g.key, "Overdue", g.rows, color = Sc.red)
        DueKind.Day -> Section(
            g.key,
            when (g.day) {
                today -> "Today"
                addDays(today, 1) -> "Tomorrow"
                else -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(g.day!!))
            },
            g.rows,
            date = dayFmt.format(Date(g.day!!))
        )
        DueKind.Later -> Section(g.key, "Later", g.rows)
        DueKind.NoDate -> Section(g.key, "No date", g.rows)
    }
}

@Composable
private fun SectionHeader(s: Section, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        s.icon?.let { (icon, tint) -> Sym(icon, tint = tint, size = 18.dp, modifier = Modifier.padding(end = 6.dp)) }
        Text(s.label, style = Type.group.copy(color = s.color))
        s.date?.let { Text(it, style = Type.meta, modifier = Modifier.padding(start = 8.dp)) }
        Spacer(Modifier.weight(1f))
        Text(s.count, style = Type.mono.copy(color = if (s.count.endsWith("over limit")) Sc.yellow else Sc.text3))
    }
}

/**
 * The list by due date, like the web's: Overdue, each day of the coming
 * week, Later, No date, then Done folded away. It opens on Today; the red
 * button in the bar jumps up to Overdue. The week strip is a map of the
 * list: it underlines the section at the top and jumps to a tapped day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(tasksVm: TasksViewModel) {
    val all by tasksVm.tasks.collectAsStateWithLifecycle()
    val loading by tasksVm.loading.collectAsStateWithLifecycle()
    val error by tasksVm.error.collectAsStateWithLifecycle()
    val area by tasksVm.areaFilter.collectAsStateWithLifecycle()
    val projects by tasksVm.projects.collectAsStateWithLifecycle()
    val view by tasksVm.boardView.collectAsStateWithLifecycle()
    val askNotify = rememberNotificationAsk()
    LaunchedEffect(Unit) {
        askNotify()
        tasksVm.refresh()
    }
    var showAdd by remember { mutableStateOf(false) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    val quickAdd by tasksVm.quickAdd.collectAsStateWithLifecycle()
    LaunchedEffect(quickAdd) {
        if (quickAdd) {
            showAdd = true
            tasksVm.quickAddShown()
        }
    }

    // Ticks so a reminder joins the bell when it goes off with the list open.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val today = startOfDay(now)
    val listed = topLevel(all, area).filter { view.shows(it) }
    val groups = dueGroups(listed.filter { !it.isDone }, now)
    val done = listed.filter { it.isDone }.sortedByDescending { it.updatedAt }
    val sections = groups.filter { it.rows.isNotEmpty() }.map { dueSection(it, today) } +
        listOfNotNull(done.takeIf { it.isNotEmpty() }?.let { Section("done", "Done", it, color = Sc.text3) })
    // LazyColumn index of each section's header.
    val headerAt = buildMap {
        var i = 0
        for (s in sections) {
            put(s.key, i)
            i += 1 + if (s.key != "done" || showDone) s.rows.size else 0
        }
    }
    val progress = subtaskProgress(all)
    val overdue = groups.first().rows.size
    val fired = firedReminders(all, now)
    var showReminders by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atTop by remember(sections, headerAt) {
        derivedStateOf { sections.lastOrNull { headerAt.getValue(it.key) <= listState.firstVisibleItemIndex }?.key }
    }
    fun jump(key: String) {
        headerAt[key]?.let { scope.launch { listState.animateScrollToItem(it) } }
    }
    var positioned by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(sections.isNotEmpty()) {
        if (positioned || sections.isEmpty()) return@LaunchedEffect
        positioned = true
        if (sections[0].key == "overdue") sections.getOrNull(1)?.let { listState.scrollToItem(headerAt.getValue(it.key)) }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(scopeTitle(view, "Tasks")) {
            if (fired.isNotEmpty()) {
                Row(
                    Modifier.clickable { showReminders = true }.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Sym(R.drawable.ms_notifications, tint = Sc.yellow, size = 20.dp, desc = "Reminders")
                    Text("${fired.size}", style = Type.meta.copy(color = Sc.yellow), modifier = Modifier.padding(start = 2.dp))
                }
            }
            if (overdue > 0) {
                val on = atTop == "overdue"
                Column(
                    Modifier.clickable { jump("overdue") }.padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Sym(if (on) R.drawable.ms_event_busy_fill else R.drawable.ms_event_busy, tint = Sc.red, size = 20.dp, desc = "Overdue")
                        Text("$overdue", style = Type.meta.copy(color = Sc.red), modifier = Modifier.padding(start = 2.dp))
                    }
                    Box(Modifier.padding(top = 2.dp).width(24.dp).height(2.dp).background(if (on) Sc.red else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
            FilterButton(projects, view, { tasksVm.setProjectView(it) }, area) { tasksVm.setAreaFilter(it) }
        }
        WeekStrip(groups, today, atTop, projects, ::jump)
        error?.let { ErrorStrip(it) { tasksVm.dismissError() } }
        Box(Modifier.weight(1f)) {
            PullToRefreshBox(isRefreshing = loading, onRefresh = { tasksVm.refresh() }, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
                    if (sections.isEmpty() && !loading) item { EmptyState(R.drawable.ms_task_alt, "No tasks", "Tap + to add one.") }
                    sections.forEach { s ->
                        val isDone = s.key == "done"
                        item(key = "h-${s.key}") { SectionHeader(s, if (isDone) ({ showDone = !showDone }) else null) }
                        if (!isDone || showDone) {
                            items(s.rows, key = { it.id }) { t ->
                                TaskRow(
                                    t,
                                    progress[t.id],
                                    onToggle = { tasksVm.toggleDone(t) },
                                    onOpen = { tasksVm.openTask(t.id) },
                                    due = if (s.date != null) DueShow.Time else DueShow.Full,
                                    projects = projects.takeIf { view == ProjectView.All }
                                )
                            }
                        }
                    }
                }
            }
            Fab(R.drawable.ms_add, "New task", { showAdd = true }, Modifier.align(Alignment.BottomEnd))
        }
    }

    if (showReminders) {
        ReminderSheet(fired, now, tasksVm) { showReminders = false }
    }
    if (showAdd) {
        QuickAddSheet(TaskArea.of(area), null, onDismiss = { showAdd = false }) { title, a, due, done ->
            tasksVm.create(title, area = a, dueAt = due, project = (view as? ProjectView.Project)?.path, onDone = done)
        }
    }
}

private fun scopeTitle(view: ProjectView, all: String) = when (view) {
    ProjectView.All -> all
    is ProjectView.Project -> view.path?.let(::projectName) ?: QUOTIDIAN
}

// Seven day slots from today, then Later (everything after the week and
// undated). Today's date is teal; the underline marks the section at the
// top of the list. Empty days are dim and can't be tapped.
@Composable
private fun WeekStrip(groups: List<DueGroup>, today: Long, atTop: String?, projects: List<ProjectDto>, onPick: (String) -> Unit) {
    val days = groups.filter { it.kind == DueKind.Day }
    val laterRows = groups.filter { it.kind == DueKind.Later || it.kind == DueKind.NoDate }
    val weekday = SimpleDateFormat("EEE", Locale.getDefault())
    val dayNum = SimpleDateFormat("d", Locale.getDefault())
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 6.dp)) {
            days.forEach { g ->
                val isToday = g.day == today
                val empty = g.rows.isEmpty()
                StripSlot(Modifier.weight(1f), atTop == g.key, if (empty) null else ({ onPick(g.key) })) {
                    Text(weekday.format(Date(g.day!!)), style = Type.meta.copy(color = if (isToday) Sc.accent else Sc.text3))
                    Text(
                        dayNum.format(Date(g.day)),
                        style = Type.body.copy(color = if (isToday) Sc.accent else if (empty) Sc.text4 else Sc.text, fontWeight = FontWeight.Medium)
                    )
                    Row(Modifier.height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        g.rows.take(3).forEach { Dot(projectStyle(it.project, projects).color, 4.dp) }
                    }
                }
            }
            val n = laterRows.sumOf { it.rows.size }
            val target = laterRows.firstOrNull { it.rows.isNotEmpty() }?.key
            StripSlot(Modifier.weight(0.8f), atTop != null && atTop in laterRows.map { it.key }, target?.let { { onPick(it) } }) {
                Sym(R.drawable.ms_calendar_clock, tint = Sc.text3, size = 15.dp, desc = "Later")
                Text("$n", style = Type.body.copy(color = if (n == 0) Sc.text4 else Sc.text2))
                Spacer(Modifier.height(6.dp))
            }
        }
        Divider()
    }
}

@Composable
private fun StripSlot(modifier: Modifier, on: Boolean, onClick: (() -> Unit)?, content: @Composable () -> Unit) {
    Column(
        modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(Modifier.padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) { content() }
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) Sc.text else androidx.compose.ui.graphics.Color.Transparent))
    }
}

// Reminders that went off: finish, snooze or clear each. Closing the sheet
// loses nothing; the bell stays while any is left.
@Composable
private fun ReminderSheet(fired: List<TaskDto>, now: Long, tasksVm: TasksViewModel, onDismiss: () -> Unit) {
    var open by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(fired.isEmpty()) { if (fired.isEmpty()) onDismiss() }
    Sheet(onDismiss) {
        SheetTitle("Reminders")
        Divider()
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            fired.forEach { t ->
                item(key = t.id) {
                    Row(
                        Modifier.fillMaxWidth().clickable { open = if (open == t.id) null else t.id }.padding(start = 4.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TaskCheck(false) { tasksVm.toggleDone(t) }
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(t.title, style = Type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(relativeTime(t.remindAt ?: now, now), style = Type.meta)
                        }
                        Sym(if (open == t.id) R.drawable.ms_expand_less else R.drawable.ms_expand_more, tint = Sc.text3, size = 18.dp)
                    }
                }
                if (open == t.id) {
                    item(key = "${t.id}-choices") {
                        Column(Modifier.padding(start = 32.dp)) {
                            snoozeChoices().forEach { c ->
                                MenuRow(R.drawable.ms_schedule, c.label, { tasksVm.setReminder(t.id, c.at) })
                            }
                            MenuRow(R.drawable.ms_close, "Clear reminder", { tasksVm.setReminder(t.id, null) })
                            MenuRow(R.drawable.ms_open_in_new, "Open task", {
                                onDismiss()
                                tasksVm.openTask(t.id)
                            })
                        }
                    }
                }
            }
        }
    }
}

// Board: the status columns stacked, Doing first, Done folded away.
// Long-press a task to move it to another column.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(tasksVm: TasksViewModel) {
    val all by tasksVm.tasks.collectAsStateWithLifecycle()
    val loading by tasksVm.loading.collectAsStateWithLifecycle()
    val error by tasksVm.error.collectAsStateWithLifecycle()
    val area by tasksVm.areaFilter.collectAsStateWithLifecycle()
    val projects by tasksVm.projects.collectAsStateWithLifecycle()
    val view by tasksVm.boardView.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { tasksVm.refresh() }
    var showAdd by remember { mutableStateOf(false) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    var moving by remember { mutableStateOf<TaskDto?>(null) }

    val tasks = topLevel(all, area).filter { view.shows(it) }
    val progress = subtaskProgress(all)
    val order = listOf(TaskStatus.Doing, TaskStatus.Todo, TaskStatus.Waiting, TaskStatus.Inbox, TaskStatus.Done)
    val sections = order.mapNotNull { s ->
        val rows = tasks.filter { it.status == s.key }.let { rows ->
            if (s == TaskStatus.Done) rows.sortedByDescending { it.updatedAt }
            else rows.sortedWith(compareBy({ it.position }, { it.createdAt }))
        }
        when {
            s == TaskStatus.Doing -> Section(
                s.key, s.label, rows,
                count = if (rows.size > DOING_LIMIT) "${rows.size - DOING_LIMIT} over limit" else "limit $DOING_LIMIT",
                icon = statusIcon(s)
            )
            rows.isEmpty() -> null
            else -> Section(s.key, s.label, rows, color = if (s == TaskStatus.Done) Sc.text3 else Sc.text, icon = statusIcon(s))
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(scopeTitle(view, "Board")) {
            FilterButton(projects, view, { tasksVm.setProjectView(it) }, area) { tasksVm.setAreaFilter(it) }
        }
        error?.let { ErrorStrip(it) { tasksVm.dismissError() } }
        Box(Modifier.weight(1f)) {
            PullToRefreshBox(isRefreshing = loading, onRefresh = { tasksVm.refresh() }, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    sections.forEach { s ->
                        val isDone = s.key == TaskStatus.Done.key
                        item(key = "h-${s.key}") { SectionHeader(s, if (isDone) ({ showDone = !showDone }) else null) }
                        if (!isDone || showDone) {
                            items(s.rows, key = { it.id }) { t ->
                                TaskRow(
                                    t,
                                    progress[t.id],
                                    onToggle = { tasksVm.toggleDone(t) },
                                    onOpen = { tasksVm.openTask(t.id) },
                                    onLongPress = { moving = t },
                                    projects = projects.takeIf { view == ProjectView.All }
                                )
                            }
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
            TaskStatus.entries.filter { it.key != t.status }.forEach { s ->
                val (icon, tint) = statusIcon(s)
                MenuRow(icon, s.label, {
                    moving = null
                    tasksVm.setStatus(t.id, s)
                }, tint = tint)
            }
        }
    }
    if (showAdd) {
        QuickAddSheet(TaskArea.of(area), null, onDismiss = { showAdd = false }) { title, a, due, done ->
            tasksVm.create(title, area = a, dueAt = due, project = (view as? ProjectView.Project)?.path, onDone = done)
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
    val projects by tasksVm.projects.collectAsStateWithLifecycle()
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
            FilterButton(null, ProjectView.All, {}, area) { tasksVm.setAreaFilter(it) }
            IconBtn(R.drawable.ms_today, "Today", {
                month = monthStart(today)
                selectedDay = today
            })
            IconBtn(R.drawable.ms_chevron_left, "Previous month", { shiftMonth(-1) })
            IconBtn(R.drawable.ms_chevron_right, "Next month", { shiftMonth(1) })
        }
        error?.let { ErrorStrip(it) { tasksVm.dismissError() } }
        Box(Modifier.weight(1f)) {
            val dayTasks = byDay[selectedDay].orEmpty().sortedBy { it.dueAt }
            val dayEvents = eventsByDay[selectedDay].orEmpty().sortedWith(compareBy({ !it.allDay }, { it.start }))
            // The grid scrolls with the agenda: a six-week month in landscape
            // would otherwise leave the agenda no height.
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                item(key = "grid") {
                    MonthGrid(month, selectedDay, today, byDay, eventsByDay, projects) { selectedDay = it }
                    Divider()
                }
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
                            TaskRow(t, progress[t.id], onToggle = { tasksVm.toggleDone(t) }, onOpen = { tasksVm.openTask(t.id) }, due = DueShow.None, projects = projects, inset = 0.dp)
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
        QuickAddSheet(TaskArea.of(area), selectedDay, onDismiss = { showAdd = false }) { title, a, due, done ->
            tasksVm.create(title, area = a, dueAt = due, onDone = done)
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
    projects: List<ProjectDto>,
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
                            DayCell(dayNum, byDay[dayMs].orEmpty().filter { !it.isDone }.take(3).map { projectStyle(it.project, projects).color }, eventsByDay[dayMs].orEmpty().size, dayMs == today, dayMs == selectedDay) { onSelect(dayMs) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: Int, dots: List<androidx.compose.ui.graphics.Color>, events: Int, isToday: Boolean, selected: Boolean, onClick: () -> Unit) {
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
            dots.forEach { Dot(it, 4.dp) }
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

/**
 * The top-bar filter: which project (All, Quotidian, one project, each in
 * its icon and color; left out when [projects] is null) and which area.
 */
@Composable
private fun FilterButton(
    projects: List<ProjectDto>?,
    view: ProjectView,
    onView: (ProjectView) -> Unit,
    area: String?,
    onArea: (String?) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val check: @Composable () -> Unit = { Sym(R.drawable.ms_check, tint = Sc.accent, size = 18.dp) }
    Box {
        IconBtn(R.drawable.ms_filter_list, "Filter", { open = true }, tint = if (view != ProjectView.All || area != null) Sc.accent else Sc.text2, active = open)
        Menu(open, { open = false }) {
            if (!projects.isNullOrEmpty()) {
                MenuRow(R.drawable.ms_inbox, "All projects", { open = false; onView(ProjectView.All) }, trailing = { if (view == ProjectView.All) check() })
                val paths = projects.map { it.path }
                (listOf<String?>(null) + paths).forEach { path ->
                    val style = projectStyle(path, projects)
                    val v = ProjectView.Project(path)
                    MenuRow(style.icon, path?.let { projectLabel(it, paths) } ?: QUOTIDIAN, { open = false; onView(v) }, tint = style.color, trailing = {
                        if (view == v) check()
                    })
                }
                Divider()
            }
            MenuRow(null, "All areas", { open = false; onArea(null) }, trailing = { if (area == null) check() })
            TaskArea.entries.forEach { a ->
                MenuRow(null, a.label, { open = false; onArea(a.key) }, trailing = { if (area == a.key) check() else Dot(a.color) })
            }
        }
    }
}

// The folder name, or the whole path when two projects share a name.
fun projectLabel(path: String, all: List<String>): String {
    val name = projectName(path)
    return if (all.count { projectName(it) == name } > 1) path else name
}

/** A status's icon and color, as the web's list draws them. */
fun statusIcon(s: TaskStatus): Pair<Int, androidx.compose.ui.graphics.Color> = when (s) {
    TaskStatus.Doing -> R.drawable.ms_clock_loader_40 to Sc.accent
    TaskStatus.Waiting -> R.drawable.ms_hourglass_top to Sc.yellow
    TaskStatus.Done -> R.drawable.ms_check_circle_fill to Sc.text4
    TaskStatus.Inbox -> R.drawable.ms_inbox to Sc.text2
    TaskStatus.Todo -> R.drawable.ms_radio_button_unchecked to Sc.text2
}

/** How a row shows its due date: in full, only a set time (under a day header), or not at all. */
enum class DueShow { Full, Time, None }

/**
 * One task: status icon (tap to finish or reopen), title, and the web's
 * meta line. [progress] is (done, total) subtasks; [projects] non-null
 * shows the task's project in its color.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskRow(
    task: TaskDto,
    progress: Pair<Int, Int>?,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    due: DueShow = DueShow.Full,
    projects: List<ProjectDto>? = null,
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
        val (icon, tint) = statusIcon(TaskStatus.of(task.status))
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
            Sym(icon, tint = tint, size = 20.dp, desc = if (task.isDone) "Reopen" else "Finish")
        }
        Column(Modifier.weight(1f).padding(start = 2.dp, top = 6.dp, bottom = 6.dp)) {
            Text(
                task.title,
                style = Type.body.copy(
                    color = if (task.isDone) Sc.text3 else Sc.text,
                    textDecoration = if (task.isDone) TextDecoration.LineThrough else null
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            TaskMeta(task, progress, due, projects)
        }
    }
}

@Composable
private fun MetaItem(icon: Int, text: String?, color: androidx.compose.ui.graphics.Color = Sc.text3, desc: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Sym(icon, tint = color, size = 15.dp, desc = desc)
        if (text != null) Text(text, style = Type.meta.copy(color = color), maxLines = 1, modifier = Modifier.padding(start = 3.dp))
    }
}

private fun days(n: Int) = if (n == 1) "1 day" else "$n days"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskMeta(task: TaskDto, progress: Pair<Int, Int>?, due: DueShow, projects: List<ProjectDto>?) {
    val priority = TaskPriority.of(task.priority)
    val dueAt = task.dueAt?.takeIf {
        when (due) {
            DueShow.Full -> true
            DueShow.Time -> minutesOfDay(it) != 0
            DueShow.None -> false
        }
    }
    val waited = task.waitingSince?.takeIf { task.status == TaskStatus.Waiting.key }?.let { daysSince(it) }
    val waiting = task.status == TaskStatus.Waiting.key && (task.waitingOn != null || waited != null)
    val idle = if (task.status == TaskStatus.Doing.key) daysSince(task.updatedAt) else 0
    val project = task.project?.takeIf { projects != null }
    val any = dueAt != null || project != null || priority != TaskPriority.None || waiting || idle >= STALE_DAYS ||
        progress != null || task.remindAt != null || task.linkCount > 0
    if (!any) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(top = 2.dp)
    ) {
        if (dueAt != null) {
            val today = task.isDueToday() && !task.isDone
            val over = task.isOverdue()
            val color = if (over) Sc.red else if (today) Sc.accent else Sc.text2
            when {
                due == DueShow.Time -> MetaItem(R.drawable.ms_schedule, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(dueAt)), Sc.text2)
                over -> MetaItem(R.drawable.ms_event_busy, shortDue(dueAt), color)
                today -> MetaItem(R.drawable.ms_today, shortDue(dueAt), color)
                else -> MetaItem(R.drawable.ms_event, shortDue(dueAt), color)
            }
        }
        if (project != null) {
            val style = projectStyle(project, projects!!)
            MetaItem(style.icon, projectName(project), style.color, desc = "Project")
        }
        if (priority != TaskPriority.None) {
            val color = when (priority) {
                TaskPriority.Urgent -> Sc.red
                TaskPriority.High -> Sc.orange
                TaskPriority.Medium -> Sc.yellow
                else -> Sc.blue
            }
            MetaItem(R.drawable.ms_flag_fill, priority.key, color, desc = "Priority")
        }
        if (waiting) {
            MetaItem(
                R.drawable.ms_person,
                (task.waitingOn ?: "Someone") + (waited?.let { ", " + days(it) } ?: ""),
                if (waited != null && waited >= LONG_WAIT_DAYS) Sc.orange else Sc.text3,
                desc = "Waiting on"
            )
        }
        if (idle >= STALE_DAYS) MetaItem(R.drawable.ms_schedule, days(idle), Sc.orange, desc = "Untouched")
        if (progress != null) MetaItem(R.drawable.ms_subdirectory_arrow_right, "${progress.first}/${progress.second}", desc = "Subtasks")
        if (task.remindAt != null) MetaItem(R.drawable.ms_notifications, null, desc = "Reminder")
        if (task.linkCount > 0) MetaItem(R.drawable.ms_description, "${task.linkCount}", desc = "Linked notes")
    }
}

/**
 * New task: title, area and an optional due day. The sheet closes once the
 * server made the task ([onAdd]'s callback gets true); on a failure it stays
 * open with the text for a retry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(initialArea: TaskArea?, initialDue: Long?, onDismiss: () -> Unit, onAdd: (String, TaskArea?, Long?, (Boolean) -> Unit) -> Unit) {
    var title by remember { mutableStateOf("") }
    var area by remember { mutableStateOf(initialArea) }
    var due by remember { mutableStateOf(initialDue) }
    var pickDate by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    fun submit() {
        if (title.isBlank() || adding) return
        adding = true
        onAdd(title, area, due) { ok ->
            adding = false
            if (ok) onDismiss()
        }
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
