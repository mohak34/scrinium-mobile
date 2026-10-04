package dev.mohak.scrinium.ui

// Editor autocomplete, ported from the web's wikiComplete.ts and
// tagComplete.ts: note names after `[[`, vault tags after `#`.

/** Replace [from, cursor) with [insert]. */
data class Suggestion(val label: String, val detail: String?, val from: Int, val insert: String)

private val wikiTrigger = Regex("""\[\[([^\]\[#|\n]*)$""")
private val tagTrigger = Regex("""(?:^|[\s(\[{'">])#([A-Za-z0-9/_-]*)$""")

fun completions(text: String, cursor: Int, notePaths: List<String>, tags: List<String>): List<Suggestion> {
    if (cursor !in 0..text.length) return emptyList()
    val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
    val before = text.substring(lineStart, cursor)
    wikiTrigger.find(before)?.let { m ->
        val typed = m.groupValues[1].lowercase()
        val from = cursor - m.groupValues[1].length
        return notePaths.asSequence()
            .map { it to it.substringAfterLast('/').removeSuffix(".md") }
            .filter { (_, stem) -> typed in stem.lowercase() }
            .sortedWith(compareBy({ !it.second.lowercase().startsWith(typed) }, { it.second.lowercase() }))
            .take(20)
            .map { (path, stem) -> Suggestion(stem, path.takeIf { '/' in it }, from, "$stem]]") }
            .toList()
    }
    tagTrigger.find(before)?.let { m ->
        val typed = m.groupValues[1].lowercase()
        val from = cursor - m.groupValues[1].length
        return tags.asSequence()
            .filter { it.lowercase().startsWith(typed) && it.lowercase() != typed }
            .take(20)
            .map { Suggestion("#$it", null, from, "$it ") }
            .toList()
    }
    return emptyList()
}

private val vaultTagRe = Regex("""(?<!\S)#([A-Za-z][\w/-]*)""")

/** Inline `#tags` across notes, most used first. */
fun vaultTags(contents: List<String>): List<String> =
    contents.flatMap { c -> vaultTagRe.findAll(c).map { it.groupValues[1] }.toList() }
        .groupingBy { it }.eachCount()
        .entries.sortedWith(compareBy({ -it.value }, { it.key.lowercase() }))
        .map { it.key }
