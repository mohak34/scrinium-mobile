package dev.mohak.scrinium.ui

// Kotlin port of the web client's wikilinks.ts backlink helpers. Every note
// is already in Room, so backlinks are computed on the phone: no request,
// and they work offline.

private val wikilinkRe = Regex("""\[\[([^\]\[#|\n]+)(?:#([^\]|]*))?(?:\|([^\]]*))?]]""")

private data class Wikilink(val from: Int, val to: Int, val target: String, val alias: String?)

data class Backlink(val path: String, val excerpt: String)

data class NoteBacklinks(val linked: List<Backlink>, val unlinked: List<Backlink>)

private fun stem(path: String): String = path.substringAfterLast('/').replace(Regex("""\.md$""", RegexOption.IGNORE_CASE), "")

// Image embeds (`![[...]]`) are not links.
private fun findWikilinks(text: String): List<Wikilink> =
    wikilinkRe.findAll(text)
        .filter { m -> m.range.first == 0 || text[m.range.first - 1] != '!' }
        .mapNotNull { m ->
            val target = m.groupValues[1].trim()
            if (target.isEmpty()) return@mapNotNull null
            val alias = m.groups[3]?.value
            Wikilink(m.range.first, m.range.last + 1, target, alias)
        }
        .toList()

/**
 * The note a `[[target]]` written in [sourcePath] opens. Vault path first
 * (`target` or `target.md`), then the same path relative to the source's
 * folder, then a case-insensitive stem match that must be unique. Null when
 * missing or ambiguous. Navigation, backlinks and autocomplete all use this
 * so they agree on where a link goes.
 */
fun resolveWikilink(target: String, sourcePath: String, notePaths: List<String>): String? {
    val t = target.substringBefore('#').trim()
    if (t.isEmpty()) return null
    val withExt = if (t.endsWith(".md", ignoreCase = true)) t else "$t.md"
    val dir = sourcePath.substringBeforeLast('/', "")
    for (c in listOfNotNull(withExt, dir.ifEmpty { null }?.let { "$it/$withExt" })) {
        notePaths.firstOrNull { it.equals(c, ignoreCase = true) }?.let { return it }
    }
    val want = stem(withExt).lowercase()
    return notePaths.filter { stem(it).lowercase() == want }.singleOrNull()
}

// A line with links replaced by their display text, trimmed for a list row.
private fun plainExcerpt(line: String): String {
    val out = StringBuilder()
    var last = 0
    for (w in findWikilinks(line)) {
        out.append(line, last, w.from)
        out.append(w.alias?.trim()?.takeIf { it.isNotEmpty() } ?: w.target)
        last = w.to
    }
    out.append(line.substring(last))
    return out.toString().trim().take(160)
}

private fun mentionRegex(target: String): Regex? {
    val name = stem(target)
    if (name.length < 2) return null
    return Regex("""\b(${Regex.escape(name)})\b""", RegexOption.IGNORE_CASE)
}

/**
 * Notes linking to [target] (first matching line as excerpt), plus notes
 * naming it in plain text outside any `[[...]]`.
 */
fun findBacklinks(target: String, notes: List<Pair<String, String>>): NoteBacklinks {
    val paths = notes.map { it.first }
    val mention = mentionRegex(target)
    val linked = mutableListOf<Backlink>()
    val unlinked = mutableListOf<Backlink>()
    for ((path, content) in notes) {
        if (path == target) continue
        val lines = content.split('\n')
        lines.firstOrNull { line ->
            findWikilinks(line).any { resolveWikilink(it.target, path, paths) == target }
        }?.let { linked += Backlink(path, plainExcerpt(it)) }
        if (mention == null) continue
        lines.firstOrNull { line ->
            val spans = findWikilinks(line)
            mention.findAll(line).any { m -> spans.none { m.range.first >= it.from && m.range.first < it.to } }
        }?.let { unlinked += Backlink(path, plainExcerpt(it)) }
    }
    return NoteBacklinks(linked.sortedBy { it.path }, unlinked.sortedBy { it.path })
}

/**
 * Wraps the first bare mention of [target]'s name in `[[...]]`, keeping the
 * author's casing. Null when there is nothing to convert.
 */
fun linkFirstMention(content: String, target: String): String? {
    val re = mentionRegex(target) ?: return null
    val lines = content.split('\n').toMutableList()
    for (i in lines.indices) {
        val spans = findWikilinks(lines[i])
        val m = re.findAll(lines[i]).firstOrNull { m ->
            spans.none { m.range.first >= it.from && m.range.first < it.to }
        } ?: continue
        lines[i] = lines[i].replaceRange(m.range, "[[${m.value}]]")
        return lines.joinToString("\n")
    }
    return null
}
