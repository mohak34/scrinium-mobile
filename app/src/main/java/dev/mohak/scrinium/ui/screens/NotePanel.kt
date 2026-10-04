package dev.mohak.scrinium.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mohak.scrinium.R
import dev.mohak.scrinium.ui.Backlink
import dev.mohak.scrinium.ui.ErrorStrip
import dev.mohak.scrinium.ui.GroupHeader
import dev.mohak.scrinium.ui.InputBox
import dev.mohak.scrinium.ui.MainViewModel
import dev.mohak.scrinium.ui.Sc
import dev.mohak.scrinium.ui.Sheet
import dev.mohak.scrinium.ui.TaskStatus
import dev.mohak.scrinium.ui.TasksViewModel
import dev.mohak.scrinium.ui.TextAction
import dev.mohak.scrinium.ui.TextTabs
import dev.mohak.scrinium.ui.Type
import dev.mohak.scrinium.ui.frontmatterProperties
import dev.mohak.scrinium.ui.noteTitle
import dev.mohak.scrinium.ui.parseOutline
import dev.mohak.scrinium.ui.wordCount
import java.text.DateFormat
import java.util.Date

/**
 * The web's right panel for the open note, as a bottom sheet with four
 * tabs: Outline, Links (backlinks + unlinked mentions), Tasks linked to the
 * note, and Info (frontmatter properties + file facts). [onJump] takes the
 * 0-based line of a tapped heading.
 */
@Composable
fun NotePanel(
    vm: MainViewModel,
    tasksVm: TasksViewModel,
    path: String,
    content: String,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val noteTasks by tasksVm.noteTasks.collectAsStateWithLifecycle()
    val taskError by tasksVm.error.collectAsStateWithLifecycle()
    val backlinks by vm.backlinks.collectAsStateWithLifecycle()
    val notes by vm.notesFlow.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var newTask by remember(path) { mutableStateOf("") }
    val outline = remember(content) { parseOutline(content) }
    val properties = remember(content) { frontmatterProperties(content) }
    val words = remember(content) { wordCount(content) }
    val stored = notes.firstOrNull { it.path == path }
    val linkCount = backlinks?.let { it.linked.size + it.unlinked.size }
    // Fixed height so switching tabs doesn't make the sheet jump.
    val height = (LocalConfiguration.current.screenHeightDp * 0.62f).dp

    fun titleOf(p: String): String =
        notes.firstOrNull { it.path == p }?.let { noteTitle(it.path, it.content) }
            ?: p.substringAfterLast('/').removeSuffix(".md")

    Sheet(onDismiss) {
        TextTabs(
            listOf("Outline", "Links" + (linkCount?.let { " $it" } ?: ""), "Tasks " + noteTasks.size, "Info"),
            tab
        ) { tab = it }
        LazyColumn(Modifier.fillMaxWidth().height(height), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 24.dp)) {
            when (tab) {
                0 -> {
                    if (outline.isEmpty()) item { Hint("No headings in this note") }
                    items(outline, key = { "o-${it.line}" }) { h ->
                        Text(
                            h.text,
                            style = Type.body.copy(color = if (h.level <= 2) Sc.text else Sc.text2),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDismiss()
                                    onJump(h.line)
                                }
                                .padding(start = (16 + (h.level - 1) * 14).dp, end = 16.dp, top = 9.dp, bottom = 9.dp)
                        )
                    }
                }
                1 -> {
                    val bl = backlinks
                    if (bl == null) {
                        item { Hint("Scanning notes") }
                    } else {
                        item { GroupHeader("Backlinks", "${bl.linked.size}") }
                        if (bl.linked.isEmpty()) item { Hint("No notes link here") }
                        items(bl.linked, key = { "l-${it.path}" }) { b ->
                            BacklinkRow(b, titleOf(b.path), onOpen = {
                                onDismiss()
                                vm.openNote(b.path)
                            })
                        }
                        if (bl.unlinked.isNotEmpty()) {
                            item { GroupHeader("Unlinked mentions", "${bl.unlinked.size}") }
                            items(bl.unlinked, key = { "u-${it.path}" }) { b ->
                                BacklinkRow(
                                    b,
                                    titleOf(b.path),
                                    onOpen = {
                                        onDismiss()
                                        vm.openNote(b.path)
                                    },
                                    onLink = { vm.linkMention(b.path) }
                                )
                            }
                        }
                    }
                }
                2 -> {
                    taskError?.let { e -> item { ErrorStrip(e) { tasksVm.dismissError() } } }
                    items(noteTasks, key = { "t-${it.id}" }) { t ->
                        TaskRow(t, null, onToggle = { tasksVm.toggleDone(t) }, onOpen = {
                            onDismiss()
                            tasksVm.openTask(t.id)
                        }, showStatus = true)
                    }
                    item {
                        InputBox(
                            newTask,
                            { newTask = it },
                            "Add task for this note",
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            icon = R.drawable.ms_add,
                            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                val title = newTask
                                // Kept on failure for a retry; cleared unless more was typed.
                                tasksVm.create(title, status = TaskStatus.Inbox, linkPath = path, onCreated = {
                                    if (newTask == title) newTask = ""
                                })
                            })
                        )
                    }
                }
                else -> {
                    if (properties.isNotEmpty()) {
                        item { GroupHeader("Properties") }
                        items(properties, key = { "p-${it.key}" }) { p -> InfoRow(p.key, p.value) }
                    }
                    item { GroupHeader("File") }
                    item { InfoRow("path", path, mono = true) }
                    item { InfoRow("words", "%,d".format(words), mono = true) }
                    item { InfoRow("characters", "%,d".format(content.length), mono = true) }
                    stored?.let { n ->
                        val modified = n.localModifiedAt ?: n.remoteUpdatedAt
                        item {
                            InfoRow(
                                "edited",
                                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(modified)) +
                                    if (n.localModifiedAt != null) "  ·  not synced" else "",
                                mono = true
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = Type.meta, modifier = Modifier.padding(16.dp))
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 34.dp).padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, style = Type.body.copy(color = Sc.text3), modifier = Modifier.width(96.dp))
        Text(value, style = if (mono) Type.mono.copy(color = Sc.text, fontSize = Type.body.fontSize * 0.9f) else Type.body, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun BacklinkRow(b: Backlink, title: String, onOpen: () -> Unit, onLink: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (b.excerpt.isNotBlank()) {
                Text(b.excerpt, style = Type.meta.copy(color = Sc.text2), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onLink != null) TextAction("Link", onLink)
    }
}
