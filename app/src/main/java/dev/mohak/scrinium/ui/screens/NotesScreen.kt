package dev.mohak.scrinium.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mohak.scrinium.R
import dev.mohak.scrinium.data.local.NoteEntity
import dev.mohak.scrinium.ui.ConfirmDialog
import dev.mohak.scrinium.ui.Divider
import dev.mohak.scrinium.ui.EmptyState
import dev.mohak.scrinium.ui.ErrorStrip
import dev.mohak.scrinium.ui.Fab
import dev.mohak.scrinium.ui.IconBtn
import dev.mohak.scrinium.ui.MainViewModel
import dev.mohak.scrinium.ui.Menu
import dev.mohak.scrinium.ui.MenuRow
import dev.mohak.scrinium.ui.PromptDialog
import dev.mohak.scrinium.ui.Sc
import dev.mohak.scrinium.ui.Sheet
import dev.mohak.scrinium.ui.SheetTitle
import dev.mohak.scrinium.ui.Sym
import dev.mohak.scrinium.ui.TopBar
import dev.mohak.scrinium.ui.TreeItem
import dev.mohak.scrinium.ui.Type
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
    var noteMenu by remember { mutableStateOf<NoteEntity?>(null) }
    var moveFolderTarget by remember { mutableStateOf<String?>(null) }
    var renameFolderTarget by remember { mutableStateOf<String?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<String?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    val actions = rememberNoteActions()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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

    Column(Modifier.fillMaxSize()) {
        TopBar("Notes") {
            IconBtn(R.drawable.ms_sell, "Tags", { vm.openTags() })
            Box {
                IconBtn(R.drawable.ms_more_vert, "More", { overflow = true }, active = overflow)
                Menu(overflow, { overflow = false }) {
                    MenuRow(R.drawable.ms_sync, "Sync now", { overflow = false; vm.syncNow(force = true) })
                    MenuRow(R.drawable.ms_create_new_folder, "New folder", { overflow = false; newFolder = true })
                    MenuRow(R.drawable.ms_unfold_less, "Collapse all", { overflow = false; vm.collapseAll() })
                    Divider()
                    MenuRow(R.drawable.ms_delete, "Trash", { overflow = false; vm.openTrash() })
                    MenuRow(R.drawable.ms_settings, "Settings", { overflow = false; vm.openSettings() })
                }
            }
        }
        sync.error?.let { ErrorStrip(it) { vm.dismissSyncError() } }
        Box(Modifier.weight(1f)) {
            PullToRefreshBox(
                isRefreshing = sync.syncing,
                onRefresh = { if (dragItem == null) vm.syncNow(force = true) },
                modifier = Modifier.fillMaxSize()
            ) {
                if (items.isEmpty() && !sync.syncing) {
                    EmptyState(R.drawable.ms_description, "No notes yet", "Pull to sync, or tap the pen to write one.")
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
                            contentPadding = PaddingValues(top = 2.dp, bottom = 96.dp)
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
                                SyncFooter(sync.syncing, unsynced, sync.lastSyncAt, now, sync.report?.errors ?: 0, sync.report?.failures.orEmpty())
                            }
                        }
                        // Floating drag shadow with the live destination. No pointer
                        // handlers, so touches pass straight through to the list.
                        if (dragItem != null) {
                            val destLabel = when (dropTarget) {
                                null -> null
                                "" -> "Vault root"
                                else -> dropTarget
                            }
                            Text(
                                text = if (destLabel == null) dragLabel else "$dragLabel  ->  $destLabel",
                                style = Type.body,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp)
                                    .offset {
                                        IntOffset(0, (dragFingerY - with(density) { 24.dp.toPx() }).roundToInt())
                                    }
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Sc.press)
                                    .border(1.dp, Sc.accent, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
                    }
                }
            }
            Fab(R.drawable.ms_edit_square, "New note", { vm.createNote() }, Modifier.align(Alignment.BottomEnd))
        }
    }

    actions.Host(vm, folders)

    noteMenu?.let { note ->
        val dir = note.path.substringBeforeLast('/', "")
        NoteMenuSheet(
            title = noteTitle(note.path, note.content),
            subtitle = (dir.ifBlank { "Vault root" }) + "  ·  edited " + relativeTime(note.localModifiedAt ?: note.remoteUpdatedAt, now),
            pinned = note.path in pinned,
            onDismiss = { noteMenu = null },
            onPin = { vm.togglePin(note.path) },
            onRename = { actions.rename = note.path },
            onMove = { actions.move = note.path },
            onShare = { actions.share = note.path },
            onExport = { exportPdf(context, scope, vm, note.path, note.content) },
            onDelete = { actions.delete = note.path }
        )
    }

    folderMenu?.let { folder ->
        val count = notes.count { it.path.startsWith("$folder/") }
        Sheet(onDismiss = { folderMenu = null }) {
            fun act(f: () -> Unit) = { folderMenu = null; f() }
            SheetTitle(folder.substringAfterLast('/'), "$count note${if (count == 1) "" else "s"}  ·  ${folder.substringBeforeLast('/', "").ifBlank { "Vault root" }}")
            Divider()
            MenuRow(R.drawable.ms_note_add, "New note here", act { vm.createNoteIn(folder) })
            val isPinned = folder in pinned
            MenuRow(if (isPinned) R.drawable.ms_star_fill else R.drawable.ms_star, if (isPinned) "Unpin" else "Pin", act { vm.togglePin(folder) }, tint = if (isPinned) Sc.accent else null)
            MenuRow(R.drawable.ms_edit, "Rename", act { renameFolderTarget = folder })
            MenuRow(R.drawable.ms_drive_file_move, "Move to folder", act { moveFolderTarget = folder })
            MenuRow(R.drawable.ms_delete, "Delete", act { deleteFolderTarget = folder }, danger = true)
        }
    }

    moveFolderTarget?.let { folder ->
        MoveSheet(
            title = "Move \"${folder.substringAfterLast('/')}\"",
            folders = folders.filter { it != folder && !it.startsWith("$folder/") },
            current = folder.substringBeforeLast('/', ""),
            onDismiss = { moveFolderTarget = null },
            onMove = { vm.moveFolder(folder, it) }
        )
    }

    renameFolderTarget?.let { folder ->
        PromptDialog(
            "Rename folder",
            folder.substringAfterLast('/'),
            "Rename",
            onConfirm = { vm.renameFolder(folder, it) },
            onDismiss = { renameFolderTarget = null }
        )
    }

    deleteFolderTarget?.let { folder ->
        val count = notes.count { it.path.startsWith("$folder/") }
        ConfirmDialog(
            "Delete folder?",
            "$count note${if (count == 1) "" else "s"} move to the server trash on the next sync.",
            "Delete",
            onConfirm = { vm.deleteFolder(folder) },
            onDismiss = { deleteFolderTarget = null }
        )
    }

    // A folder exists only through its notes, so a new folder starts with one.
    if (newFolder) {
        PromptDialog(
            "New folder",
            "",
            "Create",
            onConfirm = { vm.createNoteIn(it) },
            onDismiss = { newFolder = false },
            placeholder = "Folder name, or a/b for nested"
        )
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
            .height(if (folder.pathHint != null) 44.dp else 36.dp)
            .background(if (highlighted) Sc.hover else Color.Transparent)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(start = (14 + folder.depth * 20).dp, end = 16.dp)
            .alpha(if (dimmed) 0.35f else 1f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Sym(if (expanded) R.drawable.ms_expand_more else R.drawable.ms_chevron_right, tint = Sc.text3, size = 17.dp, desc = if (expanded) "Collapse" else "Expand")
        Spacer(Modifier.width(6.dp))
        Sym(if (expanded) R.drawable.ms_folder_open else R.drawable.ms_folder, tint = Sc.text3, size = 17.dp)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(folder.name, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            folder.pathHint?.let { Text(it, style = Type.meta, maxLines = 1) }
        }
        if (folder.pinned) Sym(R.drawable.ms_star_fill, tint = Sc.accent, size = 14.dp, desc = "Pinned")
        if (folder.hasUnsynced) Sym(R.drawable.ms_cloud_upload, tint = Sc.text3, size = 15.dp, desc = "Not synced", modifier = Modifier.padding(start = 8.dp))
        Text(folder.noteCount.toString(), style = Type.mono, modifier = Modifier.padding(start = 8.dp))
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
            .height(36.dp)
            .background(if (highlighted) Sc.hover else Color.Transparent)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(start = (14 + depth * 20).dp, end = 16.dp)
            .alpha(if (dimmed) 0.35f else 1f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (pinned) Sym(R.drawable.ms_star_fill, tint = Sc.accent, size = 17.dp, desc = "Pinned")
        else Spacer(Modifier.width(17.dp))
        Spacer(Modifier.width(6.dp))
        Sym(R.drawable.ms_description, tint = Sc.text3, size = 17.dp)
        Text(
            noteTitle(note.path, note.content),
            style = Type.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp)
        )
        if (note.localModifiedAt != null) Sym(R.drawable.ms_cloud_upload, tint = Sc.text3, size = 15.dp, desc = "Not synced", modifier = Modifier.padding(start = 8.dp))
        Text(shortAge(updatedAt, now), style = Type.mono, modifier = Modifier.padding(start = 8.dp))
    }
}

// "now", "5m", "3h", "2d", "4w", "8mo": the tree's right-hand age column.
private fun shortAge(epoch: Long, now: Long): String {
    if (epoch <= 0) return ""
    val m = (now - epoch).coerceAtLeast(0) / 60_000
    return when {
        m < 1 -> "now"
        m < 60 -> "${m}m"
        m < 60 * 24 -> "${m / 60}h"
        m < 60 * 24 * 7 -> "${m / (60 * 24)}d"
        m < 60 * 24 * 30 -> "${m / (60 * 24 * 7)}w"
        m < 60 * 24 * 365 -> "${m / (60 * 24 * 30)}mo"
        else -> "${m / (60 * 24 * 365)}y"
    }
}

@Composable
private fun SyncFooter(syncing: Boolean, unsynced: Int, lastSyncAt: Long?, now: Long, errors: Int, failures: List<String>) {
    val parts = buildList {
        when {
            syncing -> add("syncing")
            errors > 0 -> add("$errors failed: ${(failures.firstOrNull() ?: "pull to retry").take(60)}")
        }
        if (unsynced > 0) add("$unsynced unsynced")
        if (!syncing && lastSyncAt != null) add("synced ${shortAge(lastSyncAt, now).let { if (it == "now") "just now" else "$it ago" }}")
    }
    if (parts.isEmpty()) return
    Text(
        parts.joinToString("  ·  "),
        style = Type.mono.copy(color = if (errors > 0) Sc.red else Sc.text3),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)
    )
}
