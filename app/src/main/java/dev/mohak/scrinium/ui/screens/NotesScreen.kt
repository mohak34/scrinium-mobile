package dev.mohak.scrinium.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mohak.scrinium.data.local.NoteEntity
import dev.mohak.scrinium.ui.MainViewModel
import dev.mohak.scrinium.ui.TreeItem
import dev.mohak.scrinium.ui.buildTree
import dev.mohak.scrinium.ui.folderPaths
import dev.mohak.scrinium.ui.name
import dev.mohak.scrinium.ui.noteTitle
import dev.mohak.scrinium.ui.relativeTime
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(vm: MainViewModel) {
    val notes by vm.notesFlow.collectAsStateWithLifecycle()
    val collapsed by vm.collapsedFolders.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val unsynced by vm.unsyncedCount.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    val items = remember(notes, collapsed) { buildTree(notes, collapsed) }
    val folders = remember(notes) { folderPaths(notes) }
    var folderMenu by remember { mutableStateOf<String?>(null) }
    var moveTarget by remember { mutableStateOf<NoteEntity?>(null) }
    var renameFolderTarget by remember { mutableStateOf<String?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scrinium") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    IconButton(onClick = { vm.openSearch() }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { vm.openSettings() }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.createNote() }) {
                Icon(Icons.Default.Add, contentDescription = "New note")
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = sync.syncing,
            onRefresh = { vm.syncNow(force = true) },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (items.isEmpty() && !sync.syncing) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(
                        items,
                        key = {
                            when (it) {
                                is TreeItem.Folder -> "d:${it.path}"
                                is TreeItem.Note -> "f:${it.note.path}"
                            }
                        }
                    ) { item ->
                        when (item) {
                            is TreeItem.Folder -> FolderRow(
                                folder = item,
                                expanded = item.path !in collapsed,
                                onToggle = { vm.toggleFolder(item.path) },
                                onMenu = { folderMenu = item.path }
                            )
                            is TreeItem.Note -> NoteRow(
                                note = item.note,
                                now = now,
                                depth = item.depth,
                                onClick = { vm.openNote(item.note.path) },
                                onMenu = { moveTarget = item.note }
                            )
                        }
                    }
                    item {
                        SyncFooter(sync.syncing, unsynced, sync.report?.errors ?: 0, sync.report?.failures.orEmpty())
                    }
                }
            }
        }
        sync.error?.let { error ->
            SyncErrorBanner(
                message = error,
                onDismiss = { vm.dismissSyncError() }
            )
        }
    }

    folderMenu?.let { folder ->
        val count = notes.count { it.path.startsWith("$folder/") }
        AlertDialog(
            onDismissRequest = { folderMenu = null },
            title = { Text(folder.substringAfterLast('/'), maxLines = 1) },
            text = {
                Column {
                    FolderMenuButton("New note here") {
                        folderMenu = null
                        vm.createNoteIn(folder)
                    }
                    FolderMenuButton("Rename folder") {
                        folderMenu = null
                        renameFolderTarget = folder
                    }
                    FolderMenuButton("Delete folder ($count notes)") {
                        folderMenu = null
                        deleteFolderTarget = folder
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { folderMenu = null }) { Text("Cancel") }
            }
        )
    }

    moveTarget?.let { note ->
        MoveNoteDialog(
            note = note,
            folders = folders,
            onDismiss = { moveTarget = null },
            onMove = { dest ->
                moveTarget = null
                vm.moveNote(note.path, dest)
            }
        )
    }

    renameFolderTarget?.let { folder ->
        var name by remember(folder) { mutableStateOf(folder.substringAfterLast('/')) }
        AlertDialog(
            onDismissRequest = { renameFolderTarget = null },
            title = { Text("Rename folder") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renameFolderTarget = null
                    vm.renameFolder(folder, name)
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameFolderTarget = null }) { Text("Cancel") }
            }
        )
    }

    deleteFolderTarget?.let { folder ->
        val count = notes.count { it.path.startsWith("$folder/") }
        AlertDialog(
            onDismissRequest = { deleteFolderTarget = null },
            title = { Text("Delete folder?") },
            text = { Text("$count note${if (count == 1) "" else "s"} move to the server trash on the next sync.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteFolderTarget = null
                    vm.deleteFolder(folder)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteFolderTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun FolderMenuButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun MoveNoteDialog(
    note: NoteEntity,
    folders: List<String>,
    onDismiss: () -> Unit,
    onMove: (String) -> Unit
) {
    val currentParent = note.path.substringBeforeLast('/', "")
    var selected by remember(note.path) { mutableStateOf(currentParent) }
    var newFolder by remember(note.path) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to folder") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                FolderOption(
                    label = "All notes",
                    sublabel = "Vault root",
                    selected = selected.isEmpty() && newFolder.isBlank(),
                    onClick = { selected = ""; newFolder = "" }
                )
                for (folder in folders) {
                    FolderOption(
                        label = folder,
                        sublabel = null,
                        selected = selected == folder && newFolder.isBlank(),
                        onClick = { selected = folder; newFolder = "" }
                    )
                }
                OutlinedTextField(
                    value = newFolder,
                    onValueChange = { newFolder = it },
                    label = { Text("Or new folder…") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onMove(newFolder.ifBlank { selected }) }) { Text("Move") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderOption(label: String, sublabel: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface
            )
            sublabel?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(folder: TreeItem.Folder, expanded: Boolean, onToggle: () -> Unit, onMenu: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onToggle, onLongClick = onMenu)
            .padding(start = (8 + folder.depth * 20).dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = folder.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        )
        Text(
            text = folder.noteCount.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (folder.hasUnsynced) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(note: NoteEntity, now: Long, depth: Int, onClick: () -> Unit, onMenu: () -> Unit) {
    val updatedAt = note.localModifiedAt ?: note.remoteUpdatedAt
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onMenu)
            .padding(start = (36 + depth * 20).dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = noteTitle(note.path, note.content),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = relativeTime(updatedAt, now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (note.localModifiedAt != null) {
            Box(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "No notes yet. Pull to sync, or tap + to create one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SyncFooter(syncing: Boolean, unsynced: Int, errors: Int, failures: List<String>) {
    val status = when {
        syncing -> "Syncing…"
        errors > 0 -> {
            val reason = failures.firstOrNull() ?: "pull to retry"
            "$errors change${if (errors == 1) "" else "s"} failed: ${reason.take(60)}"
        }
        unsynced > 0 -> "$unsynced unsynced change${if (unsynced == 1) "" else "s"} - will push on next sync"
        else -> ""
    }
    if (status.isBlank()) return
    Text(
        text = status,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun SyncErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Dismiss",
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}
