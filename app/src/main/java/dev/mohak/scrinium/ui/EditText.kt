package dev.mohak.scrinium.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

// Editor formatting bar actions, as pure edits of the field value.

/** Wraps the selection in [open]/[close]; with no selection, puts the cursor between them. */
fun wrapSelection(v: TextFieldValue, open: String, close: String = open): TextFieldValue {
    val s = v.selection.min
    val e = v.selection.max
    val text = v.text.substring(0, s) + open + v.text.substring(s, e) + close + v.text.substring(e)
    return if (s == e) TextFieldValue(text, TextRange(s + open.length))
    else TextFieldValue(text, TextRange(s + open.length, e + open.length))
}

/** Replaces the selection with [s] and puts the cursor after it. */
fun insertText(v: TextFieldValue, s: String): TextFieldValue {
    val a = v.selection.min
    return TextFieldValue(v.text.substring(0, a) + s + v.text.substring(v.selection.max), TextRange(a + s.length))
}

private val listPrefix = Regex("""^(\s*)(- \[[ xX]] |[-*+] |\d+[.)] )?""")

/**
 * Sets the cursor line's list marker to [prefix] ("- " or "- [ ] "), or
 * removes it when the line already has exactly that marker. Indentation
 * stays; the cursor keeps its place in the text.
 */
fun toggleLinePrefix(v: TextFieldValue, prefix: String): TextFieldValue {
    val cursor = v.selection.start
    val lineStart = v.text.lastIndexOf('\n', cursor - 1) + 1
    val lineEnd = v.text.indexOf('\n', cursor).let { if (it < 0) v.text.length else it }
    val line = v.text.substring(lineStart, lineEnd)
    val m = listPrefix.find(line)!!
    val indent = m.groupValues[1]
    val old = m.groupValues[2]
    val isSame = if (prefix == "- [ ] ") old.startsWith("- [") else old == prefix
    val replacement = if (isSame) "" else prefix
    val newLine = indent + replacement + line.substring(m.range.last + 1)
    val delta = replacement.length - old.length
    val text = v.text.substring(0, lineStart) + newLine + v.text.substring(lineEnd)
    val markEnd = lineStart + indent.length + old.length
    val newCursor = if (cursor >= markEnd) cursor + delta else lineStart + indent.length + replacement.length
    return TextFieldValue(text, TextRange(newCursor.coerceIn(0, text.length)))
}
