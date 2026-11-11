package dev.mohak.scrinium.ui

// Kotlin port of the web client's wikilinks.ts backlink helpers. Every note
// is already in Room, so backlinks are computed on the phone: no request,
// and they work offline.

private val wikilinkRe = Regex("""\[\[([^\]\[#|\n]+)(?:#([^\]|]*))?(?:\|([^\]]*))?]]""")

private data class Wikilink(val from: Int, val to: Int, val target: String, val alias: String?)

data class Backlink(val path: String, val excerpt: String)

data class NoteBacklinks(val linked: List<Backlink>, val unlinked: List<Backlink>)

private val mdExt = Regex("""\.md$""", RegexOption.IGNORE_CASE)

private fun stem(path: String): String = path.substringAfterLast('/').replace(mdExt, "")

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
 * Link lookups over one list of note paths, built once so resolving many
 * links costs a map hit each instead of a scan of every path.
 *
 * [resolve] gives the note a `[[target]]` written in sourcePath opens. Vault
 * path first (`target` or `target.md`), then the same path relative to the
 * source's folder, then a case-insensitive stem match that must be unique.
 * Null when missing or ambiguous. Navigation, backlinks and autocomplete all
 * use this so they agree on where a link goes.
 */
class WikiResolver(notePaths: List<String>) {
    private val byPath = HashMap<String, String>()
    private val byStem = HashMap<String, MutableList<String>>()

    init {
        for (p in notePaths) {
            byPath.putIfAbsent(p.lowercase(), p)
            byStem.getOrPut(stem(p).lowercase()) { mutableListOf() } += p
        }
    }

    fun resolve(target: String, sourcePath: String): String? {
        val t = target.substringBefore('#').trim()
        if (t.isEmpty()) return null
        val withExt = if (t.endsWith(".md", ignoreCase = true)) t else "$t.md"
        val dir = sourcePath.substringBeforeLast('/', "")
        byPath[withExt.lowercase()]?.let { return it }
        if (dir.isNotEmpty()) byPath["$dir/$withExt".lowercase()]?.let { return it }
        return byStem[stem(withExt).lowercase()]?.singleOrNull()
    }

    /** How a link in sourcePath should name [path]: its bare name when that resolves back to it, else its vault path. */
    fun targetFor(path: String, sourcePath: String): String {
        val name = stem(path)
        return if (resolve(name, sourcePath) == path) name else path.replace(mdExt, "")
    }
}

/** One-off [WikiResolver.resolve]; build a [WikiResolver] for many links. */
fun resolveWikilink(target: String, sourcePath: String, notePaths: List<String>): String? =
    WikiResolver(notePaths).resolve(target, sourcePath)

/**
 * [content] with code blanked out: fenced blocks (``` or ~~~) and inline
 * `code` spans become spaces, so offsets and line numbers still match the
 * original. Links and mentions inside code are text, not links.
 */
fun maskCode(content: String): String {
    val lines = content.split('\n')
    var fence: String? = null
    return lines.joinToString("\n") { line ->
        val trimmed = line.trimStart()
        val open = fence
        when {
            open != null -> {
                if (trimmed.startsWith(open)) fence = null
                " ".repeat(line.length)
            }
            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                fence = trimmed.take(3)
                " ".repeat(line.length)
            }
            else -> inlineCode.replace(line) { " ".repeat(it.value.length) }
        }
    }
}

private val inlineCode = Regex("""(`+)[^`].*?\1""")

/**
 * The vault path a relative Markdown link `[text](href)` in [sourcePath]
 * reaches, percent-decoded, without its `#anchor` or `?query`. A leading `/`
 * starts at the vault root. Null for external links (anything with a scheme),
 * anchor-only links and paths that climb above the vault.
 */
fun resolveMarkdownLink(href: String, sourcePath: String): String? {
    var clean = linkDestination(href)
    if (clean.isEmpty() || isExternalLink(clean)) return null
    clean = clean.substringBefore('#').substringBefore('?')
    if (clean.isEmpty()) return null
    val parts = ArrayDeque<String>()
    if (!clean.startsWith("/")) sourcePath.split('/').dropLast(1).filter { it.isNotEmpty() }.forEach(parts::addLast)
    for (seg in clean.split('/')) {
        when (seg) {
            "", "." -> Unit
            ".." -> parts.removeLastOrNull() ?: return null
            else -> parts.addLast(percentDecode(seg))
        }
    }
    return parts.joinToString("/").ifEmpty { null }
}

/** The URL of a `[text](href)` destination: angle brackets dropped, a trailing `"title"` cut. */
fun linkDestination(href: String): String {
    val t = href.trim()
    return if (t.startsWith("<")) t.drop(1).substringBefore('>').trim() else t.substringBefore(' ')
}

/** A link with a scheme (`https:`, `mailto:`), opened outside the app. */
fun isExternalLink(href: String): Boolean = schemeRe.containsMatchIn(linkDestination(href))

private val schemeRe = Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE)

// decodeURIComponent: `+` stays a plus; a malformed escape keeps the segment as written.
private fun percentDecode(seg: String): String =
    try {
        java.net.URLDecoder.decode(seg.replace("+", "%2B"), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        seg
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
 * naming it in plain text outside any `[[...]]`. Code is neither.
 */
fun findBacklinks(target: String, notes: List<Pair<String, String>>): NoteBacklinks {
    val links = WikiResolver(notes.map { it.first })
    val mention = mentionRegex(target)
    val name = stem(target).lowercase()
    val linked = mutableListOf<Backlink>()
    val unlinked = mutableListOf<Backlink>()
    for ((path, content) in notes) {
        if (path == target) continue
        // Every link names the target's stem, its path, or a folder-relative
        // path ending in the stem, so a note without the stem can't match.
        if (!content.lowercase().contains(name)) continue
        val lines = content.split('\n')
        val masked = maskCode(content).split('\n')
        masked.indexOfFirst { line ->
            findWikilinks(line).any { links.resolve(it.target, path) == target }
        }.takeIf { it >= 0 }?.let { linked += Backlink(path, plainExcerpt(lines[it])) }
        if (mention == null) continue
        masked.indexOfFirst { line -> bareMention(line, mention) != null }
            .takeIf { it >= 0 }?.let { unlinked += Backlink(path, plainExcerpt(lines[it])) }
    }
    return NoteBacklinks(linked.sortedBy { it.path }, unlinked.sortedBy { it.path })
}

private fun bareMention(line: String, mention: Regex): MatchResult? {
    val spans = findWikilinks(line)
    return mention.findAll(line).firstOrNull { m -> spans.none { m.range.first >= it.from && m.range.first < it.to } }
}

/**
 * Wraps the first bare mention of [target]'s name in [sourcePath] in
 * `[[...]]`, keeping the author's casing. When the bare name would open
 * another note from there, it links by path with the mention as alias.
 * Code is skipped. Null when there is nothing to convert.
 */
fun linkFirstMention(content: String, sourcePath: String, target: String, links: WikiResolver): String? {
    val re = mentionRegex(target) ?: return null
    val named = links.targetFor(target, sourcePath)
    val lines = content.split('\n').toMutableList()
    val masked = maskCode(content).split('\n')
    for (i in lines.indices) {
        val m = bareMention(masked[i], re) ?: continue
        val written = lines[i].substring(m.range)
        val link = if (named == stem(target)) "[[$written]]" else "[[$named|$written]]"
        lines[i] = lines[i].replaceRange(m.range, link)
        return lines.joinToString("\n")
    }
    return null
}
