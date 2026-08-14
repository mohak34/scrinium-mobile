package dev.mohak.scrinium.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
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
    Text(
        text = renderer.render(markdown),
        modifier = modifier,
        color = colors.onSurface,
        fontSize = 15.sp,
        lineHeight = 25.5.sp
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
                    appendBlockquote(quote)
                    append("\n\n")
                }
                listMarker(trimmed) != null -> {
                    val items = mutableListOf<Pair<String, String>>()
                    while (i < lines.size && listMarker(lines[i].trimStart()) != null) {
                        val (marker, rest) = listMarker(lines[i].trimStart())!!
                        items += marker to rest
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

    private fun AnnotatedString.Builder.appendList(items: List<Pair<String, String>>) {
        items.forEachIndexed { index, (marker, text) ->
            val prefix = when (marker) {
                "bullet" -> "\u2022  "
                "checked" -> "\u2611  "
                "unchecked" -> "\u2610  "
                else -> ""
            }
            val style = SpanStyle(
                color = if (marker == "checked") onSurfaceVariant else onSurface
            )
            if (prefix.isNotEmpty()) withStyle(style) { append(prefix) }
            appendInlineStyled(text, base = style)
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

    private fun AnnotatedString.Builder.appendInlineStyled(text: String, base: SpanStyle = SpanStyle()) {
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