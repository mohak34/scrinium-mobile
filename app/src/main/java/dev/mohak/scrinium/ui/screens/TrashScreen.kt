package dev.mohak.scrinium.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mohak.scrinium.R
import dev.mohak.scrinium.data.remote.TrashEntry
import dev.mohak.scrinium.ui.ConfirmDialog
import dev.mohak.scrinium.ui.Divider
import dev.mohak.scrinium.ui.EmptyState
import dev.mohak.scrinium.ui.ErrorStrip
import dev.mohak.scrinium.ui.GroupHeader
import dev.mohak.scrinium.ui.IconBtn
import dev.mohak.scrinium.ui.MainViewModel
import dev.mohak.scrinium.ui.Menu
import dev.mohak.scrinium.ui.MenuRow
import dev.mohak.scrinium.ui.Sc
import dev.mohak.scrinium.ui.Sheet
import dev.mohak.scrinium.ui.SheetTitle
import dev.mohak.scrinium.ui.Sym
import dev.mohak.scrinium.ui.TopBar
import dev.mohak.scrinium.ui.Type
import dev.mohak.scrinium.ui.startOfToday
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Server trash, grouped by when things were deleted. Swipe right to
 * restore, left to delete forever (asks first); tap for both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(vm: MainViewModel) {
    val entries by vm.trash.collectAsStateWithLifecycle()
    val loading by vm.trashLoading.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<TrashEntry?>(null) }
    var purge by remember { mutableStateOf<TrashEntry?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val today = startOfToday()
    val weekAgo = today - 6 * 86_400_000L
    val sorted = entries.sortedByDescending { it.deletedAt }
    val groups = listOf(
        "Today" to sorted.filter { it.deletedAt >= today },
        "This week" to sorted.filter { it.deletedAt in weekAgo until today },
        "Older" to sorted.filter { it.deletedAt < weekAgo }
    ).filter { it.second.isNotEmpty() }

    Column(Modifier.fillMaxSize()) {
        TopBar("Trash", onBack = { vm.closeTrash() }) {
            if (entries.isNotEmpty()) {
                Box {
                    IconBtn(R.drawable.ms_more_vert, "More", { menu = true }, active = menu)
                    Menu(menu, { menu = false }) {
                        MenuRow(R.drawable.ms_restore_from_trash, "Restore all", { menu = false; vm.restoreAllTrash() })
                        MenuRow(R.drawable.ms_delete_sweep, "Empty trash", { menu = false; confirmEmpty = true }, danger = true)
                    }
                }
            }
        }
        sync.error?.let { ErrorStrip(it) { vm.dismissSyncError() } }
        PullToRefreshBox(isRefreshing = loading, onRefresh = { vm.refreshTrash() }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (!loading && entries.isEmpty()) {
                    item { EmptyState(R.drawable.ms_delete, "Trash is empty", "Deleted notes land here, on the server.") }
                }
                groups.forEach { (label, rows) ->
                    item(key = "h-$label") { GroupHeader(label, "${rows.size}") }
                    items(rows, key = { it.trashName }) { e ->
                        SwipeRow(e, today, onRestore = { vm.restoreTrashEntry(e) }, onPurge = { purge = e }, onTap = { open = e })
                    }
                }
            }
        }
    }

    open?.let { e ->
        val dir = e.originalPath.substringBeforeLast('/', "").ifBlank { "vault root" }
        Sheet(onDismiss = { open = null }) {
            SheetTitle(e.originalPath.substringAfterLast('/').removeSuffix(".md"), "was in $dir  ·  deleted ${deletedLabel(e.deletedAt, today)}")
            Divider()
            MenuRow(R.drawable.ms_restore_from_trash, "Restore to $dir", { open = null; vm.restoreTrashEntry(e) }, tint = Sc.accent)
            MenuRow(R.drawable.ms_delete_forever, "Delete forever", { open = null; purge = e }, danger = true)
        }
    }
    purge?.let { e ->
        ConfirmDialog(
            "Delete forever?",
            "${e.originalPath} can't be recovered.",
            "Delete",
            onConfirm = { vm.purgeTrashEntry(e) },
            onDismiss = { purge = null }
        )
    }
    if (confirmEmpty) {
        ConfirmDialog(
            "Empty trash?",
            "Everything in trash is deleted for good.",
            "Empty",
            onConfirm = { vm.emptyTrash() },
            onDismiss = { confirmEmpty = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(e: TrashEntry, today: Long, onRestore: () -> Unit, onPurge: () -> Unit, onTap: () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = state,
        // The row always springs back: delete asks first, and a restore only
        // drops the row once the trash refresh confirms it, so a failed
        // restore leaves it swipeable for a retry.
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) onRestore() else onPurge()
            scope.launch { state.reset() }
        },
        backgroundContent = {
            val restore = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Row(
                Modifier
                    .fillMaxSize()
                    .background(if (restore) Sc.fill else Sc.redFill)
                    .padding(horizontal = 20.dp),
                horizontalArrangement = if (restore) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (restore) {
                    Sym(R.drawable.ms_restore_from_trash, tint = Sc.onFill)
                    Text("Restore", style = Type.body.copy(color = Sc.onFill), modifier = Modifier.padding(start = 8.dp))
                } else {
                    Text("Delete forever", style = Type.body.copy(color = Sc.red), modifier = Modifier.padding(end = 8.dp))
                    Sym(R.drawable.ms_delete_forever, tint = Sc.red)
                }
            }
        }
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .background(Sc.bg)
                .clickable(onClick = onTap)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Sym(if (e.isDir) R.drawable.ms_folder else R.drawable.ms_description, tint = Sc.text3, size = 17.dp)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(e.originalPath.substringAfterLast('/').removeSuffix(".md"), style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(e.originalPath.substringBeforeLast('/', "").ifBlank { "/" }, style = Type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(deletedLabel(e.deletedAt, today), style = Type.mono, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

// Time for today, weekday this week, date before that.
private fun deletedLabel(at: Long, today: Long): String = when {
    at <= 0 -> ""
    at >= today -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
    at >= today - 6 * 86_400_000L -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(at))
    else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(at))
}
