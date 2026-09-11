package dev.mohak.scrinium.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val HOVER_EXPAND_MS = 700L

// Non-composing drag scratch state. Finger position updates every frame while
// dragging, so it stays out of Compose state — only dropTarget (highlight)
// and dragItem (overlay) are observable.
private class DragHolder {
    var moved = false
    var containerTop = 0f
    var viewportTop = 0f
    var viewportHeight = 0f
    var fingerWindowY = 0f
    var hoverFolder: String? = null
    var hoverSince = 0L
}

// Gesture callbacks handed to rows. Each wraps a rememberUpdatedState so the
// pointerInput blocks (keyed on stable item keys) never see stale closures.
private class RowDrag(
    val onStart: State<(TreeItem, String, Float) -> Unit>,
    val onMove: State<(Float) -> Unit>,
    val onEnd: State<(() -> Unit) -> Unit>
)

@Composable
private fun Modifier.treeDraggable(
    dragKey: String,
    item: TreeItem,
    label: String,
    rowDrag: RowDrag,
    onTap: () -> Unit,
    onMenu: () -> Unit
): Modifier {
    var coords by remember(dragKey) { mutableStateOf<LayoutCoordinates?>(null) }
    return this
        .onGloballyPositioned { coords = it }
        .clickable(onClick = onTap)
        .pointerInput(dragKey) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    val c = coords ?: return@detectDragGesturesAfterLongPress
                    rowDrag.onStart.value(item, label, c.localToWindow(offset).y)
                },
                onDrag = { change, _ ->
                    change.consume()
                    val c = coords ?: return@detectDragGesturesAfterLongPress
                    rowDrag.onMove.value(c.localToWindow(change.position).y)
                },
                onDragEnd = { rowDrag.onEnd.value(onMenu) },
                onDragCancel = { rowDrag.onEnd.value(onMenu) }
            )
        }
}

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

    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val edgePx = with(density) { 96.dp.toPx() }
    val drag = remember { DragHolder() }
    var dragItem by remember { mutableStateOf<TreeItem?>(null) }
    var dropTarget by remember { mutableStateOf<String?>(null) }
    var dragLabel by remember { mutableStateOf("") }
    var dragFingerY by remember { mutableFloatStateOf(0f) }

    val items = remember(notes, collapsed) { buildTree(notes, collapsed) }
    val folders = remember(notes) { folderPaths(notes) }
    var folderMenu by remember { mutableStateOf<String?>(null) }
    var moveTarget by remember { mutableStateOf<NoteEntity?>(null) }
    var moveFolderTarget by remember { mutableStateOf<String?>(null) }
    var renameFolderTarget by remember { mutableStateOf<String?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<String?>(null) }

    // Folders a dragged folder may not be dropped into: itself + descendants.
    val dragBlocked = remember(dragItem, notes) {
        val path = (dragItem as? TreeItem.Folder)?.path ?: return@remember emptySet()
        folders.filterTo(mutableSetOf()) { it == path || it.startsWith("$path/") }
    }

    fun resolveTarget(key: Any?, relY: Float): String? {
        val item = dragItem ?: return null
        // Empty list space (padding below/around rows, but still inside the
        // viewport) means the vault root. Outside the viewport resolves to
        // nothing so edge auto-scroll releases can't misfire to root.
        if (key == null) {
            return if (relY >= 0 && relY <= drag.viewportHeight) "" else null
        }
        return when {
            key is String && key.startsWith("d:") -> {
                val path = key.removePrefix("d:")
                if (path in dragBlocked) null else path
            }
            key is String && key.startsWith("f:") -> {
                val parent = key.removePrefix("f:").substringBeforeLast('/', "")
                val folder = item as? TreeItem.Folder
                if (folder != null && (parent == folder.path || parent.startsWith(folder.path + "/"))) {
                    null
                } else parent
            }
            else -> null
        }
    }

    fun onDragStart(item: TreeItem, label: String, winY: Float) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        drag.moved = false
        drag.hoverFolder = null
        dragLabel = label
        drag.fingerWindowY = winY
        dragFingerY = winY - drag.containerTop
        dragItem = item
        dropTarget = null
    }

    fun onDragMove(winY: Float) {
        drag.moved = true
        drag.fingerWindowY = winY
        dragFingerY = winY - drag.containerTop
        val relY = winY - drag.viewportTop
        val key = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { relY >= it.offset && relY < it.offset + it.size }?.key
        val target = resolveTarget(key, relY)
        if (target != dropTarget) dropTarget = target
        val nowMs = System.currentTimeMillis()
        if (target != null && target.isNotEmpty() && target in collapsed) {
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

    fun onDragEnd(openMenu: () -> Unit) {
        val wasMoved = drag.moved
        val item = dragItem
        val target = dropTarget
        dragItem = null
        dropTarget = null
        drag.hoverFolder = null
        if (!wasMoved) {
            openMenu()
            return
        }
        if (item == null || target == null) return
        when (item) {
            is TreeItem.Folder -> vm.moveFolder(item.path, target)
            is TreeItem.Note -> vm.moveNote(item.note.path, target)
        }
    }

    val rowDrag = RowDrag(
        onStart = rememberUpdatedState(::onDragStart),
        onMove = rememberUpdatedState(::onDragMove),
        onEnd = rememberUpdatedState(::onDragEnd)
    )

    // Edge auto-scroll while dragging. Reads the holder directly so the
    // per-frame loop never recomposes.
    LaunchedEffect(dragItem) {
        if (dragItem == null) return@LaunchedEffect
        while (true) {
            delay(16)
            val y = drag.fingerWindowY
            when {
                y < drag.viewportTop + edgePx -> listState.scrollBy(-14f)
                y > drag.viewportTop + drag.viewportHeight - edgePx -> listState.scrollBy(14f)
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
            onRefresh = { if (dragItem == null) vm.syncNow(force = true) },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (items.isEmpty() && !sync.syncing) {
                EmptyState()
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { drag.containerTop = it.positionInWindow().y }
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned {
                                drag.viewportTop = it.positionInWindow().y
                                drag.viewportHeight = it.size.height.toFloat()
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
                                    dimmed = (dragItem as? TreeItem.Folder)?.path == item.path,
                                    highlighted = dropTarget == item.path,
                                    dragModifier = Modifier.treeDraggable(
                                        dragKey = "d:${item.path}",
                                        item = item,
                                        label = "${item.name} (${item.noteCount})",
                                        rowDrag = rowDrag,
                                        onTap = { vm.toggleFolder(item.path) },
                                        onMenu = { folderMenu = item.path }
                                    ),
                                    onToggle = { vm.toggleFolder(item.path) }
                                )
                                is TreeItem.Note -> {
                                    val parent = item.note.path.substringBeforeLast('/', "")
                                    NoteRow(
                                        note = item.note,
                                        now = now,
                                        depth = item.depth,
                                        dimmed = (dragItem as? TreeItem.Note)?.note?.path == item.note.path,
                                        highlighted = dropTarget != null && dropTarget == parent,
                                        dragModifier = Modifier.treeDraggable(
                                            dragKey = "f:${item.note.path}",
                                            item = item,
                                            label = noteTitle(item.note.path, item.note.content),
                                            rowDrag = rowDrag,
                                            onTap = { vm.openNote(item.note.path) },
                                            onMenu = { moveTarget = item.note }
                                        ),
                                        onClick = { vm.openNote(item.note.path) }
                                    )
                                }
                            }
                        }
                        item {
                            SyncFooter(sync.syncing, unsynced, sync.report?.errors ?: 0, sync.report?.failures.orEmpty())
                        }
                    }
                    // Floating drag shadow, with the live destination. No pointer
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

@Composable
private fun FolderRow(
    folder: TreeItem.Folder,
    expanded: Boolean,
    dimmed: Boolean,
    highlighted: Boolean,
    dragModifier: Modifier,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.35f else 1f)
            .background(
                if (highlighted) MaterialTheme.colorScheme.surfaceContainerHigh
                else Color.Transparent
            )
            .then(dragModifier)
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

@Composable
private fun NoteRow(
    note: NoteEntity,
    now: Long,
    depth: Int,
    dimmed: Boolean,
    highlighted: Boolean,
    dragModifier: Modifier,
    onClick: () -> Unit
) {
    val updatedAt = note.localModifiedAt ?: note.remoteUpdatedAt
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.35f else 1f)
            .background(
                if (highlighted) MaterialTheme.colorScheme.surfaceContainerHigh
                else Color.Transparent
            )
            .then(dragModifier)
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
