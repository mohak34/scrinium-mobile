package dev.mohak.scrinium.ui

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