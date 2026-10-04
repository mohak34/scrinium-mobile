package dev.mohak.scrinium.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

// Live-preview editing like the web editor: markdown is styled in place and
// its marks (`**`, `[[`, `# `...) are hidden on every line except the ones
// holding the cursor, where they show dimmed so they can be edited. The text
// itself never changes; only how it is drawn.

enum class LiveKind { Heading1, Heading2, Heading3, Heading4, Heading5, Heading6, Bold, Italic, Code, Strike, Highlight, Link, Tag, Marker, CodeBlock, Meta }

/** A styled range in the visible (transformed) text. */
data class LiveStyle(val kind: LiveKind, val start: Int, val end: Int)

/**
 * The visible text with hidden marks removed, plus both offset maps:
 * [origToVis] has text.length + 1 entries, [visToOrig] visible.length + 1.
 */
class LiveLayout(val visible: String, val origToVis: IntArray, val visToOrig: IntArray, val styles: List<LiveStyle>)

private val inlineRules: List<Pair<Regex, LiveKind>> = listOf(
    Regex("`([^`\n]+)`") to LiveKind.Code,
    Regex("""(?<!!)\[\[([^\]\[\n|#]+)(?:#[^\]|\n]*)?(?:\|([^\]\n]+))?]]""") to LiveKind.Link,
    Regex("""(?<![!\]])\[([^\]\n]+)]\(([^)\s]+)\)""") to LiveKind.Link,
    Regex("""\*\*([^*\n]+)\*\*""") to LiveKind.Bold,
    Regex("""(?<![*\w])\*([^*\n]+)\*(?![*\w])""") to LiveKind.Italic,
    Regex("~~([^~\n]+)~~") to LiveKind.Strike,
    Regex("==([^=\n]+)==") to LiveKind.Highlight,
)
private val tagRe = Regex("""(?<!\S)#[A-Za-z][\w/-]*""")
private val headingRe = Regex("""^(#{1,6})\s+""")
private val listRe = Regex("""^\s*(?:[-*+]|\d+[.)])\s+(?:\[[ xX]]\s+)?""")
private val quoteRe = Regex("""^\s*>\s?""")
private val fenceRe = Regex("""^\s*(`{3,}|~{3,})""")

/**
 * Lays out [text] for live preview. Lines overlapping [revealFrom, revealTo]
 * (the selection) keep their marks; pass -1 for both to hide marks everywhere.
 */
fun liveLayout(text: String, revealFrom: Int, revealTo: Int): LiveLayout {
    val hidden = BooleanArray(text.length)
    val styles = mutableListOf<Pair<LiveKind, IntRange>>() // original coordinates, end exclusive

    fun mark(range: IntRange, reveal: Boolean) {
        if (range.isEmpty()) return
        if (reveal) styles += LiveKind.Marker to range else for (i in range) hidden[i] = true
    }

    var lineStart = 0
    var inFence = false
    var inMeta = false
    var lineNo = 0
    while (lineStart <= text.length) {
        val nl = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, nl)
        val reveal = revealFrom >= 0 && revealFrom <= nl && revealTo >= lineStart
        val whole = lineStart until nl

        when {
            lineNo == 0 && line.trim() == "---" -> {
                inMeta = true
                styles += LiveKind.Meta to whole
            }
            inMeta -> {
                styles += LiveKind.Meta to whole
                if (line.trim() == "---" || line.trim() == "...") inMeta = false
            }
            fenceRe.containsMatchIn(line) -> {
                inFence = !inFence
                styles += LiveKind.Marker to whole
            }
            inFence -> styles += LiveKind.CodeBlock to whole
            else -> {
                var contentFrom = 0
                headingRe.find(line)?.let { m ->
                    val level = m.groupValues[1].length
                    mark(lineStart until lineStart + m.range.last + 1, reveal)
                    styles += LiveKind.entries[level - 1] to (lineStart + m.range.last + 1 until nl)
                    contentFrom = m.range.last + 1
                }
                if (contentFrom == 0) {
                    (listRe.find(line) ?: quoteRe.find(line))?.let { m ->
                        styles += LiveKind.Marker to (lineStart until lineStart + m.range.last + 1)
                        contentFrom = m.range.last + 1
                    }
                }
                val claimed = BooleanArray(line.length)
                for ((re, kind) in inlineRules) {
                    for (m in re.findAll(line, contentFrom)) {
                        if ((m.range).any { claimed[it] }) continue
                        for (i in m.range) claimed[i] = true
                        // Shown part: the alias for `[[t|alias]]`, else group 1.
                        val alias = if (m.value.startsWith("[[")) m.groups[2] else null
                        val shown = alias ?: m.groups[1]!!
                        val s = lineStart + shown.range.first
                        val e = lineStart + shown.range.last + 1
                        mark(lineStart + m.range.first until s, reveal)
                        mark(e until lineStart + m.range.last + 1, reveal)
                        styles += kind to (s until e)
                    }
                }
                for (m in tagRe.findAll(line, contentFrom)) {
                    if (m.range.any { claimed[it] }) continue
                    styles += LiveKind.Tag to (lineStart + m.range.first until lineStart + m.range.last + 1)
                }
            }
        }
        lineStart = nl + 1
        lineNo++
    }

    val origToVis = IntArray(text.length + 1)
    val visible = StringBuilder(text.length)
    for (i in text.indices) {
        origToVis[i] = visible.length
        if (!hidden[i]) visible.append(text[i])
    }
    origToVis[text.length] = visible.length
    val visToOrig = IntArray(visible.length + 1)
    for (i in text.indices) if (!hidden[i]) visToOrig[origToVis[i]] = i
    visToOrig[visible.length] = text.length

    return LiveLayout(
        visible.toString(),
        origToVis,
        visToOrig,
        styles.mapNotNull { (k, r) ->
            val s = origToVis[r.first]
            val e = origToVis[r.last + 1]
            if (e > s) LiveStyle(k, s, e) else null
        }
    )
}

/** Colors the live preview draws with. */
data class LivePalette(val accent: Color, val muted: Color, val codeBackground: Color, val highlight: Color)

/**
 * The editor's visual transformation. A data class so Compose reuses it
 * until the text, the revealed range or the colors change.
 */
data class LiveMarkdownTransformation(val revealFrom: Int, val revealTo: Int, val palette: LivePalette) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val layout = liveLayout(text.text, revealFrom, revealTo)
        val styled = AnnotatedString.Builder(layout.visible).apply {
            for (s in layout.styles) addStyle(spanFor(s.kind), s.start, s.end)
        }.toAnnotatedString()
        return TransformedText(styled, object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = layout.origToVis[offset.coerceIn(0, layout.origToVis.size - 1)]
            override fun transformedToOriginal(offset: Int) = layout.visToOrig[offset.coerceIn(0, layout.visToOrig.size - 1)]
        })
    }

    private fun spanFor(kind: LiveKind): SpanStyle = when (kind) {
        LiveKind.Heading1 -> SpanStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        LiveKind.Heading2 -> SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        LiveKind.Heading3 -> SpanStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        LiveKind.Heading4, LiveKind.Heading5, LiveKind.Heading6 -> SpanStyle(fontWeight = FontWeight.SemiBold)
        LiveKind.Bold -> SpanStyle(fontWeight = FontWeight.SemiBold)
        LiveKind.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
        LiveKind.Code -> SpanStyle(fontFamily = FontFamily.Monospace, background = palette.codeBackground)
        LiveKind.Strike -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        LiveKind.Highlight -> SpanStyle(background = palette.highlight)
        LiveKind.Link -> SpanStyle(color = palette.accent)
        LiveKind.Tag -> SpanStyle(color = palette.accent)
        LiveKind.Marker -> SpanStyle(color = palette.muted)
        LiveKind.CodeBlock -> SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        LiveKind.Meta -> SpanStyle(color = palette.muted, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}
