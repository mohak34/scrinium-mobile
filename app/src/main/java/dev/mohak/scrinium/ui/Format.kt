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