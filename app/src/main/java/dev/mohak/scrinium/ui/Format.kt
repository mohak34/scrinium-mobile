package dev.mohak.scrinium.ui

import dev.mohak.scrinium.data.sanitizePathSegment
import java.util.concurrent.TimeUnit

fun relativeTime(epoch: Long, now: Long = System.currentTimeMillis()): String {
    val diff = now - epoch
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "just now"
        diff < TimeUnit.HOURS.toMillis(1) -> "${diff / TimeUnit.MINUTES.toMillis(1)}m ago"
        diff < TimeUnit.DAYS.toMillis(1) -> "${diff / TimeUnit.HOURS.toMillis(1)}h ago"
        else -> "${diff / TimeUnit.DAYS.toMillis(1)}d ago"
    }
}

// "expires in 3 days", "expires in 5 hours", "expired"; "" for never.
// Same wording as the web's expiryLabel.
fun expiryLabel(expiresAt: Long?, now: Long = System.currentTimeMillis()): String {
    if (expiresAt == null) return ""
    val hours = (expiresAt - now) / 3_600_000.0
    if (hours <= 0) return "expired"
    if (hours < 1) return "expires within the hour"
    val (n, unit) = if (hours < 23) Math.round(hours) to "hour" else Math.round(hours / 24) to "day"
    return "expires in $n $unit${if (n == 1L) "" else "s"}"
}

fun lifetimeLabel(days: Int?): String = if (days == null) "Never expires" else "Expires in $days day${if (days == 1) "" else "s"}"

/** A note's filename without its .md extension, in any case. */
fun noteStem(path: String): String =
    path.substringAfterLast('/').let { if (it.endsWith(".md", ignoreCase = true)) it.dropLast(3) else it }

fun noteTitle(path: String, content: String): String = effectiveTitle(content, noteStem(path))

// Relevance bucket mirroring the web client's titleScore (rank.ts):
// title-prefix first, body-only mentions last. Lower wins.
fun searchRank(title: String, path: String, query: String): Int {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return 4
    val t = title.lowercase()
    if (t.startsWith(q)) return 0
    val tokens = q.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return 4
    val words = t.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
    if (tokens.any { tok -> words.any { w -> w.startsWith(tok) } }) return 1
    if (tokens.any { tok -> t.contains(tok) }) return 2
    val p = path.lowercase()
    if (tokens.any { tok -> p.contains(tok) }) return 3
    return 4
}

// First query-matching line, trimmed for result rows. Null when the content
// doesn't contain the query (e.g. path-only local matches).
fun snippet(content: String, query: String, maxLen: Int = 140): String? {
    val line = content.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() && it.contains(query, ignoreCase = true) }
        ?: return null
    if (line.length <= maxLen) return line
    val at = line.indexOf(query, ignoreCase = true).coerceAtLeast(0)
    val start = (at - 40).coerceAtLeast(0)
    val window = line.substring(start, (start + maxLen).coerceAtMost(line.length))
    return (if (start > 0) "…" else "") + window + (if (start + maxLen < line.length) "…" else "")
}

// Ported from the web's editor/frontmatter.ts without a YAML parser: only
// the top-level `title:` key counts, its value may continue on deeper
// indented lines, and headings inside code fences are not titles.

/** Frontmatter `title:` wins, then the first `# ` heading below the block, then [fallback]. */
fun effectiveTitle(content: String, fallback: String): String {
    val lines = content.lines()
    val fm = frontmatterRange(lines)
    if (fm != null) {
        titleKey(fm.lines)?.let { (start, end) -> scalarValue(fm.lines.subList(start, end)) }?.let { return it.take(200) }
    }
    return firstHeading(lines, fm?.let { it.endInclusive + 1 } ?: 0)?.value?.take(200) ?: fallback
}

// Rewrites the same source effectiveTitle reads (the top-level key if the
// block has one, else the first body heading). Null when neither exists:
// it never injects a title.
fun setEffectiveTitle(content: String, newTitle: String): String? {
    val lines = content.lines().toMutableList()
    val fm = frontmatterRange(lines)
    val key = fm?.let { titleKey(it.lines) }
    if (fm != null && key != null) {
        val at = fm.start + 1 + key.first
        repeat(key.second - key.first) { lines.removeAt(at) }
        lines.add(at, "title: ${yamlScalar(newTitle)}")
        return lines.joinToString("\n")
    }
    val h = firstHeading(lines, fm?.let { it.endInclusive + 1 } ?: 0) ?: return null
    lines[h.index] = "# $newTitle"
    return lines.joinToString("\n")
}

/**
 * A renamed note takes its new filename as title only when its title was
 * the old filename. A title that already differed is the author's and stays,
 * so title->filename and filename->title sync never undo each other.
 */
fun retitle(content: String, oldPath: String, newPath: String): String {
    fun stem(p: String) = if (p.endsWith(".md", ignoreCase = true)) noteStem(p) else null
    val was = stem(oldPath) ?: return content
    val now = stem(newPath) ?: return content
    if (was == now || sanitizeTitleForFilename(effectiveTitle(content, "")) != was) return content
    return setEffectiveTitle(content, now) ?: content
}

fun sanitizeTitleForFilename(raw: String): String? = sanitizePathSegment(raw)

private data class FmRange(val start: Int, val endInclusive: Int, val lines: List<String>)

private fun frontmatterRange(lines: List<String>): FmRange? {
    if (lines.isEmpty() || lines[0].trimEnd() != "---") return null
    for (i in 1 until lines.size) {
        val t = lines[i].trimEnd()
        if (t == "---" || t == "...") return FmRange(0, i, lines.subList(1, i))
    }
    return null
}

private val TITLE_KEY = Regex("^title\\s*:")

// Lines [first, second) of the top-level title key and its continuation.
private fun titleKey(fm: List<String>): Pair<Int, Int>? {
    val start = fm.indexOfFirst { TITLE_KEY.containsMatchIn(it) }
    if (start < 0) return null
    var end = start + 1
    for (j in start + 1 until fm.size) {
        if (fm[j].isBlank()) continue
        if (!fm[j][0].isWhitespace()) break
        end = j + 1
    }
    return start to end
}

// The key's value on one line: block (| >) and multi-line plain or quoted
// values are folded with spaces, quotes and plain-scalar comments removed.
private fun scalarValue(lines: List<String>): String? {
    val first = lines[0].substringAfter(':').trim()
    val rest = lines.drop(1).map { it.trim() }.filter { it.isNotEmpty() }
    val raw = (if (first.startsWith('|') || first.startsWith('>')) rest else listOf(first) + rest).joinToString(" ").trim()
    val value = when {
        raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"') ->
            raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        raw.length >= 2 && raw.startsWith('\'') && raw.endsWith('\'') ->
            raw.substring(1, raw.length - 1).replace("''", "'")
        else -> raw.substringBefore(" #")
    }.trim()
    return value.ifEmpty { null }
}

private val FENCE = Regex("^ {0,3}(`{3,}|~{3,})")
private val HEADING = Regex("^#\\s+(.+?)\\s*$")

// First `# ` heading at or after line [from], outside code fences.
private fun firstHeading(lines: List<String>, from: Int): IndexedValue<String>? {
    var fence = ""
    for (i in from until lines.size) {
        val line = lines[i]
        val f = FENCE.find(line)?.groupValues?.get(1)
        if (fence.isNotEmpty()) {
            if (f != null && f[0] == fence[0] && f.length >= fence.length && line.trim().drop(f.length).isBlank()) fence = ""
        } else if (f != null) {
            fence = f
        } else {
            HEADING.matchEntire(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return IndexedValue(i, it) }
        }
    }
    return null
}

private val PLAIN = Regex("^[A-Za-z][A-Za-z0-9 _\\-/().]*$")
private val YAML_WORD = Regex("^(true|false|yes|no|on|off|null|y|n)$", RegexOption.IGNORE_CASE)

// Bare when YAML is sure to read it back as the same string, else quoted.
private fun yamlScalar(s: String): String =
    if (PLAIN.matches(s) && s == s.trim() && !YAML_WORD.matches(s)) s
    else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
