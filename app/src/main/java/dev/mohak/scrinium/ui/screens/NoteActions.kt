package dev.mohak.scrinium.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mohak.scrinium.R
import dev.mohak.scrinium.ui.ConfirmDialog
import dev.mohak.scrinium.ui.Divider
import dev.mohak.scrinium.ui.FillButton
import dev.mohak.scrinium.ui.GhostButton
import dev.mohak.scrinium.ui.IconBtn
import dev.mohak.scrinium.ui.InputBox
import dev.mohak.scrinium.ui.MainViewModel
import dev.mohak.scrinium.ui.PromptDialog
import dev.mohak.scrinium.ui.Sc
import dev.mohak.scrinium.ui.Sheet
import dev.mohak.scrinium.ui.SheetTitle
import dev.mohak.scrinium.ui.Sym
import dev.mohak.scrinium.ui.Type
import dev.mohak.scrinium.ui.printHtml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The per-note actions offered from the notes list and the editor menu.
 * Each asks first (rename, move, share, delete); [Host] draws whichever is
 * open. One instance per screen.
 */
@Stable
class NoteActions {
    var rename by mutableStateOf<String?>(null)
    var move by mutableStateOf<String?>(null)
    var share by mutableStateOf<String?>(null)
    var delete by mutableStateOf<String?>(null)

    @Composable
    fun Host(vm: MainViewModel, folders: List<String>) {
        rename?.let { path ->
            PromptDialog(
                "Rename note",
                path.substringAfterLast('/').removeSuffix(".md"),
                "Rename",
                onConfirm = { vm.renameNote(path, it) },
                onDismiss = { rename = null }
            )
        }
        move?.let { path ->
            MoveSheet(
                title = "Move \"${path.substringAfterLast('/').removeSuffix(".md")}\"",
                folders = folders,
                current = path.substringBeforeLast('/', ""),
                onDismiss = { move = null },
                onMove = { vm.moveNote(path, it) }
            )
        }
        share?.let { path -> ShareSheet(vm, path) { share = null } }
        delete?.let { path ->
            ConfirmDialog(
                "Delete note?",
                "It moves to the server trash on the next sync. Restore it from Trash.",
                "Delete",
                onConfirm = { vm.deleteNote(path) },
                onDismiss = { delete = null }
            )
        }
    }
}

@Composable
fun rememberNoteActions(): NoteActions = remember { NoteActions() }

/** Opens the print dialog for a note, where "Save as PDF" is a printer. */
fun exportPdf(context: Context, scope: CoroutineScope, vm: MainViewModel, path: String, text: String) {
    scope.launch {
        printHtml(context, path.substringAfterLast('/').removeSuffix(".md"), vm.printableHtml(path, text))
    }
}

/**
 * Folder picker. Typing filters the list; a name that matches no folder can
 * be created by moving into it.
 */
@Composable
fun MoveSheet(title: String, folders: List<String>, current: String, onDismiss: () -> Unit, onMove: (String) -> Unit) {
    var filter by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(current) }
    val q = filter.trim()
    val shown = folders.filter { q.isEmpty() || it.contains(q, ignoreCase = true) }
    Sheet(onDismiss) {
        SheetTitle(title)
        InputBox(filter, { filter = it }, "Filter or name a new folder", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), icon = R.drawable.ms_search)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(top = 4.dp)) {
            if (q.isEmpty()) item { FolderPick(R.drawable.ms_home, "Vault root", 0, selected == "", current == "") { selected = "" } }
            if (q.isNotEmpty() && folders.none { it.equals(q, ignoreCase = true) }) {
                item { FolderPick(R.drawable.ms_create_new_folder, "New folder \"$q\"", 0, selected == q, false) { selected = q } }
            }
            items(shown, key = { it }) { f ->
                val depth = if (q.isEmpty()) f.count { it == '/' } else 0
                FolderPick(R.drawable.ms_folder, if (q.isEmpty()) f.substringAfterLast('/') else f, depth, selected == f, f == current) { selected = f }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            GhostButton("Cancel", onDismiss, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            FillButton("Move here", {
                onDismiss()
                onMove(selected)
            }, Modifier.weight(1f), enabled = selected != current)
        }
    }
}

@Composable
private fun FolderPick(icon: Int, label: String, depth: Int, selected: Boolean, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable(onClick = onClick)
            .background(if (selected) Sc.hover else androidx.compose.ui.graphics.Color.Transparent)
            .padding(start = (16 + depth * 16).dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Sym(icon, tint = if (isCurrent) Sc.accent else Sc.text3, size = 18.dp)
        Text(
            label,
            style = Type.body.copy(color = if (isCurrent) Sc.accent else Sc.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 10.dp)
        )
        if (isCurrent) Text("current", style = Type.meta)
        if (selected) Sym(R.drawable.ms_check, tint = Sc.accent, size = 18.dp)
    }
}

/** Public read-only links for a note: copy, remove, or add one (optionally locked). */
@Composable
private fun ShareSheet(vm: MainViewModel, path: String, onDismiss: () -> Unit) {
    val shares by vm.shares.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val copyScope = rememberCoroutineScope()
    var password by remember(path) { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(path) { vm.loadShares(path) }
    Sheet(onDismiss) {
        SheetTitle("Share links", "Anyone with a link can read this note.")
        Divider()
        shares.forEach { share ->
            val url = vm.shareUrl(share.id)
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(url.substringAfter("://"), style = Type.mono.copy(color = Sc.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (share.hasPassword) "password" else "open", style = Type.meta)
                }
                IconBtn(R.drawable.ms_content_copy, "Copy link", { copyScope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("link", url))) } })
                IconBtn(R.drawable.ms_close, "Remove link", { vm.deleteShareLink(share.id) }, tint = Sc.red)
            }
        }
        InputBox(password, { password = it }, "Password (optional)", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), icon = R.drawable.ms_lock)
        FillButton("New link", {
            vm.createShareLink(path, password.ifBlank { null })
            password = ""
        }, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    }
}

/** Long-press menu for a note in the list. */
@Composable
fun NoteMenuSheet(
    title: String,
    subtitle: String,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    fun act(f: () -> Unit) = { onDismiss(); f() }
    Sheet(onDismiss) {
        SheetTitle(title, subtitle)
        Divider()
        dev.mohak.scrinium.ui.MenuRow(if (pinned) R.drawable.ms_star_fill else R.drawable.ms_star, if (pinned) "Unpin" else "Pin", act(onPin), tint = if (pinned) Sc.accent else null)
        dev.mohak.scrinium.ui.MenuRow(R.drawable.ms_edit, "Rename", act(onRename))
        dev.mohak.scrinium.ui.MenuRow(R.drawable.ms_drive_file_move, "Move to folder", act(onMove))
        dev.mohak.scrinium.ui.MenuRow(R.drawable.ms_link, "Share link", act(onShare))
        dev.mohak.scrinium.ui.MenuRow(R.drawable.ms_picture_as_pdf, "Export PDF", act(onExport))
        dev.mohak.scrinium.ui.MenuRow(R.drawable.ms_delete, "Delete", act(onDelete), danger = true)
    }
}
