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

fun noteTitle(path: String, content: String): String {
    val heading = content.lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.takeIf { it.startsWith("#") }
        ?.trimStart('#')
        ?.trim()
    if (!heading.isNullOrBlank()) return heading
    return path.substringAfterLast('/').removeSuffix(".md")
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

// Title source mirrors the web client: frontmatter `title:` wins, else the
// first `# ` heading, else the filename stem.
fun effectiveTitle(content: String, fallback: String): String {
    frontmatterTitle(content)?.takeIf { it.isNotBlank() }?.let { return it }
    firstH1(content)?.takeIf { it.isNotBlank() }?.let { return it }
    return fallback
}

fun firstH1(content: String): String? {
    val body = withoutFrontmatter(content)
    return body.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("# ") }
        ?.drop(2)?.trim()?.takeIf { it.isNotEmpty() }
}

// Rewrites the same source effectiveTitle read from (fm key if the block
// owns one, else the body H1). Null when neither exists — never injects.
fun setEffectiveTitle(content: String, newTitle: String): String? {
    val lines = content.lines()
    val fm = frontmatterRange(lines)
    if (fm != null) {
        val idx = fm.lines.indices.firstOrNull { i ->
            fm.lines[i].trimStart().startsWith("title:")
        }
        if (idx != null) {
            val quote = fm.lines[idx].trimEnd().lastOrNull()?.takeIf { it == '"' || it == '\'' }
            val replacement = if (quote != null) "title: $quote$newTitle$quote" else "title: $newTitle"
            val out = lines.toMutableList()
            out[fm.start + 1 + idx] = replacement
            return out.joinToString("\n")
        }
    }
    val bodyOffset = if (fm != null) fm.endInclusive + 1 else 0
    val h1 = lines.withIndex().drop(bodyOffset).firstOrNull { (_, line) ->
        line.trimStart().startsWith("# ")
    } ?: return null
    val out = lines.toMutableList()
    val indent = h1.value.takeWhile { it == ' ' || it == '\t' }
    out[h1.index] = "$indent# $newTitle"
    return out.joinToString("\n")
}

fun sanitizeTitleForFilename(raw: String): String? = sanitizePathSegment(raw)

private data class FmRange(val start: Int, val endInclusive: Int, val lines: List<String>)

private fun frontmatterRange(lines: List<String>): FmRange? {
    if (lines.isEmpty() || lines[0].trim() != "---") return null
    for (i in 1 until lines.size) {
        val t = lines[i].trim()
        if (t == "---" || t == "...") {
            return FmRange(0, i, lines.subList(1, i))
        }
    }
    return null
}

private fun withoutFrontmatter(content: String): String {
    val lines = content.lines()
    val fm = frontmatterRange(lines) ?: return content
    return lines.drop(fm.endInclusive + 1).joinToString("\n")
}

private fun frontmatterTitle(content: String): String? {
    val lines = content.lines()
    val fm = frontmatterRange(lines) ?: return null
    val raw = fm.lines.firstOrNull { it.trimStart().startsWith("title:") }
        ?.substringAfter("title:")?.trim() ?: return null
    if (raw.length >= 2 && (raw.startsWith('"') && raw.endsWith('"') || raw.startsWith('\'') && raw.endsWith('\''))) {
        return raw.substring(1, raw.length - 1)
    }
    return raw
}