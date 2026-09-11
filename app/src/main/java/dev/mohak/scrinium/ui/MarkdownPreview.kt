package dev.mohak.scrinium.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val wikilinkPattern = Regex("""\[\[([^|\]]+)(?:\|([^\]]+))?]]""")
private val tagPattern = Regex("""(?<!\S)#([A-Za-z][A-Za-z0-9_-]*(?:/[A-Za-z][A-Za-z0-9_-]*)*)""")
private val calloutPattern = Regex("""\[!(\w+)](.*)""")

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    onWikilinkClick: (String) -> Unit = {},
    onToggleTaskLine: (Int) -> Unit = {},
    onTagClick: (String) -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val renderer = remember(colors) {
        MarkdownRenderer(
            primary = colors.primary,
            onSurface = colors.onSurface,
            onSurfaceVariant = colors.onSurfaceVariant,
            codeBackground = colors.surfaceContainerHigh,
            outline = colors.outline
        )
    }
    val rendered = remember(markdown, renderer) { renderer.render(markdown) }
    ClickableText(
        text = rendered,
        modifier = modifier,
        style = androidx.compose.ui.text.TextStyle(
            color = colors.onSurface,
            fontSize = 15.sp,
            lineHeight = 25.5.sp
        ),
        onClick = { offset ->
            val annotations = rendered.getStringAnnotations(start = offset, end = offset)
            annotations.firstOrNull { it.tag == "toggle" }?.let {
                it.item.toIntOrNull()?.let(onToggleTaskLine)
                return@ClickableText
            }
            annotations.firstOrNull { it.tag == "wikilink" }?.let {
                onWikilinkClick(it.item)
                return@ClickableText
            }
            annotations.firstOrNull { it.tag == "tag" }?.let {
                onTagClick(it.item)
            }
        }
    )
}

private class MarkdownRenderer(
    private val primary: Color,
    private val onSurface: Color,
    private val onSurfaceVariant: Color,
    private val codeBackground: Color,
    private val outline: Color
) {
    fun render(raw: String): AnnotatedString = buildAnnotatedString {
        val lines = raw.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") -> {
                    val code = buildString {
                        i++
                        while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                            append(lines[i])
                            append('\n')
                            i++
                        }
                    }
                    appendCodeBlock(code.trimEnd('\n'))
                    append("\n\n")
                    i++
                }
                trimmed.startsWith("#") -> {
                    val level = trimmed.takeWhile { it == '#' }.length
                    if (level in 1..6) {
                        appendHeading(level, trimmed.drop(level).trim())
                        append("\n\n")
                    } else {
                        appendParagraph(listOf(trimmed))
                        append("\n\n")
                    }
                    i++
                }
                trimmed.startsWith("---") || trimmed.startsWith("***") || trimmed.startsWith("___") -> {
                    appendHorizontalRule()
                    append("\n\n")
                    i++
                }
                trimmed.startsWith(">") -> {
                    val quote = mutableListOf<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        quote += lines[i].trimStart().drop(1).trimStart()
                        i++
                    }
                    val callout = quote.firstOrNull()?.let { calloutPattern.matchEntire(it.trim()) }
                    if (callout != null) {
                        appendCallout(callout.groupValues[1], callout.groupValues[2].trim(), quote.drop(1))
                    } else {
                        appendBlockquote(quote)
                    }
                    append("\n\n")
                }
                listMarker(trimmed) != null -> {
                    val items = mutableListOf<Triple<String, String, Int>>()
                    while (i < lines.size && listMarker(lines[i].trimStart()) != null) {
                        val (marker, rest) = listMarker(lines[i].trimStart())!!
                        items += Triple(marker, rest, i)
                        i++
                    }
                    appendList(items)
                    append("\n\n")
                }
                trimmed.isBlank() -> {
                    i++
                }
                else -> {
                    val paragraph = mutableListOf<String>()
                    while (i < lines.size && !isBlockStart(lines[i].trimStart()) && lines[i].isNotBlank()) {
                        paragraph += lines[i].trim()
                        i++
                    }
                    appendParagraph(paragraph)
                    append("\n\n")
                }
            }
        }
    }

    private fun isBlockStart(line: String): Boolean =
        line.startsWith("#") || line.startsWith("```") || line.startsWith(">") ||
            line.startsWith("---") || line.startsWith("***") || line.startsWith("___") ||
            listMarker(line) != null

    private fun listMarker(line: String): Pair<String, String>? {
        return when {
            line.startsWith("- [x]") || line.startsWith("- [X]") -> "checked" to line.drop(5).trim()
            line.startsWith("- [ ]") -> "unchecked" to line.drop(5).trim()
            line.startsWith("- ") -> "bullet" to line.drop(2).trim()
            line.startsWith("* ") -> "bullet" to line.drop(2).trim()
            line.startsWith("+ ") -> "bullet" to line.drop(2).trim()
            line.matches(Regex("""\d+[.)]\s.*""")) -> "ordered" to line
            else -> null
        }
    }

    private fun AnnotatedString.Builder.appendHeading(level: Int, text: String) {
        val size = when (level) {
            1 -> 24.sp
            2 -> 20.sp
            3 -> 17.sp
            else -> 15.sp
        }
        val style = SpanStyle(
            fontSize = size,
            fontWeight = FontWeight.Medium,
            color = onSurface
        )
        appendInlineStyled(text, base = style)
    }

    private fun AnnotatedString.Builder.appendParagraph(lines: List<String>) {
        lines.forEachIndexed { index, line ->
            appendInlineStyled(line)
            if (index != lines.lastIndex) append('\n')
        }
    }

    private fun AnnotatedString.Builder.appendBlockquote(lines: List<String>) {
        val style = SpanStyle(
            fontStyle = FontStyle.Italic,
            color = onSurfaceVariant
        )
        lines.forEachIndexed { index, line ->
            withStyle(style) { append("\u258D ") }
            appendInlineStyled(line, base = style)
            if (index != lines.lastIndex) append('\n')
        }
    }

    private fun AnnotatedString.Builder.appendList(items: List<Triple<String, String, Int>>) {
        items.forEachIndexed { index, (marker, text, lineIdx) ->
            val prefix = when (marker) {
                "bullet" -> "\u2022  "
                "checked" -> "\u2611  "
                "unchecked" -> "\u2610  "
                else -> ""
            }
            val style = SpanStyle(
                color = if (marker == "checked") onSurfaceVariant else onSurface
            )
            val toggleable = marker == "checked" || marker == "unchecked"
            if (toggleable) pushStringAnnotation(tag = "toggle", annotation = lineIdx.toString())
            if (prefix.isNotEmpty()) withStyle(style) { append(prefix) }
            appendInlineStyled(text, base = style)
            if (toggleable) pop()
            if (index != items.lastIndex) append('\n')
        }
    }

    private fun AnnotatedString.Builder.appendCodeBlock(code: String) {
        val style = SpanStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            background = codeBackground,
            color = onSurface
        )
        withStyle(style) { append(code) }
    }

    private fun AnnotatedString.Builder.appendHorizontalRule() {
        val style = SpanStyle(color = outline)
        withStyle(style) {
            append("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500")
        }
    }

    private fun AnnotatedString.Builder.appendCallout(kind: String, title: String, lines: List<String>) {
        val labelStyle = SpanStyle(fontWeight = FontWeight.SemiBold, color = primary)
        withStyle(labelStyle) { append(kind.uppercase()) }
        if (title.isNotBlank()) {
            withStyle(SpanStyle(color = onSurfaceVariant)) { append("  $title") }
        }
        for (line in lines) {
            append('\n')
            appendInlineStyled(line, base = SpanStyle(color = onSurfaceVariant))
        }
    }

    private fun AnnotatedString.Builder.appendInlineStyled(text: String, base: SpanStyle = SpanStyle()) {
        // Wikilinks first: annotated spans the tap handler resolves.
        var rest = text
        while (true) {
            val m = wikilinkPattern.find(rest) ?: break
            if (m.range.first > 0) appendInlineTags(rest.substring(0, m.range.first), base)
            val target = m.groupValues[1].trim()
            val label = m.groupValues[2].ifBlank { target }
            pushStringAnnotation(tag = "wikilink", annotation = target)
            withStyle(
                base.merge(SpanStyle(textDecoration = TextDecoration.Underline, color = primary))
            ) { append(label) }
            pop()
            rest = rest.substring(m.range.last + 1)
        }
        appendInlineTags(rest, base)
    }

    private fun AnnotatedString.Builder.appendInlineTags(text: String, base: SpanStyle) {
        // Tag pills: primary on tinted background, tappable to browse.
        var rest = text
        while (true) {
            val m = tagPattern.find(rest) ?: break
            if (m.range.first > 0) appendInlineBasic(rest.substring(0, m.range.first), base)
            pushStringAnnotation(tag = "tag", annotation = m.groupValues[1])
            withStyle(
                base.merge(SpanStyle(color = primary, background = codeBackground))
            ) { append(m.value) }
            pop()
            rest = rest.substring(m.range.last + 1)
        }
        appendInlineBasic(rest, base)
    }

    private fun AnnotatedString.Builder.appendInlineBasic(text: String, base: SpanStyle = SpanStyle()) {
        var i = 0
        while (i < text.length) {
            val rest = text.substring(i)
            when {
                rest.startsWith("**") && rest.indexOf("**", 2) > 0 -> {
                    val end = rest.indexOf("**", 2)
                    val style = base.merge(SpanStyle(fontWeight = FontWeight.SemiBold))
                    withStyle(style) { append(rest.substring(2, end)) }
                    i += end + 2
                }
                rest.startsWith("[") && rest.indexOf("](") > 0 && rest.indexOf(")") > rest.indexOf("](") -> {
                    val close = rest.indexOf("](")
                    val linkEnd = rest.indexOf(")", close + 2)
                    val style = base.merge(
                        SpanStyle(
                            textDecoration = TextDecoration.Underline,
                            color = primary
                        )
                    )
                    withStyle(style) { append(rest.substring(1, close)) }
                    i += linkEnd + 1
                }
                rest.startsWith("`") && rest.indexOf('`', 1) > 0 -> {
                    val end = rest.indexOf('`', 1)
                    val style = base.merge(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBackground
                        )
                    )
                    withStyle(style) { append(rest.substring(1, end)) }
                    i += end + 1
                }
                rest.startsWith("*") && rest.indexOf('*', 1) > 0 -> {
                    val end = rest.indexOf('*', 1)
                    val style = base.merge(SpanStyle(fontStyle = FontStyle.Italic))
                    withStyle(style) { append(rest.substring(1, end)) }
                    i += end + 1
                }
                else -> {
                    val next = listOf('*', '`', '[').map { rest.indexOf(it, 1) }.filter { it >= 0 }.minOrNull()
                    val end = next ?: rest.length
                    withStyle(base) { append(rest.substring(0, end)) }
                    i += end
                }
            }
        }
    }
}