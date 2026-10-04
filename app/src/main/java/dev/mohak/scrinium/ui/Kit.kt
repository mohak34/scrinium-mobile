package dev.mohak.scrinium.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.mohak.scrinium.R

// The app's small component kit, drawn to the web's tokens instead of
// Material's defaults: flat rows, thin lines, no cards.

/** A Material Symbols icon from res/drawable (ms_*). */
@Composable
fun Sym(icon: Int, modifier: Modifier = Modifier, tint: Color = Sc.text2, size: Dp = 20.dp, desc: String? = null) {
    Icon(painterResource(icon), contentDescription = desc, tint = tint, modifier = modifier.size(size))
}

@Composable
fun IconBtn(icon: Int, desc: String, onClick: () -> Unit, tint: Color = Sc.text2, active: Boolean = false) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (active) Sc.hover else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Sym(icon, tint = tint, desc = desc)
    }
}

/** 48dp top bar. [onBack] adds a back arrow; [title] can be replaced by [titleContent]. */
@Composable
fun TopBar(
    title: String = "",
    onBack: (() -> Unit)? = null,
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(start = if (onBack == null) 16.dp else 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) IconBtn(R.drawable.ms_arrow_back, "Back", onBack)
        Row(
            modifier = Modifier.weight(1f).padding(start = if (onBack == null) 0.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (titleContent != null) titleContent()
            else Text(title, style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

/** Text tabs with an accent underline, used for filters and sheet sections. */
@Composable
fun TextTabs(labels: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(Sc.line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
            }
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Text(
                label,
                style = Type.body.copy(fontWeight = FontWeight.Medium, color = if (on) Sc.text else Sc.text3),
                modifier = Modifier
                    .clickable { onSelect(i) }
                    .drawBehind {
                        if (on) drawRect(Sc.accent, Offset(0f, size.height - 2.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx()))
                    }
                    .padding(vertical = 10.dp)
            )
        }
    }
}

/** Group heading: white title, mono count on the right. */
@Composable
fun GroupHeader(title: String, count: String? = null, color: Color = Sc.text, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = Type.group.copy(color = color), modifier = Modifier.weight(1f))
        if (count != null) Text(count, style = Type.mono)
    }
}

/** The square new-item button, bottom right. */
@Composable
fun Fab(icon: Int, desc: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(16.dp)
            .size(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Sc.fill)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Sym(icon, tint = Sc.onFill, size = 22.dp, desc = desc)
    }
}

/** Icon + label row for menus and sheets. */
@Composable
fun MenuRow(icon: Int?, label: String, onClick: () -> Unit, danger: Boolean = false, tint: Color? = null, trailing: (@Composable () -> Unit)? = null) {
    val color = if (danger) Sc.red else Sc.text
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Sym(icon, tint = tint ?: if (danger) Sc.red else Sc.text2, size = 18.dp)
            Spacer(Modifier.width(16.dp))
        }
        Text(label, style = Type.body.copy(color = color), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke()
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Sc.line))
}

/** Dropdown menu in the app's colors. */
@Composable
fun Menu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = Sc.raise,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Sc.line2),
        modifier = Modifier.width(220.dp)
    ) {
        Column { content() }
    }
}

/** Bottom sheet with the app's surface and a small grab handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onDismiss: () -> Unit, skipPartial: Boolean = true, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartial),
        containerColor = Sc.raise,
        contentColor = Sc.text,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 8.dp, bottom = 6.dp)
                    .size(36.dp, 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Sc.line3)
            )
        }
    ) {
        Column(Modifier.padding(bottom = 12.dp)) { content() }
    }
}

/** Title + subtitle block at the top of a sheet. */
@Composable
fun SheetTitle(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(title, style = Type.group.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = Type.meta, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun FillButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) Sc.fill else Sc.press)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = Type.body.copy(fontWeight = FontWeight.SemiBold, color = if (enabled) Sc.onFill else Sc.text3))
    }
}

@Composable
fun GhostButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Sc.line2, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = Type.body.copy(fontWeight = FontWeight.SemiBold, color = Sc.text2))
    }
}

/** Plain text action: accent by default, red for destructive ones. */
@Composable
fun TextAction(label: String, onClick: () -> Unit, danger: Boolean = false, enabled: Boolean = true) {
    Text(
        label,
        style = Type.body.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = when {
                !enabled -> Sc.text4
                danger -> Sc.red
                else -> Sc.accent
            }
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    )
}

/** Single text box with an optional leading icon; the border turns accent on focus. */
@Composable
fun InputBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    singleLine: Boolean = true,
    minHeight: Dp = 44.dp,
    style: TextStyle = Type.body,
    focusRequester: FocusRequester? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: (@Composable () -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = style,
        cursorBrush = SolidColor(Sc.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .border(1.dp, if (focused) Sc.accent else Sc.line2, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top
            ) {
                if (icon != null) {
                    Sym(icon, tint = Sc.text3, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = style.copy(color = Sc.text3))
                    inner()
                }
                trailing?.invoke()
            }
        }
    )
}

/** Small segmented options; the selected one is filled. */
@Composable
fun <T> Segments(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit, lead: (@Composable (T) -> Unit)? = null) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { o ->
            val on = o == selected
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) Sc.fill else Color.Transparent)
                    .border(1.dp, if (on) Sc.fill else Sc.line2, RoundedCornerShape(6.dp))
                    .clickable { onSelect(o) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                lead?.invoke(o)
                Text(label(o), style = Type.body.copy(fontSize = 13.sp, color = if (on) Sc.onFill else Sc.text2))
            }
        }
    }
}

@Composable
fun Dot(color: Color, size: Dp = 7.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** Confirm dialog: title, one line of text, Cancel and a (red when [danger]) action. */
@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = true) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Sc.raise)
                .border(1.dp, Sc.line2, RoundedCornerShape(14.dp))
                .padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp)
        ) {
            Text(title, style = Type.title.copy(fontSize = 16.sp))
            Spacer(Modifier.height(8.dp))
            Text(text, style = Type.body.copy(color = Sc.text2), modifier = Modifier.padding(end = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                TextAction("Cancel", onDismiss)
                TextAction(confirm, { onDismiss(); onConfirm() }, danger = danger)
            }
        }
    }
}

/** Dialog with one text field, for renames and new folders. */
@Composable
fun PromptDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit, placeholder: String = "") {
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Sc.raise)
                .border(1.dp, Sc.line2, RoundedCornerShape(14.dp))
                .padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp)
        ) {
            Text(title, style = Type.title.copy(fontSize = 16.sp))
            Spacer(Modifier.height(12.dp))
            InputBox(
                value,
                { value = it },
                placeholder,
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                focusRequester = focus,
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) { onDismiss(); onConfirm(value) } })
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                TextAction("Cancel", onDismiss)
                TextAction(confirm, { onDismiss(); onConfirm(value) }, enabled = value.isNotBlank())
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
}

/** Dismissable error strip under the top bar. */
@Composable
fun ErrorStrip(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Sc.redFill)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(message, style = Type.body.copy(fontSize = 13.sp, color = Sc.red), modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        IconBtn(R.drawable.ms_close, "Dismiss", onDismiss, tint = Sc.red)
    }
}

/** Centered icon + line for empty screens. */
@Composable
fun EmptyState(icon: Int, title: String, hint: String? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Sym(icon, tint = Sc.text4, size = 40.dp)
        Spacer(Modifier.height(10.dp))
        Text(title, style = Type.body.copy(color = Sc.text2))
        if (hint != null) Text(hint, style = Type.meta, modifier = Modifier.padding(top = 2.dp, start = 32.dp, end = 32.dp))
    }
}

/** Round task checkbox: an outline when open, filled with a check when done. */
@Composable
fun TaskCheck(done: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(19.dp)
                .clip(CircleShape)
                .background(if (done) Sc.fill else Color.Transparent)
                .border(1.5.dp, if (done) Sc.fill else Sc.text3, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (done) Sym(R.drawable.ms_check, tint = Sc.onFill, size = 14.dp)
        }
    }
}

private data class TabSpec(val screen: Screen, val label: String, val icon: Int, val iconOn: Int)

private val Tabs = listOf(
    TabSpec(Screen.Notes, "Notes", R.drawable.ms_description, R.drawable.ms_description_fill),
    TabSpec(Screen.Tasks, "Tasks", R.drawable.ms_task_alt, R.drawable.ms_task_alt_fill),
    TabSpec(Screen.Search, "Search", R.drawable.ms_search, R.drawable.ms_search_fill),
    TabSpec(Screen.Board, "Board", R.drawable.ms_view_kanban, R.drawable.ms_view_kanban_fill),
    TabSpec(Screen.Calendar, "Calendar", R.drawable.ms_calendar_month, R.drawable.ms_calendar_month_fill)
)

/** The five-tab bar at the bottom of the top-level screens. */
@Composable
fun BottomTabs(selected: Screen, onSelect: (Screen) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Sc.bg)
            .drawBehind { drawLine(Sc.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .height(60.dp)
    ) {
        Tabs.forEach { t ->
            val on = t.screen == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(60.dp)
                    .clickable { onSelect(t.screen) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Sym(if (on) t.iconOn else t.icon, tint = if (on) Sc.accent else Sc.text3, size = 22.dp, desc = t.label)
                Spacer(Modifier.height(3.dp))
                Text(t.label, style = Type.meta.copy(fontSize = 11.sp, color = if (on) Sc.accent else Sc.text3))
            }
        }
    }
}
