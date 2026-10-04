package dev.mohak.scrinium.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import dev.mohak.scrinium.ui.Backlink
import dev.mohak.scrinium.ui.MainViewModel
import dev.mohak.scrinium.ui.TaskStatus
import dev.mohak.scrinium.ui.TasksViewModel
import dev.mohak.scrinium.ui.frontmatterProperties
import dev.mohak.scrinium.ui.noteTitle
import dev.mohak.scrinium.ui.parseOutline
import dev.mohak.scrinium.ui.wordCount
import java.text.DateFormat
import java.util.Date

/**
 * The open note's side panel from the web, as a bottom sheet: outline,
 * frontmatter properties, tasks linked to the note, notes linking here,
 * plain-text mentions that can be turned into links, and file info.
 * [onJump] takes the 0-based line of a tapped heading.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val backlinks by vm.backlinks.collectAsStateWithLifecycle()
    val notes by vm.notesFlow.collectAsStateWithLifecycle()
    var newTask by remember(path) { mutableStateOf("") }
    val outline = remember(content) { parseOutline(content) }
    val properties = remember(content) { frontmatterProperties(content) }
    val words = remember(content) { wordCount(content) }
    val stored = notes.firstOrNull { it.path == path }

    fun titleOf(p: String): String =
        notes.firstOrNull { it.path == p }?.let { noteTitle(it.path, it.content) }
            ?: p.substringAfterLast('/').removeSuffix(".md")

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            if (outline.isNotEmpty()) {
                item { SectionHeader("Outline") }
                items(outline, key = { "o-${it.line}" }) { h ->
                    Text(
                        h.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                onJump(h.line)
                            }
                            .padding(start = (16 + (h.level - 1) * 14).dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
                    )
                }
            }
            if (properties.isNotEmpty()) {
                item { SectionHeader("Properties") }
                items(properties, key = { "p-${it.key}" }) { p -> InfoRow(p.key, p.value) }
            }
            item { SectionHeader("Tasks") }
            items(noteTasks, key = { "t-${it.id}" }) { t ->
                TaskRow(
                    task = t,
                    subtasks = 0,
                    onToggle = { tasksVm.toggleDone(t) },
                    onOpen = {
                        onDismiss()
                        tasksVm.openTask(t.id)
                    }
                )
            }
            item {
                OutlinedTextField(
                    value = newTask,
                    onValueChange = { newTask = it },
                    singleLine = true,
                    placeholder = { Text("Add task for this note") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        tasksVm.create(newTask, status = TaskStatus.Inbox, linkPath = path)
                        newTask = ""
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            val bl = backlinks
            item { SectionHeader("Linked mentions${bl?.let { "  ${it.linked.size}" } ?: ""}") }
            if (bl == null) {
                item { EmptyHint("Scanning notes") }
            } else {
                if (bl.linked.isEmpty()) item { EmptyHint("No notes link here") }
                items(bl.linked, key = { "l-${it.path}" }) { b ->
                    BacklinkRow(b, titleOf(b.path), onOpen = {
                        onDismiss()
                        vm.openNote(b.path)
                    })
                }
                if (bl.unlinked.isNotEmpty()) {
                    item { SectionHeader("Unlinked mentions  ${bl.unlinked.size}") }
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
            item { SectionHeader("Info") }
            item { InfoRow("Words", "%,d".format(words)) }
            item { InfoRow("Characters", "%,d".format(content.length)) }
            stored?.let { n ->
                val modified = n.localModifiedAt ?: n.remoteUpdatedAt
                item {
                    InfoRow("Modified", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(modified)))
                }
            }
            item { InfoRow("Path", path) }
            item { Text("", modifier = Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f)
        )
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.65f))
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
        Column(modifier = Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (b.excerpt.isNotBlank()) {
                Text(
                    b.excerpt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (onLink != null) {
            TextButton(onClick = onLink) { Text("Link") }
        }
    }
}
