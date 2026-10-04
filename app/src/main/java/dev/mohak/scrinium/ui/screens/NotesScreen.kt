package dev.mohak.scrinium.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntOffset
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private const val HOVER_EXPAND_MS = 700L

// Non-composing drag scratch. The finger position updates every frame, so it
// stays out of Compose state — only dropTarget (highlight) and dragItem
// (overlay + refresh guard) are observable.
private class DragHolder {
    var fingerY = 0f
    var viewportH = 0f
    var hoverFolder: String? = null
    var hoverSince = 0L
    var openMenu: (() -> Unit)? = null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(vm: MainViewModel) {
    val notes by vm.notesFlow.collectAsStateWithLifecycle()
    val collapsed by vm.collapsedFolders.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val unsynced by vm.unsyncedCount.collectAsStateWithLifecycle()
    val pinned by vm.pinned.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val edgePx = with(density) { 96.dp.toPx() }
    val drag = remember { DragHolder() }
    var dragItem by remember { mutableStateOf<TreeItem?>(null) }
    var dropTarget by remember { mutableStateOf<String?>(null) }
    var dragLabel by remember { mutableStateOf("") }
    var dragFingerY by remember { mutableFloatStateOf(0f) }

    val items = remember(notes, collapsed, pinned) { buildTree(notes, collapsed, pinned) }
    val folders = remember(notes) { folderPaths(notes) }
    var folderMenu by remember { mutableStateOf<String?>(null) }
    var moveTarget by remember { mutableStateOf<NoteEntity?>(null) }
    var noteMenu by remember { mutableStateOf<NoteEntity?>(null) }
    var moveFolderTarget by remember { mutableStateOf<String?>(null) }
    var renameFolderTarget by remember { mutableStateOf<String?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<String?>(null) }

    val latestItems = rememberUpdatedState(items)
    val latestCollapsed = rememberUpdatedState(collapsed)

    // Folders a dragged folder may not be dropped into: itself + descendants.
    fun blockedPrefixes(dragged: TreeItem): Set<String> {
        val path = (dragged as? TreeItem.Folder)?.path ?: return emptySet()
        return folders.filterTo(mutableSetOf()) { it == path || it.startsWith("$path/") }
    }

    fun resolveTarget(key: Any?, relY: Float): String? {
        val item = dragItem ?: return null
        // Past the edges (mid auto-scroll overshoot) resolves to nothing.
        if (relY < 0 || relY > drag.viewportH) return null
        // Empty list space and the sync-status footer row count as the vault
        // root — the footer must not be a dead zone that swallows drops.
        if (key == null) return ""
        return when {
            key is String && key.startsWith("d:") -> {
                val path = key.removePrefix("d:")
                if (path in blockedPrefixes(item)) null else path
            }
            key is String && key.startsWith("f:") -> {
                val parent = key.removePrefix("f:").substringBeforeLast('/', "")
                val folder = item as? TreeItem.Folder
                if (folder != null && (parent == folder.path || parent.startsWith(folder.path + "/"))) {
                    null
                } else parent
            }
            else -> ""
        }
    }

    fun onDragMove(y: Float) {
        drag.fingerY = y
        dragFingerY = y
        val key = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { y >= it.offset && y < it.offset + it.size }?.key
        val target = resolveTarget(key, y)
        if (target != dropTarget) dropTarget = target
        val nowMs = System.currentTimeMillis()
        if (target != null && target.isNotEmpty() && target in latestCollapsed.value) {
            if (drag.hoverFolder != target) {
                drag.hoverFolder = target
                drag.hoverSince = nowMs
            } else if (nowMs - drag.hoverSince > HOVER_EXPAND_MS) {
                vm.toggleFolder(target)
                drag.hoverFolder = null
            }
        } else {
            drag.hoverFolder = null
        }
    }

    fun onDragEnd(moved: Boolean) {
        val item = dragItem
        val target = dropTarget
        val menu = drag.openMenu
        dragItem = null
        dropTarget = null
        drag.hoverFolder = null
        drag.openMenu = null
        if (!moved) {
            menu?.invoke()
            return
        }
        if (item == null || target == null) return
        when (item) {
            is TreeItem.Folder -> vm.moveFolder(item.path, target)
            is TreeItem.Note -> vm.moveNote(item.note.path, target)
        }
    }

    fun onDragStart(item: TreeItem, label: String, y: Float, openMenu: () -> Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        drag.openMenu = openMenu
        dragLabel = label
        drag.fingerY = y
        dragFingerY = y
        drag.hoverFolder = null
        dragItem = item
        dropTarget = null
    }

    val moveRef = rememberUpdatedState(::onDragMove)
    val endRef = rememberUpdatedState(::onDragEnd)

    // Edge auto-scroll while dragging. Reads the holder directly so the
    // per-frame loop never recomposes.
    LaunchedEffect(dragItem) {
        if (dragItem == null) return@LaunchedEffect
        while (true) {
            delay(16)
            val y = drag.fingerY
            when {
                y < edgePx -> listState.scrollBy(-14f)
                y > drag.viewportH - edgePx -> listState.scrollBy(14f)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scrinium") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(-12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { vm.openSearch() }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = { vm.openTasks() }) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Tasks")
                        }
                        IconButton(onClick = { vm.openTags() }) {
                            Text(
                                text = "#",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { vm.openSettings() }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
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
            onRefresh = { if (dragItem == null) vm.syncNow(force = true) },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (items.isEmpty() && !sync.syncing) {
                EmptyState()
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { drag.viewportH = it.size.height.toFloat() }
                            .pointerInput(Unit) {
                                // Passive gesture tracker on the list itself (never
                                // recycled, unlike rows). Observes without consuming
                                // until a row long-press promotes it to a drag.
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val id = down.id
                                    var moved = false
                                    var tracking = false
                                    try {
                                        do {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == id }
                                                ?: continue
                                            if (dragItem != null) tracking = true
                                            if (!change.pressed) break
                                            if (tracking) {
                                                change.consume()
                                                val y = change.position.y
                                                if (!moved && abs(y - down.position.y) > touchSlop) {
                                                    moved = true
                                                }
                                                moveRef.value(y)
                                            }
                                        } while (true)
                                    } catch (e: CancellationException) {
                                        dragItem = null
                                        dropTarget = null
                                        drag.hoverFolder = null
                                        drag.openMenu = null
                                        throw e
                                    }
                                    if (tracking) endRef.value(moved)
                                }
                            },
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
                                    highlighted = dropTarget == item.path,
                                    dimmed = (dragItem as? TreeItem.Folder)?.path == item.path,
                                    onTap = { vm.toggleFolder(item.path) },
                                    onLongPress = {
                                        onDragStart(
                                            item,
                                            "${item.name} (${item.noteCount})",
                                            listState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.key == "d:${item.path}" }
                                                ?.let { it.offset + it.size / 2f } ?: 0f
                                        ) { folderMenu = item.path }
                                    }
                                )
                                is TreeItem.Note -> {
                                    val parent = item.note.path.substringBeforeLast('/', "")
                                    NoteRow(
                                        note = item.note,
                                        pinned = item.pinned,
                                        now = now,
                                        depth = item.depth,
                                        highlighted = dropTarget != null && dropTarget == parent,
                                        dimmed = (dragItem as? TreeItem.Note)?.note?.path == item.note.path,
                                        onTap = { vm.openNote(item.note.path) },
                                        onLongPress = {
                                            onDragStart(
                                                item,
                                                noteTitle(item.note.path, item.note.content),
                                                listState.layoutInfo.visibleItemsInfo
                                                    .firstOrNull { it.key == "f:${item.note.path}" }
                                                    ?.let { it.offset + it.size / 2f } ?: 0f
                                            ) { noteMenu = item.note }
                                        }
                                    )
                                }
                            }
                        }
                        item {
                            SyncFooter(sync.syncing, unsynced, sync.report?.errors ?: 0, sync.report?.failures.orEmpty())
                        }
                    }
                    // Floating drag shadow with the live destination. No pointer
                    // handlers, so touches pass straight through to the list.
                    if (dragItem != null) {
                        val destLabel = when (dropTarget) {
                            null -> null
                            "" -> "All notes"
                            else -> dropTarget
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .offset {
                                    IntOffset(
                                        0,
                                        (dragFingerY - with(density) { 24.dp.toPx() }).roundToInt()
                                    )
                                },
                            tonalElevation = 6.dp,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(
                                text = if (destLabel == null) dragLabel else "$dragLabel → $destLabel",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
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
                    FolderMenuButton(if (folder in pinned) "Unpin folder" else "Pin folder") {
                        folderMenu = null
                        vm.togglePin(folder)
                    }
                    FolderMenuButton("Rename folder") {
                        folderMenu = null
                        renameFolderTarget = folder
                    }
                    FolderMenuButton("Move folder") {
                        folderMenu = null
                        moveFolderTarget = folder
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

    noteMenu?.let { note ->
        AlertDialog(
            onDismissRequest = { noteMenu = null },
            title = { Text(noteTitle(note.path, note.content), maxLines = 1) },
            text = {
                Column {
                    FolderMenuButton(if (note.path in pinned) "Unpin" else "Pin") {
                        noteMenu = null
                        vm.togglePin(note.path)
                    }
                    FolderMenuButton("Move to folder") {
                        noteMenu = null
                        moveTarget = note
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { noteMenu = null }) { Text("Cancel") }
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

    moveFolderTarget?.let { folder ->
        MoveFolderDialog(
            folder = folder,
            folders = folders.filter { it != folder && !it.startsWith("$folder/") },
            onDismiss = { moveFolderTarget = null },
            onMove = { dest ->
                moveFolderTarget = null
                vm.moveFolder(folder, dest)
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

@Composable
private fun MoveFolderDialog(
    folder: String,
    folders: List<String>,
    onDismiss: () -> Unit,
    onMove: (String) -> Unit
) {
    val currentParent = folder.substringBeforeLast('/', "")
    var selected by remember(folder) { mutableStateOf(currentParent) }
    var newFolder by remember(folder) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move folder", maxLines = 1) },
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
                for (option in folders) {
                    FolderOption(
                        label = option,
                        sublabel = null,
                        selected = selected == option && newFolder.isBlank(),
                        onClick = { selected = option; newFolder = "" }
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
private fun FolderRow(
    folder: TreeItem.Folder,
    expanded: Boolean,
    highlighted: Boolean,
    dimmed: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (highlighted) MaterialTheme.colorScheme.surfaceContainerHigh
                else Color.Transparent
            )
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(start = (8 + folder.depth * 20).dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(if (dimmed) 0.35f else 1f)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
                .alpha(if (dimmed) 0.35f else 1f)
        ) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            folder.pathHint?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (folder.pinned) PinMark()
        Text(
            text = folder.noteCount.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(if (dimmed) 0.35f else 1f)
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
private fun NoteRow(
    note: NoteEntity,
    pinned: Boolean,
    now: Long,
    depth: Int,
    highlighted: Boolean,
    dimmed: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val updatedAt = note.localModifiedAt ?: note.remoteUpdatedAt
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (highlighted) MaterialTheme.colorScheme.surfaceContainerHigh
                else Color.Transparent
            )
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(start = (36 + depth * 20).dp, end = 16.dp, top = 10.dp, bottom = 10.dp)
            .alpha(if (dimmed) 0.35f else 1f),
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
        if (pinned) PinMark()
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
private fun PinMark() {
    Icon(
        Icons.Default.Star,
        contentDescription = "Pinned",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp).size(14.dp)
    )
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
