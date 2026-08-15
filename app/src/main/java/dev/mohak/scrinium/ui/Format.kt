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