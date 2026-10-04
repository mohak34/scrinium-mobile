package dev.mohak.scrinium.ui

// Read-only facts about a note's text for its info panel: outline,
// frontmatter properties and word count. Ports of the web's outline.ts,
// propDisplayRows and the status bar word count.

/** A heading with its 0-based line in the note. */
data class OutlineEntry(val level: Int, val text: String, val line: Int)

private val fenceRe = Regex("""^\s*(`{3,}|~{3,})""")
private val headingRe = Regex("""^(#{1,6})\s+(.+?)\s*#*\s*$""")

/** ATX headings, skipping fenced code and a leading frontmatter block. */
fun parseOutline(text: String): List<OutlineEntry> {
    val out = mutableListOf<OutlineEntry>()
    val lines = text.split('\n')
    var inFence = false
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (i == 0 && line.trimEnd() == "---") {
            var j = 1
            while (j < lines.size && lines[j].trimEnd() != "---" && lines[j].trimEnd() != "...") j++
            i = j + 1
            continue
        }
        if (fenceRe.containsMatchIn(line)) {
            inFence = !inFence
        } else if (!inFence) {
            headingRe.matchEntire(line)?.let { m ->
                val heading = m.groupValues[2].trim()
                if (heading.isNotEmpty()) out += OutlineEntry(m.groupValues[1].length, heading, i)
            }
        }
        i++
    }
    return out
}

/** Offset of the start of 0-based [line] in [text], clamped to the end. */
fun lineStartOffset(text: String, line: Int): Int {
    var offset = 0
    repeat(line) {
        val next = text.indexOf('\n', offset)
        if (next < 0) return text.length
        offset = next + 1
    }
    return offset
}

/** One frontmatter key with its value flattened for display. */
data class PropertyRow(val key: String, val value: String)

private val keyLineRe = Regex("""^([A-Za-z0-9_-][^:]*):\s*(.*)$""")

/**
 * Top-level frontmatter keys. Handles the shapes notes actually use: scalars,
 * `[a, b]` lists and `- item` block lists. Anything nested shows as its raw
 * indented text joined on one line.
 */
fun frontmatterProperties(content: String): List<PropertyRow> {
    val lines = content.split('\n')
    if (lines.isEmpty() || lines[0].trim() != "---") return emptyList()
    val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" || lines[it].trim() == "..." }
        ?: return emptyList()
    val rows = mutableListOf<PropertyRow>()
    var key: String? = null
    var inline = ""
    val nested = mutableListOf<String>()

    fun flush() {
        val k = key ?: return
        val value = when {
            inline.isNotEmpty() -> unquote(inline).let { v ->
                if (v.startsWith('[') && v.endsWith(']')) {
                    v.drop(1).dropLast(1).split(',').map { unquote(it.trim()) }.filter { it.isNotEmpty() }.joinToString(", ")
                } else v
            }
            nested.all { it.startsWith("- ") } -> nested.joinToString(", ") { unquote(it.removePrefix("- ").trim()) }
            else -> nested.joinToString(" ")
        }
        rows += PropertyRow(k, value)
    }

    for (raw in lines.subList(1, end)) {
        if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
        val m = if (raw.first().isWhitespace() || raw.startsWith("- ")) null else keyLineRe.matchEntire(raw)
        if (m != null) {
            flush()
            key = m.groupValues[1].trim()
            inline = m.groupValues[2].trim()
            nested.clear()
        } else if (key != null) {
            nested += raw.trim()
        }
    }
    flush()
    return rows
}

private fun unquote(s: String): String =
    if (s.length >= 2 && (s.startsWith('"') && s.endsWith('"') || s.startsWith('\'') && s.endsWith('\''))) {
        s.substring(1, s.length - 1)
    } else s

private val fencedCodeRe = Regex("```[\\s\\S]*?(```|$)")
private val wordRe = Regex("""[\p{L}\p{N}']+""")

/** Body words only: frontmatter and fenced code are not prose. */
fun wordCount(content: String): Int {
    val lines = content.split('\n')
    val body = if (lines.firstOrNull()?.trim() == "---") {
        val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" || lines[it].trim() == "..." }
        if (end == null) content else lines.drop(end + 1).joinToString("\n")
    } else content
    return wordRe.findAll(fencedCodeRe.replace(body, " ")).count()
}
