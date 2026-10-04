package dev.mohak.scrinium.ui.screens

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mohak.scrinium.R
import dev.mohak.scrinium.ui.Divider
import dev.mohak.scrinium.ui.ErrorStrip
import dev.mohak.scrinium.ui.IconBtn
import dev.mohak.scrinium.ui.LiveMarkdownTransformation
import dev.mohak.scrinium.ui.LivePalette
import dev.mohak.scrinium.ui.MainViewModel
import dev.mohak.scrinium.ui.MarkdownText
import dev.mohak.scrinium.ui.Menu
import dev.mohak.scrinium.ui.MenuRow
import dev.mohak.scrinium.ui.Sc
import dev.mohak.scrinium.ui.TasksViewModel
import dev.mohak.scrinium.ui.TopBar
import dev.mohak.scrinium.ui.Type
import dev.mohak.scrinium.ui.completions
import dev.mohak.scrinium.ui.insertText
import dev.mohak.scrinium.ui.toggleLinePrefix
import dev.mohak.scrinium.ui.wrapSelection
import dev.mohak.scrinium.ui.folderPaths
import dev.mohak.scrinium.ui.lineStartOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EditorScreen(vm: MainViewModel, tasksVm: TasksViewModel) {
    val editor by vm.editor.collectAsStateWithLifecycle()
    val state = editor ?: return

    var preview by remember { mutableStateOf(false) }
    var showPanel by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val actions = rememberNoteActions()

    val sync by vm.sync.collectAsStateWithLifecycle()
    val pinned by vm.pinned.collectAsStateWithLifecycle()
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
    // with the selection clamped to the new length.
    var field by remember(state.path) { mutableStateOf(TextFieldValue(state.text)) }
    if (field.text != state.text) {
        val end = state.text.length
        field = field.copy(
            text = state.text,
            selection = TextRange(field.selection.start.coerceAtMost(end), field.selection.end.coerceAtMost(end))
        )
    }

    // Undo: a snapshot before each burst of typing (a pause of a second
    // starts a new one), so one tap undoes a phrase, not a letter.
    val undo = remember(state.path) { ArrayDeque<TextFieldValue>() }
    var lastEdit by remember(state.path) { mutableLongStateOf(0L) }

    fun apply(next: TextFieldValue) {
        if (next.text != field.text) {
            val now = System.currentTimeMillis()
            if (undo.isEmpty() || now - lastEdit > 1000) {
                undo.addLast(field)
                if (undo.size > 100) undo.removeFirst()
            }
            lastEdit = now
        }
        field = next
        if (next.text != state.text) vm.updateEditorText(next.text)
    }

    var focused by remember { mutableStateOf(false) }
    val notes by vm.notesFlow.collectAsStateWithLifecycle()
    val tags by vm.vaultTags.collectAsStateWithLifecycle()
    val palette = remember { LivePalette(Sc.accent, Sc.text4, Sc.press, Sc.yellow.copy(alpha = 0.25f)) }
    // Marks show on the cursor's lines only while the field has focus.
    val live = if (focused) {
        LiveMarkdownTransformation(field.selection.min, field.selection.max, palette)
    } else {
        LiveMarkdownTransformation(-1, -1, palette)
    }
    val suggestions = remember(field.text, field.selection, notes, tags, focused) {
        if (!focused || !field.selection.collapsed) emptyList()
        else completions(field.text, field.selection.start, notes.map { it.path }, tags)
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val notePath = state.path
        scope.launch {
            uploading = true
            val (bytes, mime, name) = withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "image"
                Triple(resolver.openInputStream(uri)?.use { it.readBytes() }, resolver.getType(uri), name)
            }
            val markdown = bytes?.let { vm.uploadImage(notePath, it, mime, name) }
            uploading = false
            if (markdown == null || vm.editor.value?.path != notePath) return@launch
            // Own line, like the web's paste: break before it if mid-line.
            val at = field.selection.start
            val needsBreak = at > 0 && field.text[at - 1] != '\n'
            val insert = (if (needsBreak) "\n" else "") + markdown + "\n"
            apply(TextFieldValue(field.text.substring(0, at) + insert + field.text.substring(at), TextRange(at + insert.length)))
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            onBack = { vm.closeEditor() },
            titleContent = { Crumbs(state.path) }
        ) {
            IconBtn(
                if (preview) R.drawable.ms_visibility_fill else R.drawable.ms_visibility,
                if (preview) "Edit" else "Reading view",
                { preview = !preview },
                tint = if (preview) Sc.accent else Sc.text2
            )
            IconBtn(R.drawable.ms_right_panel_open, "Outline, links and tasks", {
                vm.loadBacklinks()
                tasksVm.loadNoteTasks(state.path)
                showPanel = true
            })
            Box {
                IconBtn(R.drawable.ms_more_vert, "More", { showMenu = true }, active = showMenu)
                Menu(showMenu, { showMenu = false }) {
                    val isPinned = state.path in pinned
                    fun act(f: () -> Unit) = { showMenu = false; f() }
                    MenuRow(if (isPinned) R.drawable.ms_star_fill else R.drawable.ms_star, if (isPinned) "Unpin" else "Pin", act { vm.togglePin(state.path) }, tint = if (isPinned) Sc.accent else null)
                    MenuRow(R.drawable.ms_edit, "Rename", act { actions.rename = state.path })
                    MenuRow(R.drawable.ms_drive_file_move, "Move", act { actions.move = state.path })
                    MenuRow(R.drawable.ms_link, "Share link", act { actions.share = state.path })
                    MenuRow(R.drawable.ms_picture_as_pdf, "Export PDF", act { exportPdf(context, scope, vm, state.path, field.text) })
                    Divider()
                    MenuRow(R.drawable.ms_delete, "Delete", act { actions.delete = state.path }, danger = true)
                }
            }
        }
        sync.error?.let { ErrorStrip(it) { vm.dismissSyncError() } }
        if (preview) {
            MarkdownText(
                markdown = state.text,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(previewScroll)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                onWikilinkClick = { vm.openWikilink(it) },
                onToggleTaskLine = { vm.toggleTaskLine(it) },
                onTagClick = { vm.searchTag(it) },
                loadImage = { vm.loadImage(state.path, it) },
                onHeadingPositioned = { line, y -> headingY[line] = y }
            )
        } else Column(Modifier.fillMaxSize().imePadding()) {
            BasicTextField(
                value = field,
                onValueChange = { apply(it) },
                visualTransformation = live,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .focusRequester(focus)
                    .onFocusChanged { focused = it.isFocused }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                textStyle = Type.read,
                cursorBrush = SolidColor(Sc.accent),
                decorationBox = { inner ->
                    if (state.text.isEmpty()) Text("Start writing", style = Type.read.copy(color = Sc.text3))
                    inner()
                }
            )
            if (focused && suggestions.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .topLine()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    suggestions.forEachIndexed { i, s ->
                        Text(
                            s.label,
                            style = Type.body.copy(color = if (i == 0) Sc.accent else Sc.text2, fontWeight = if (i == 0) FontWeight.Medium else FontWeight.Normal),
                            maxLines = 1,
                            modifier = Modifier
                                .clickable {
                                    val cursor = field.selection.start
                                    val text = field.text.substring(0, s.from) + s.insert + field.text.substring(cursor)
                                    apply(TextFieldValue(text, TextRange(s.from + s.insert.length)))
                                }
                                .padding(horizontal = 10.dp, vertical = 10.dp)
                        )
                    }
                }
            }
            if (focused) {
                FormatBar(
                    canUndo = undo.isNotEmpty(),
                    uploading = uploading,
                    onBold = { apply(wrapSelection(field, "**")) },
                    onItalic = { apply(wrapSelection(field, "*")) },
                    onLink = { apply(wrapSelection(field, "[[", "]]")) },
                    onTag = { apply(insertText(field, "#")) },
                    onCheckbox = { apply(toggleLinePrefix(field, "- [ ] ")) },
                    onList = { apply(toggleLinePrefix(field, "- ")) },
                    onImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onUndo = {
                        undo.removeLastOrNull()?.let { prev ->
                            field = prev
                            vm.updateEditorText(prev.text)
                        }
                    }
                )
            }
        }
    }

    actions.Host(vm, remember(notes) { folderPaths(notes) })

    if (showPanel) {
        NotePanel(
            vm,
            tasksVm,
            state.path,
            state.text,
            onJump = { line ->
                if (preview) {
                    headingY[line]?.let { y -> scope.launch { previewScroll.animateScrollTo(y) } }
                } else {
                    field = field.copy(selection = TextRange(lineStartOffset(field.text, line)))
                    focus.requestFocus()
                }
            },
            onDismiss = { showPanel = false }
        )
    }
}

private fun Modifier.topLine() = drawBehind { drawLine(Sc.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }

// "projects / scrinium / " dimmed, then the file name.
@Composable
private fun Crumbs(path: String) {
    val dir = path.substringBeforeLast('/', "")
    Text(
        buildAnnotatedString {
            if (dir.isNotBlank()) withStyle(SpanStyle(color = Sc.text3)) { append(dir.replace("/", " / ") + " / ") }
            withStyle(SpanStyle(color = Sc.text2)) { append(path.substringAfterLast('/').removeSuffix(".md")) }
        },
        style = Type.body.copy(fontWeight = FontWeight.Medium, fontSize = Type.meta.fontSize * 1.1f),
        maxLines = 1,
        overflow = TextOverflow.StartEllipsis
    )
}

@Composable
private fun FormatBar(
    canUndo: Boolean,
    uploading: Boolean,
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onLink: () -> Unit,
    onTag: () -> Unit,
    onCheckbox: () -> Unit,
    onList: () -> Unit,
    onImage: () -> Unit,
    onUndo: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(46.dp).topLine().background(Sc.bg),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBtn(R.drawable.ms_format_bold, "Bold", onBold)
        IconBtn(R.drawable.ms_format_italic, "Italic", onItalic)
        IconBtn(R.drawable.ms_data_array, "Link a note", onLink)
        IconBtn(R.drawable.ms_tag, "Tag", onTag)
        IconBtn(R.drawable.ms_check_box, "Checkbox", onCheckbox)
        IconBtn(R.drawable.ms_format_list_bulleted, "List", onList)
        IconBtn(R.drawable.ms_image, if (uploading) "Uploading image" else "Insert image", { if (!uploading) onImage() }, tint = if (uploading) Sc.accent else Sc.text2)
        IconBtn(R.drawable.ms_undo, "Undo", { if (canUndo) onUndo() }, tint = if (canUndo) Sc.text2 else Sc.text4)
    }
}
