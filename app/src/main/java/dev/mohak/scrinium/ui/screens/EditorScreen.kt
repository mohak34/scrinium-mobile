package dev.mohak.scrinium.ui.screens

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mohak.scrinium.ui.MainViewModel
import dev.mohak.scrinium.ui.MarkdownText
import dev.mohak.scrinium.ui.TasksViewModel
import dev.mohak.scrinium.ui.lineStartOffset
import dev.mohak.scrinium.ui.LiveMarkdownTransformation
import dev.mohak.scrinium.ui.LivePalette
import dev.mohak.scrinium.ui.completions
import dev.mohak.scrinium.ui.printHtml
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: MainViewModel, tasksVm: TasksViewModel) {
    val editor by vm.editor.collectAsStateWithLifecycle()
    val state = editor ?: return

    var preview by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val fileName = state.path.substringAfterLast('/').removeSuffix(".md")
    val sync by vm.sync.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uploading by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val previewScroll = rememberScrollState()
    // Heading line -> y in the preview, filled as headings lay out, so an
    // outline tap can scroll there.
    val headingY = remember(state.path) { mutableMapOf<Int, Int>() }

    // The field keeps its own selection so an image lands at the cursor.
    // Text changed elsewhere (task toggles in preview, title sync) is adopted
    // with the selection clamped to the new length. Keyed by the editor
    // session, not the path, so a title-sync rename keeps cursor and IME.
    var field by remember(state.id) { mutableStateOf(TextFieldValue(state.text)) }
    if (field.text != state.text) {
        val end = state.text.length
        field = field.copy(
            text = state.text,
            selection = TextRange(field.selection.start.coerceAtMost(end), field.selection.end.coerceAtMost(end))
        )
    }

    var focused by remember { mutableStateOf(false) }
    val notes by vm.notesFlow.collectAsStateWithLifecycle()
    val tags by vm.vaultTags.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val palette = remember(colors) {
        LivePalette(colors.primary, colors.outline, colors.surfaceContainerHigh, colors.tertiary.copy(alpha = 0.3f))
    }
    // Marks show on the cursor's lines only while the field has focus.
    val live = if (focused) {
        LiveMarkdownTransformation(field.selection.min, field.selection.max, palette)
    } else {
        LiveMarkdownTransformation(-1, -1, palette)
    }
    val suggestions = remember(field.text, field.selection, state.path, notes, tags, focused) {
        if (!focused || !field.selection.collapsed) emptyList()
        else completions(field.text, field.selection.start, state.path, notes.map { it.path }, tags)
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val notePath = state.path
        val editorId = state.id
        scope.launch {
            uploading = true
            val markdown = try {
                // Providers throw for unreadable picks (an offline cloud photo,
                // a revoked grant); that is an upload error, not a crash.
                val (bytes, mime, name) = withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "image"
                    Triple(resolver.openInputStream(uri)?.use { it.readBytes() }, resolver.getType(uri), name)
                }
                if (bytes == null) throw IOException("no data")
                vm.uploadImage(notePath, bytes, mime, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vm.reportError("Couldn't read image: ${e.message}")
                null
            } finally {
                uploading = false
            }
            if (markdown == null || vm.editor.value?.id != editorId) return@launch
            // Own line, like the web's paste: break before it if mid-line.
            val at = field.selection.start
            val needsBreak = at > 0 && field.text[at - 1] != '\n'
            val insert = (if (needsBreak) "\n" else "") + markdown + "\n"
            val text = field.text.substring(0, at) + insert + field.text.substring(at)
            field = TextFieldValue(text, TextRange(at + insert.length))
            vm.updateEditorText(text)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName, maxLines = 1) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = { vm.closeEditor() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { preview = !preview }) {
                        Text(if (preview) "Edit" else "Preview")
                    }
                    IconButton(onClick = {
                        vm.loadBacklinks()
                        tasksVm.loadNoteTasks(state.path)
                        showInfo = true
                    }) {
                        Icon(Icons.Default.Info, contentDescription = "Links and tasks")
                    }
                    IconButton(onClick = {
                        vm.loadShares(state.path)
                        showShare = true
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    showRename = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (uploading) "Uploading image" else "Insert image") },
                                enabled = !uploading,
                                onClick = {
                                    showMenu = false
                                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Export PDF") },
                                onClick = {
                                    showMenu = false
                                    scope.launch {
                                        val html = vm.printableHtml(state.path, field.text)
                                        printHtml(context, fileName, html)
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    showDelete = true
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            sync.error?.let { ErrorBanner(it) { vm.dismissSyncError() } }
            if (preview) {
                MarkdownText(
                    markdown = state.text,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(previewScroll)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    onWikilinkClick = { vm.openWikilink(it) },
                    onToggleTaskLine = { vm.toggleTaskLine(it) },
                    onTagClick = { vm.searchTag(it) },
                    loadImage = { vm.loadImage(state.path, it) },
                    onHeadingPositioned = { line, y -> headingY[line] = y }
                )
            } else Column(modifier = Modifier.fillMaxSize().imePadding()) {
                BasicTextField(
                    value = field,
                    onValueChange = {
                        field = it
                        if (it.text != state.text) vm.updateEditorText(it.text)
                    },
                    visualTransformation = live,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .focusRequester(focus)
                        .onFocusChanged { focused = it.isFocused }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    textStyle = TextStyle(
                        fontSize = 15.sp,
                        lineHeight = 25.5.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        if (state.text.isEmpty()) {
                            Text(
                                text = "Start writing\u2026",
                                style = TextStyle(
                                    fontSize = 15.sp,
                                    lineHeight = 25.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                        innerTextField()
                    }
                )
                if (suggestions.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.surfaceContainer),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        items(suggestions, key = { it.label + it.detail }) { s ->
                            TextButton(onClick = {
                                val cursor = field.selection.start
                                val text = field.text.substring(0, s.from) + s.insert + field.text.substring(cursor)
                                field = TextFieldValue(text, TextRange(s.from + s.insert.length))
                                vm.updateEditorText(text)
                            }) {
                                Text(s.label, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showInfo) {
        NotePanel(
            vm,
            tasksVm,
            state.path,
            state.text,
            onJump = { line ->
                if (preview) {
                    headingY[line]?.let { y -> scope.launch { previewScroll.animateScrollTo(y) } }
                } else {
                    val at = lineStartOffset(field.text, line)
                    field = field.copy(selection = TextRange(at))
                    focus.requestFocus()
                }
            },
            onDismiss = { showInfo = false }
        )
    }

    if (showRename) {
        var name by remember(state.path) { mutableStateOf(fileName) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename note") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRename = false
                    vm.renameCurrentNote(name)
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("Cancel") }
            }
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete note?") },
            text = { Text("The note is moved to the server trash on the next sync.") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    vm.deleteCurrentNote()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Cancel") }
            }
        )
    }

    if (showShare) {
        val shares by vm.shares.collectAsStateWithLifecycle()
        val clipboard = LocalClipboardManager.current
        var password by remember(state.path) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showShare = false },
            title = { Text("Share links") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (shares.isEmpty()) {
                        Text(
                            text = "No links yet. Anyone with the link can read this note.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    for (share in shares) {
                        val url = vm.shareUrl(share.id)
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text(
                                text = url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row {
                                if (share.hasPassword) {
                                    Text(
                                        text = "locked",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                }
                                TextButton(onClick = {
                                    clipboard.setText(AnnotatedString(url))
                                }) { Text("Copy") }
                                TextButton(onClick = { vm.deleteShareLink(share.id) }) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        placeholder = { Text("Password (optional)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.createShareLink(password)
                    password = ""
                }) { Text("New link") }
            },
            dismissButton = {
                TextButton(onClick = { showShare = false }) { Text("Done") }
            }
        )
    }
}