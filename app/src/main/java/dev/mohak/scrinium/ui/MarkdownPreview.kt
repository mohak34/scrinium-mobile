package dev.mohak.scrinium.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
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
private val calloutPattern = Regex("""\[!([\w-]+)](.*)""")
// A line that is only an image: `![alt](url)` or `![alt](url "title")`.
private val imageLinePattern = Regex("""^!\[([^\]]*)]\(\s*(<[^>]+>|[^)\s]+)(?:\s+"[^"]*")?\s*\)$""")

// One parsed block. Text blocks carry a pre-rendered AnnotatedString so tap
// annotations (tasks, wikilinks, tags) survive the split into composables.
private sealed interface Block {
    data class Text(val content: AnnotatedString) : Block
    data class Heading(val level: Int, val content: AnnotatedString, val line: Int) : Block
    data class Code(val text: String) : Block
    data class Math(val text: String) : Block
    data class Quote(val content: AnnotatedString) : Block
    data class Callout(val kind: String, val title: String, val content: AnnotatedString?) : Block
    data class Image(val alt: String, val url: String) : Block
    data object Rule : Block
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    onWikilinkClick: (String) -> Unit = {},
    onToggleTaskLine: (Int) -> Unit = {},
    onTagClick: (String) -> Unit = {},
    // [imageBase] is what relative image references resolve against (the
    // note's path): a new base reloads every image.
    imageBase: String = "",
    loadImage: suspend (String) -> ImageBitmap? = { null },
    onHeadingPositioned: (line: Int, y: Int) -> Unit = { _, _ -> }
) {
    val renderer = remember {
        MarkdownRenderer(
            primary = Sc.accent,
            onSurface = Sc.text,
            onSurfaceVariant = Sc.text2,
            codeBackground = Sc.press,
            warning = Sc.orange,
            danger = Sc.red,
            muted = Sc.text3
        )
    }
    val blocks = remember(markdown) { renderer.parse(markdown) }
    fun handleTap(offset: Int, rendered: AnnotatedString) {
        val annotations = rendered.getStringAnnotations(start = offset, end = offset)
        annotations.firstOrNull { it.tag == "toggle" }?.let {
            it.item.toIntOrNull()?.let(onToggleTaskLine)
            return
        }
        annotations.firstOrNull { it.tag == "wikilink" }?.let {
            onWikilinkClick(it.item)
            return
        }
        annotations.firstOrNull { it.tag == "tag" }?.let {
            onTagClick(it.item)
        }
    }
    val bodyStyle = Type.read
    Column(modifier = modifier) {
        for (block in blocks) {
            when (block) {
                is Block.Text -> ClickableText(
                    text = block.content,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    style = bodyStyle,
                    onClick = { handleTap(it, block.content) }
                )
                is Block.Heading -> {
                    val size = when (block.level) {
                        1 -> 24.sp
                        2 -> 19.sp
                        3 -> 17.sp
                        else -> 16.sp
                    }
                    ClickableText(
                        text = block.content,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { onHeadingPositioned(block.line, it.positionInParent().y.toInt()) }
                            .padding(bottom = 12.dp),
                        style = bodyStyle.merge(
                            androidx.compose.ui.text.TextStyle(
                                fontSize = size,
                                lineHeight = size * 1.35f,
                                fontWeight = FontWeight.SemiBold
                            )
                        ),
                        onClick = { handleTap(it, block.content) }
                    )
                }
                is Block.Code -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Sc.raise)
                        .border(1.dp, Sc.line, RoundedCornerShape(6.dp))
                        .padding(12.dp)
                ) {
                    androidx.compose.material3.Text(
                        text = block.text,
                        style = bodyStyle.merge(
                            androidx.compose.ui.text.TextStyle(
                                fontFamily = MonoFont,
                                fontSize = 13.sp,
                                lineHeight = 20.sp
                            )
                        )
                    )
                }
                is Block.Math -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Sc.raise)
                        .padding(12.dp)
                ) {
                    androidx.compose.material3.Text(
                        text = block.text,
                        style = bodyStyle.merge(
                            androidx.compose.ui.text.TextStyle(
                                fontFamily = MonoFont,
                                fontStyle = FontStyle.Italic,
                                fontSize = 14.sp
                            )
                        )
                    )
                }
                is Block.Quote -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(Sc.line3)
                        )
                        ClickableText(
                            text = block.content,
                            modifier = Modifier.weight(1f),
                            style = bodyStyle.merge(
                                androidx.compose.ui.text.TextStyle(
                                    fontStyle = FontStyle.Italic,
                                    color = Sc.text2
                                )
                            ),
                            onClick = { handleTap(it, block.content) }
                        )
                    }
                }
                is Block.Callout -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                        .background(renderer.accentFor(block.kind).copy(alpha = 0.07f))
                ) {
                    val accent = renderer.accentFor(block.kind)
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(accent)
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(12.dp)
                    ) {
                        Row {
                            androidx.compose.material3.Text(
                                text = block.kind.replaceFirstChar { it.uppercase() },
                                style = Type.group,
                                color = accent
                            )
                            if (block.title.isNotBlank()) {
                                androidx.compose.material3.Text(
                                    text = "  ${block.title}",
                                    style = bodyStyle,
                                    color = Sc.text2
                                )
                            }
                        }
                        block.content?.let {
                            ClickableText(
                                text = it,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                style = bodyStyle.merge(
                                    androidx.compose.ui.text.TextStyle(color = Sc.text2)
                                ),
                                onClick = { offset -> handleTap(offset, it) }
                            )
                        }
                    }
                }
                is Block.Image -> MarkdownImage(block, imageBase, loadImage, Sc.text3)
                is Block.Rule -> HorizontalDivider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    color = Sc.line2
                )
            }
        }
    }
}

// Loads through the caller (auth + cache live there). Alt text stands in
// while loading and when the image can't be fetched.
@Composable
private fun MarkdownImage(block: Block.Image, base: String, loadImage: suspend (String) -> ImageBitmap?, muted: Color) {
    // Same `photo.png` in another note is another file: key on both, and
    // drop the old bitmap before the new one loads.
    val bitmap by produceState<ImageBitmap?>(null, base, block.url) {
        value = null
        value = loadImage(block.url)
    }
    val image = bitmap
    if (image == null) {
        androidx.compose.material3.Text(
            text = "[image: ${block.alt.ifBlank { block.url }}]",
            color = muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
    } else {
        Image(
            bitmap = image,
            contentDescription = block.alt.ifBlank { null },
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(6.dp))
        )
    }
}

private class MarkdownRenderer(
    private val primary: Color,
    private val onSurface: Color,
    private val onSurfaceVariant: Color,
    private val codeBackground: Color,
    private val warning: Color,
    private val danger: Color,
    private val muted: Color
) {
    // Kind colors mirror the web client (callouts.ts). Unknown kinds fall
    // back to note, same as canonicalCalloutType.
    fun accentFor(rawKind: String): Color = when (canonicalKind(rawKind)) {
        "tip", "success", "warning", "example" -> warning
        "failure" -> danger
        "quote" -> muted
        else -> primary
    }

    private fun canonicalKind(raw: String): String = when (raw.lowercase()) {
        "abstract", "summary", "tldr" -> "abstract"
        "info", "todo" -> "info"
        "tip", "hint", "important" -> "tip"
        "success", "check", "done" -> "success"
        "question", "help", "faq" -> "question"
        "warning", "caution", "attention" -> "warning"
        "failure", "fail", "missing", "danger", "error", "bug" -> "failure"
        "example" -> "example"
        "quote", "cite" -> "quote"
        else -> "note"
    }

    fun parse(raw: String): List<Block> {
        val out = mutableListOf<Block>()
        val lines = raw.lines()
        var i = 0
        // Frontmatter is metadata, not content — never preview it.
        if (lines.isNotEmpty() && lines[0].trim() == "---") {
            for (j in 1 until lines.size) {
                val t = lines[j].trim()
                if (t == "---" || t == "...") {
                    i = j + 1
                    break
                }
            }
        }
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") -> {
                    val lang = trimmed.drop(3).trim().lowercase()
                    val code = buildString {
                        i++
                        while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                            append(lines[i])
                            append('\n')
                            i++
                        }
                    }.trimEnd('\n')
                    if (lang == "math") out += Block.Math(code) else out += Block.Code(code)
                    i++
                }
                trimmed.startsWith("$$") -> {
                    val single = Regex("""^\$\$(.*)\$\$\s*$""").matchEntire(trimmed)
                    if (single != null) {
                        if (single.groupValues[1].isNotBlank()) out += Block.Math(single.groupValues[1].trim())
                        i++
                    } else {
                        val math = buildString {
                            append(trimmed.drop(2).trim())
                            i++
                            while (i < lines.size && !lines[i].trimStart().startsWith("$$")) {
                                append('\n')
                                append(lines[i].trim())
                                i++
                            }
                        }.trim().toString()
                        if (math.isNotBlank()) out += Block.Math(math)
                        i++
                    }
                }
                imageLinePattern.matches(trimmed.trimEnd()) -> {
                    val m = imageLinePattern.matchEntire(trimmed.trimEnd())!!
                    out += Block.Image(m.groupValues[1], m.groupValues[2])
                    i++
                }
                headingLevel(trimmed) != null -> {
                    val level = headingLevel(trimmed)!!
                    out += Block.Heading(level, inline(trimmed.drop(level).trim(), headingStyle(level)), i)
                    i++
                }
                trimmed.startsWith("---") || trimmed.startsWith("***") || trimmed.startsWith("___") -> {
                    out += Block.Rule
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
                        val body = quote.drop(1)
                        out += Block.Callout(
                            kind = callout.groupValues[1],
                            title = callout.groupValues[2].trim(),
                            content = if (body.isEmpty()) null else inline(body.joinToString("\n"))
                        )
                    } else {
                        out += Block.Quote(inline(quote.joinToString("\n")))
                    }
                }
                listMarker(trimmed) != null -> {
                    val rendered = buildAnnotatedString {
                        var first = true
                        while (i < lines.size && listMarker(lines[i].trimStart()) != null) {
                            val (marker, rest) = listMarker(lines[i].trimStart())!!
                            val lineIdx = i
                            i++
                            if (!first) append('\n')
                            first = false
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
                            appendInlineStyled(rest, base = style)
                            if (toggleable) pop()
                        }
                    }
                    out += Block.Text(rendered)
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
                    out += Block.Text(inline(paragraph.joinToString("\n")))
                }
            }
        }
        return out
    }

    private fun headingStyle(level: Int): SpanStyle = SpanStyle(color = onSurface)

    private fun inline(text: String, base: SpanStyle = SpanStyle()): AnnotatedString =
        buildAnnotatedString { appendInlineStyled(text, base = base) }

    // CommonMark: #s must be followed by a space. Without it `#tag` is a tag,
    // not a heading.
    private fun headingLevel(line: String): Int? {
        val level = line.takeWhile { it == '#' }.length
        if (level !in 1..6) return null
        if (line.getOrNull(level)?.isWhitespace() != true) return null
        return level
    }

    private fun isBlockStart(line: String): Boolean =
        imageLinePattern.matches(line.trimEnd()) || line.startsWith("```") || line.startsWith("$$") || line.startsWith(">") ||
            line.startsWith("---") || line.startsWith("***") || line.startsWith("___") ||
            headingLevel(line) != null || listMarker(line) != null

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
            if (m.range.first > 0) appendInlineMath(rest.substring(0, m.range.first), base)
            pushStringAnnotation(tag = "tag", annotation = m.groupValues[1])
            withStyle(
                base.merge(SpanStyle(color = primary, background = codeBackground))
            ) { append(m.value) }
            pop()
            rest = rest.substring(m.range.last + 1)
        }
        appendInlineMath(rest, base)
    }

    // Inline `$…$`: styled distinctly, not typeset (that needs a WebView and
    // is out of scope). Opening $ must not follow a space-adjacent pattern
    // that screams currency: `$` with no space after it, closing with no
    // space before it, same segment.
    private val inlineMathPattern = Regex("""\$(?!\s)([^$\n]+?)(?<!\s)\$""")

    private fun AnnotatedString.Builder.appendInlineMath(text: String, base: SpanStyle) {
        var rest = text
        while (true) {
            val m = inlineMathPattern.find(rest) ?: break
            if (m.range.first > 0) appendInlineBasic(rest.substring(0, m.range.first), base)
            withStyle(
                base.merge(
                    SpanStyle(
                        fontFamily = MonoFont,
                        fontStyle = FontStyle.Italic,
                        background = codeBackground
                    )
                )
            ) { append(m.groupValues[1]) }
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
                            fontFamily = MonoFont,
                            fontSize = 14.sp,
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
